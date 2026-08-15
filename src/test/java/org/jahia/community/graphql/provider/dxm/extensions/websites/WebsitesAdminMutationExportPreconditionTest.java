package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.api.settings.SettingsBean;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.Test;
import org.mockito.MockedStatic;

import javax.jcr.RepositoryException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Pins the two preconditions of {@link WebsitesAdminMutation#exportAllSites()} and their ordering.
 *
 * <p><b>Administrator gate (SEC-136 §4.3).</b> A bulk export spans the whole instance, so it is
 * restricted to server administrators. Running it under a delegated holder's own session would
 * produce an archive silently containing only the sites that holder can read — a partial backup
 * that looks complete, which is worse than a refusal.
 *
 * <p><b>S3 precondition ordering.</b> The configuration check used to run <em>after</em>
 * {@code exportAllSites(Path)}: an unconfigured instance would export every site to disk —
 * potentially minutes of CPU and gigabytes of churn — then delete the archive unread in the
 * {@code finally} block and return
 * {@link WebsitesAdminMutation.ExportAllSitesResults#AWS_S3_BUCKET_NOT_CONFIGURED} anyway.
 *
 * <p>The return value alone cannot distinguish the old behaviour from the new, so asserting on it
 * would not catch a regression. These tests exploit the ordering directly: the export path is
 * built from {@code BundleUtils.getOsgiService(SettingsBean.class, null)}, which is only reached
 * after both checks pass. If either check is moved below the export,
 * {@link #exportAllSites_doesNotTouchTheFilesystemWhenS3IsUnconfigured()} fails because
 * SettingsBean gets requested.
 *
 * <p>Only the refusing branches are exercised here. The success branch runs the real Jahia
 * exporter, whose collaborators need a live container — that path is covered end to end by
 * {@code 01-graphqlExtensionWebsites.cy.ts} instead.
 */
public class WebsitesAdminMutationExportPreconditionTest {

    /**
     * Must be built <em>before</em> {@code mockStatic} is opened: stubbing this mock inside an
     * in-progress {@code MockedStatic.when(...)} nests one stubbing inside another and Mockito
     * rejects it with {@code UnfinishedStubbingException}.
     */
    private static GraphQLWebsitesConfig unconfigured() {
        GraphQLWebsitesConfig config = mock(GraphQLWebsitesConfig.class);
        when(config.isConfigured()).thenReturn(false);
        return config;
    }

    /**
     * A session factory whose root node answers {@code admin} as requested — this is what
     * {@code callerIsServerAdministrator()} consults. Built before any {@code mockStatic} scope
     * opens, for the reason above.
     */
    private static JCRSessionFactory sessionFactoryWithAdmin(boolean isAdmin) throws RepositoryException {
        JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        when(root.hasPermission("admin")).thenReturn(isAdmin);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode("/")).thenReturn(root);
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        when(factory.getCurrentUserSession()).thenReturn(session);
        return factory;
    }

    @Test
    public void exportAllSites_returnsNotConfiguredWhenS3IsUnconfigured() throws Exception {
        // Arrange — an administrator, so the run reaches the S3 precondition
        GraphQLWebsitesConfig config = unconfigured();
        JCRSessionFactory sessionFactory = sessionFactoryWithAdmin(true);

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(GraphQLWebsitesConfig.class, null))
                    .thenReturn(config);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            WebsitesAdminMutation.ExportAllSitesResults result = new WebsitesAdminMutation().exportAllSites();

            // Assert
            assertThat(result).isEqualTo(WebsitesAdminMutation.ExportAllSitesResults.AWS_S3_BUCKET_NOT_CONFIGURED);
        }
    }

    @Test
    public void exportAllSites_doesNotTouchTheFilesystemWhenS3IsUnconfigured() throws Exception {
        // Arrange
        GraphQLWebsitesConfig config = unconfigured();
        JCRSessionFactory sessionFactory = sessionFactoryWithAdmin(true);

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(GraphQLWebsitesConfig.class, null))
                    .thenReturn(config);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            new WebsitesAdminMutation().exportAllSites();

            // Assert — SettingsBean is only needed to build the export path, so never asking for it
            // proves the method returned before doing any export work.
            bundleUtils.verify(() -> BundleUtils.getOsgiService(SettingsBean.class, null), never());
        }
    }

    /** SEC-136 §4.3: a non-administrator is refused, whatever the S3 configuration says. */
    @Test
    public void exportAllSites_refusesANonAdministrator() throws Exception {
        // Arrange
        JCRSessionFactory sessionFactory = sessionFactoryWithAdmin(false);

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            WebsitesAdminMutation.ExportAllSitesResults result = new WebsitesAdminMutation().exportAllSites();

            // Assert
            assertThat(result).isEqualTo(WebsitesAdminMutation.ExportAllSitesResults.NOT_SERVER_ADMINISTRATOR);
            bundleUtils.verify(() -> BundleUtils.getOsgiService(SettingsBean.class, null), never());
        }
    }

    /**
     * The administrator gate runs before the S3 check, so an unauthorized caller cannot use the
     * response to learn whether the instance has S3 configured.
     */
    @Test
    public void exportAllSites_refusesANonAdministratorWithoutConsultingTheConfiguration() throws Exception {
        // Arrange
        JCRSessionFactory sessionFactory = sessionFactoryWithAdmin(false);

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            new WebsitesAdminMutation().exportAllSites();

            // Assert — the config service is never even requested
            bundleUtils.verify(() -> BundleUtils.getOsgiService(GraphQLWebsitesConfig.class, null), never());
        }
    }

    @Test
    public void exportAllSitesResults_staysAnActionableOutcomeEnum() {
        // The split is deliberate: unexpected failures are raised as DataFetchingException so their
        // cause survives, rather than being flattened into a constant here. A new value should only
        // appear for an outcome an operator can actually remedy — NOT_SERVER_ADMINISTRATOR
        // qualifies (grant the admin role), AWS_S3_BUCKET_NOT_CONFIGURED qualifies (fix the config).
        assertThat(WebsitesAdminMutation.ExportAllSitesResults.values())
                .containsExactlyInAnyOrder(WebsitesAdminMutation.ExportAllSitesResults.SUCCESS,
                        WebsitesAdminMutation.ExportAllSitesResults.AWS_S3_BUCKET_NOT_CONFIGURED,
                        WebsitesAdminMutation.ExportAllSitesResults.NOT_SERVER_ADMINISTRATOR);
    }
}
