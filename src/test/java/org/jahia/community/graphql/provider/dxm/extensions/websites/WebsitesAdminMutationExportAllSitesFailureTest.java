package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.api.settings.SettingsBean;
import org.jahia.bin.listeners.JahiaContextLoaderListener;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.osgi.BundleUtils;
import org.jahia.services.SpringContextSingleton;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.sites.JahiaSitesService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import javax.jcr.RepositoryException;
import javax.servlet.ServletContext;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The half of {@link WebsitesAdminMutation#exportAllSites()} that the precondition tests cannot
 * reach: what happens once both gates have passed.
 *
 * <h3>H3 — the {@link DataFetchingException} contract</h3>
 *
 * <p>The mutation reports outcomes on two deliberate channels: an
 * {@link WebsitesAdminMutation.ExportAllSitesResults} value for an expected, operator-actionable
 * outcome, and a {@link DataFetchingException} for anything unexpected, so the cause survives into
 * the GraphQL error extensions and the logs. The enum half was asserted twice in the suite; the
 * exception half was asserted nowhere, and neither was the {@code finally} block that removes the
 * half-written archive. Collapsing the exception into a new enum constant — the "tidier" design an
 * unaware reader will reach for — would discard exactly the diagnostic detail the split exists to
 * preserve, and nothing failed.
 *
 * <h3>M2 — no re-escalation, checked at runtime</h3>
 *
 * <p>{@code WebsitesAdminMutationExportAllSitesTest} pins the absence of the historical escalation
 * mechanism ({@code setCurrentUser} / {@code getRootUserSession} / …) by scanning the compiled
 * constant pool. That scan cannot see the escalation route available <em>today</em>: wrapping the
 * export in {@code JCRTemplate.getInstance().doExecuteWithSystemSession(...)} introduces no new
 * token, because {@code createSiteByKey} and {@code doImportFiles} already legitimately use it
 * elsewhere in the same class.
 *
 * <p>{@link #exportAllSites_runsTheExportWithoutAskingForASystemSession()} closes that hole from
 * the other side: it drives the mutation all the way into the export call with
 * {@link JCRTemplate} static-mocked, and fails if the singleton is so much as consulted.
 */
public class WebsitesAdminMutationExportAllSitesFailureTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File varDiskPath;
    private Path exportsDir;

    @Before
    public void createExportsDirectory() throws Exception {
        varDiskPath = tmp.newFolder("var");
        exportsDir = new File(varDiskPath, "exports").toPath();
        Files.createDirectories(exportsDir);
    }

    /** Built before any {@code mockStatic} scope opens, to avoid {@code UnfinishedStubbingException}. */
    private SettingsBean settings() {
        SettingsBean settings = mock(SettingsBean.class);
        when(settings.getJahiaVarDiskPath()).thenReturn(varDiskPath.getAbsolutePath());
        return settings;
    }

    private static GraphQLWebsitesConfig configured() {
        GraphQLWebsitesConfig config = mock(GraphQLWebsitesConfig.class);
        when(config.isConfigured()).thenReturn(true);
        return config;
    }

    private static JCRSessionFactory administratorSession() throws RepositoryException {
        JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        when(root.hasPermission("admin")).thenReturn(true);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode("/")).thenReturn(root);
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        when(factory.getCurrentUserSession()).thenReturn(session);
        return factory;
    }

    /**
     * An unexpected failure inside the export must surface as a {@link DataFetchingException}
     * rather than as an enum value.
     *
     * <p>The failure injected here is the genuine one a container-free run hits: the export builds
     * its XSL path from {@code JahiaContextLoaderListener.getServletContext()}, which is
     * {@code null} outside a servlet container, so the export fails before writing anything. What
     * matters is not <em>which</em> exception occurs but that an arbitrary unchecked failure is
     * wrapped, and that its cause is preserved rather than flattened away.
     *
     * <p>The assertions below say exactly that and no more. Pinning the concrete cause type would
     * pin an incidental property of running outside a container — add a null guard to that lookup
     * and the failure becomes some other exception, breaking this test for a reason that has
     * nothing to do with the two-channel contract it exists to protect.
     */
    @Test
    public void exportAllSites_wrapsAnUnexpectedFailureInADataFetchingException() throws Exception {
        // Arrange — administrator + configured S3, so both preconditions pass
        SettingsBean settings = settings();
        GraphQLWebsitesConfig config = configured();
        JCRSessionFactory sessionFactory = administratorSession();

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(GraphQLWebsitesConfig.class, null)).thenReturn(config);
            bundleUtils.when(() -> BundleUtils.getOsgiService(SettingsBean.class, null)).thenReturn(settings);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Throwable thrown = catchThrowable(() -> new WebsitesAdminMutation().exportAllSites());

            // Assert
            assertThat(thrown)
                    .as("an unexpected failure must reach the GraphQL error channel, not be "
                            + "flattened into an ExportAllSitesResults constant")
                    .isInstanceOf(DataFetchingException.class);
            assertThat(thrown.getCause())
                    .as("the cause is the whole point of the exception channel: it is what survives "
                            + "into the GraphQL error extensions and the log. Wrapping without a "
                            + "cause discards the diagnosis just as thoroughly as returning an enum "
                            + "constant would")
                    .isNotNull();
        }
    }

    /**
     * M2 + the {@code finally} block. This drives the mutation past the XSL lookup and into the
     * export call itself, then asserts three things that no other test covers:
     *
     * <ul>
     *   <li><b>No escalation.</b> {@link JCRTemplate} — the only remaining way to obtain a system
     *       session in this codebase — is never consulted during a bulk export. A future
     *       {@code doExecuteWithSystemSession(...)} wrapper around the export would restore the
     *       pre-SEC-136 vulnerability while adding no new bytecode token for the constant-pool
     *       scan to catch; it fails here instead.</li>
     *   <li><b>Caller-scoped enumeration.</b> The site list really does come from
     *       {@code JahiaSitesService.getSitesNodeList()}, evaluated as an argument before the
     *       export call.</li>
     *   <li><b>Cleanup on failure.</b> The archive file is created by the export
     *       ({@code new FileOutputStream(...)}) and must be gone afterwards — that is the
     *       {@code finally deleteExportArtifact(...)}, which nothing else exercises.</li>
     * </ul>
     */
    @Test
    public void exportAllSites_runsTheExportWithoutAskingForASystemSession() throws Exception {
        // Arrange
        SettingsBean settings = settings();
        GraphQLWebsitesConfig config = configured();
        JCRSessionFactory sessionFactory = administratorSession();
        ServletContext servletContext = mock(ServletContext.class);
        when(servletContext.getRealPath("/WEB-INF/etc/repository/export/cleanup.xsl"))
                .thenReturn("/etc/jahia/cleanup.xsl");
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        when(sitesService.getSitesNodeList()).thenReturn(Collections.emptyList());

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class);
             MockedStatic<JahiaContextLoaderListener> listenerStatic = mockStatic(JahiaContextLoaderListener.class);
             MockedStatic<JahiaSitesService> sitesStatic = mockStatic(JahiaSitesService.class);
             MockedStatic<SpringContextSingleton> springStatic = mockStatic(SpringContextSingleton.class);
             MockedStatic<JCRTemplate> jcrTemplateStatic = mockStatic(JCRTemplate.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(GraphQLWebsitesConfig.class, null)).thenReturn(config);
            bundleUtils.when(() -> BundleUtils.getOsgiService(SettingsBean.class, null)).thenReturn(settings);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);
            listenerStatic.when(JahiaContextLoaderListener::getServletContext).thenReturn(servletContext);
            sitesStatic.when(JahiaSitesService::getInstance).thenReturn(sitesService);
            // The bean is null, so the export call itself fails — after the site list has been
            // enumerated and after the archive file has been created on disk. The real
            // ImportExportBaseService cannot be instantiated outside a running Jahia.
            springStatic.when(() -> SpringContextSingleton.getBean("ImportExportService")).thenReturn(null);

            // Act
            assertThatThrownBy(() -> new WebsitesAdminMutation().exportAllSites())
                    .isInstanceOf(DataFetchingException.class);

            // Assert
            jcrTemplateStatic.verify(JCRTemplate::getInstance, never());
            verify(sitesService, times(1)).getSitesNodeList();
        }

        assertThat(exportsDir.toFile().listFiles())
                .as("the finally block must remove the half-written archive; leaving it behind is "
                        + "how stale multi-gigabyte exports accumulate in the exports directory")
                .isEmpty();
    }
}
