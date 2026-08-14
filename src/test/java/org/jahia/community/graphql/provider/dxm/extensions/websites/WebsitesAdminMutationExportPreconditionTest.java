package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.api.settings.SettingsBean;
import org.jahia.osgi.BundleUtils;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Pins the S3 precondition ordering in {@link WebsitesAdminMutation#exportAllSites()}.
 *
 * <p>The check used to run <em>after</em> {@code exportAllSites(Path)}: an instance with no S3
 * configuration would export every site to disk — potentially minutes of CPU and gigabytes of
 * churn — and then delete the archive unread in the {@code finally} block, returning
 * {@link WebsitesAdminMutation.ExportAllSitesResults#AWS_S3_BUCKET_NOT_CONFIGURED} anyway.
 *
 * <p>The return value alone cannot distinguish the old behaviour from the new, so asserting on it
 * would not catch a regression. Instead these tests exploit the ordering directly: the export path
 * is built from {@code BundleUtils.getOsgiService(SettingsBean.class, null)}, which is now only
 * reached <em>after</em> the configuration check passes. If the check is ever moved back below the
 * export, {@link #exportAllSites_doesNotTouchTheFilesystemWhenS3IsUnconfigured()} fails because
 * SettingsBean gets requested.
 *
 * <p>Only the unconfigured branch is exercised here. The configured branch runs the real Jahia
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

    @Test
    public void exportAllSites_returnsNotConfiguredWhenS3IsUnconfigured() {
        // Arrange
        GraphQLWebsitesConfig config = unconfigured();
        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(GraphQLWebsitesConfig.class, null))
                    .thenReturn(config);

            // Act
            WebsitesAdminMutation.ExportAllSitesResults result = new WebsitesAdminMutation().exportAllSites();

            // Assert
            assertThat(result).isEqualTo(WebsitesAdminMutation.ExportAllSitesResults.AWS_S3_BUCKET_NOT_CONFIGURED);
        }
    }

    @Test
    public void exportAllSites_doesNotTouchTheFilesystemWhenS3IsUnconfigured() {
        // Arrange
        GraphQLWebsitesConfig config = unconfigured();
        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(GraphQLWebsitesConfig.class, null))
                    .thenReturn(config);

            // Act
            new WebsitesAdminMutation().exportAllSites();

            // Assert — SettingsBean is only needed to build the export path, so never asking for it
            // proves the method returned before doing any export work.
            bundleUtils.verify(() -> BundleUtils.getOsgiService(SettingsBean.class, null), never());
        }
    }

    @Test
    public void exportAllSitesResults_staysAnActionableOutcomeEnum() {
        // The split is deliberate: unexpected failures are raised as DataFetchingException so their
        // cause survives, rather than being flattened into a constant here. A new value should only
        // appear for an outcome an operator can actually remedy.
        assertThat(WebsitesAdminMutation.ExportAllSitesResults.values())
                .containsExactlyInAnyOrder(WebsitesAdminMutation.ExportAllSitesResults.SUCCESS,
                        WebsitesAdminMutation.ExportAllSitesResults.AWS_S3_BUCKET_NOT_CONFIGURED);
    }
}
