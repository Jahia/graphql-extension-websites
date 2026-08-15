package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.api.settings.SettingsBean;
import org.jahia.osgi.BundleUtils;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedStatic;

import javax.jcr.RepositoryException;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * SEC-136 — the two in-body guards of {@link WebsitesAdminMutation#importWebsite(String, String)}:
 * the server-administrator gate and the traversal check on the untrusted {@code siteKey}.
 *
 * <p>{@code importWebsite} is the most dangerous mutation in the module: it imports arbitrary
 * <em>users and roles</em>, which no session de-escalation can bound, so the coarse
 * {@code websitesAdmin} annotation is deliberately not its real authorization. The real gate is
 * {@code callerIsServerAdministrator()} in the method body — and before this class it had
 * <em>zero</em> coverage: deleting those four lines left the whole suite green.
 *
 * <h3>How the "no work was reached" assertions work</h3>
 *
 * <p>A bare {@code assertThat(result).isFalse()} proves nothing here — {@code importWebsite}
 * returns {@code false} for a rejected path, a missing {@code export.properties} and a failed site
 * import too. Every refusal test therefore also asserts against two never-called collaborators,
 * each of which the method <em>must</em> touch if it gets past the guard:
 *
 * <ul>
 *   <li>{@code BundleUtils.getOsgiService(SettingsBean.class, null)} — the very first thing
 *       {@code resolveImportRoot} does, so never requesting it proves the administrator gate
 *       returned before any path resolution;</li>
 *   <li>{@code ServicesRegistry.getInstance()} — first touched by {@code runImport}, so never
 *       calling it proves nothing was imported.</li>
 * </ul>
 *
 * <h3>The sentinel</h3>
 *
 * <p>The allowed path cannot run to completion in a unit test: {@code runImport} delegates to the
 * concrete {@code ImportExportBaseService}, which cannot be class-initialized outside a running
 * Jahia. Instead {@code ServicesRegistry.getImportExportService()} is stubbed to throw
 * {@link #SENTINEL}, so "the import was actually entered" becomes a positive, unambiguous
 * assertion rather than an absence. That is what stops these tests from passing against an
 * always-deny gate.
 */
public class WebsitesAdminMutationImportGateTest {

    /** Marks the exact point where {@code runImport} starts doing real work. */
    private static final String SENTINEL = "REACHED_RUN_IMPORT";

    private static final String IMPORT_PATH = "staged-tree";
    private static final String SITE_KEY = "cypress-roundtrip-site";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File importsBaseDir;

    @Before
    public void createImportsBaseDir() throws Exception {
        importsBaseDir = tmp.newFolder("imports");
    }

    /**
     * Creates {@code <imports>/staged-tree/export.properties} so that everything downstream of the
     * two guards succeeds. Without it {@code readExportProperties} would return {@code null} and
     * the mutation would answer {@code false} for a reason unrelated to authorization — which
     * would make the refusal tests pass for the wrong reason.
     */
    private void stageAValidImportTree() throws Exception {
        File tree = new File(importsBaseDir, IMPORT_PATH);
        assertThat(tree.mkdirs()).isTrue();
        Properties exportProperties = new Properties();
        exportProperties.setProperty("JahiaRelease", "8.2");
        try (FileOutputStream out = new FileOutputStream(new File(tree, "export.properties"))) {
            exportProperties.store(out, "staged by WebsitesAdminMutationImportGateTest");
        }
    }

    /** Built before any {@code mockStatic} scope opens — see {@code sessionFactoryWithAdmin}. */
    private SettingsBean settings() {
        SettingsBean settings = mock(SettingsBean.class);
        when(settings.getJahiaImportsDiskPath()).thenReturn(importsBaseDir.getAbsolutePath());
        return settings;
    }

    /** The root node answers {@code admin} as requested; this is what the gate consults. */
    private static JCRSessionFactory sessionFactoryWithAdmin(boolean isAdmin) throws RepositoryException {
        JCRNodeWrapper root = mock(JCRNodeWrapper.class);
        when(root.hasPermission("admin")).thenReturn(isAdmin);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode("/")).thenReturn(root);
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        when(factory.getCurrentUserSession()).thenReturn(session);
        return factory;
    }

    /** A session factory that cannot answer the permission question at all. */
    private static JCRSessionFactory sessionFactoryThrowing(RepositoryException failure) throws RepositoryException {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode("/")).thenThrow(failure);
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        when(factory.getCurrentUserSession()).thenReturn(session);
        return factory;
    }

    /** A registry whose exporter lookup throws {@link #SENTINEL} the moment {@code runImport} starts. */
    private static ServicesRegistry registryTrippingTheSentinel() {
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getImportExportService()).thenThrow(new IllegalStateException(SENTINEL));
        return registry;
    }

    // -------------------------------------------------------------------------
    // C1 — the server-administrator gate
    // -------------------------------------------------------------------------

    /**
     * SEC-136 regression lock. A caller holding {@code websitesAdmin} at the root — enough to pass
     * the GraphQL annotation and reach this method — but not {@code admin} must import nothing.
     */
    @Test
    public void importWebsite_refusesANonAdministrator_andReachesNoWork() throws Exception {
        // Arrange — a perfectly valid import tree, so only the gate can be the reason for a refusal
        stageAValidImportTree();
        SettingsBean settings = settings();
        ServicesRegistry registry = registryTrippingTheSentinel();
        JCRSessionFactory sessionFactory = sessionFactoryWithAdmin(false);

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(SettingsBean.class, null)).thenReturn(settings);
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Boolean result = new WebsitesAdminMutation().importWebsite(IMPORT_PATH, SITE_KEY);

            // Assert — the never() calls are the load-bearing half. `false` on its own is also the
            // answer for a rejected path or an unreadable export.properties.
            assertThat(result).isFalse();
            bundleUtils.verify(() -> BundleUtils.getOsgiService(SettingsBean.class, null), never());
            registryStatic.verify(ServicesRegistry::getInstance, never());
        }
    }

    /**
     * M6 — fail closed. {@code callerIsServerAdministrator()} catches {@link RepositoryException}
     * and answers {@code false}; the equivalent path in {@code callerMayActOnSite} is already
     * pinned by {@code WebsitesAdminMutationDeleteSiteTest}, and this is the same contract for the
     * far more dangerous mutation.
     */
    @Test
    public void importWebsite_failsClosed_whenTheAdministratorCheckErrors() throws Exception {
        // Arrange
        stageAValidImportTree();
        SettingsBean settings = settings();
        ServicesRegistry registry = registryTrippingTheSentinel();
        JCRSessionFactory sessionFactory = sessionFactoryThrowing(new RepositoryException("jcr down"));

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(SettingsBean.class, null)).thenReturn(settings);
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Boolean result = new WebsitesAdminMutation().importWebsite(IMPORT_PATH, SITE_KEY);

            // Assert — an unanswerable permission question denies rather than defaulting open
            assertThat(result).isFalse();
            bundleUtils.verify(() -> BundleUtils.getOsgiService(SettingsBean.class, null), never());
            registryStatic.verify(ServicesRegistry::getInstance, never());
        }
    }

    /**
     * The other half of the gate: it must not be an always-deny. A server administrator gets all
     * the way into {@code runImport}, which is where {@link #SENTINEL} fires.
     *
     * <p>Without this test the whole class would still pass if {@code importWebsite} were changed
     * to {@code return Boolean.FALSE;}.
     */
    @Test
    public void importWebsite_admitsAServerAdministrator_andProceedsToTheImport() throws Exception {
        // Arrange
        stageAValidImportTree();
        SettingsBean settings = settings();
        ServicesRegistry registry = registryTrippingTheSentinel();
        JCRSessionFactory sessionFactory = sessionFactoryWithAdmin(true);

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(SettingsBean.class, null)).thenReturn(settings);
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act + Assert — reaching runImport is the proof the gate opened
            assertThatThrownBy(() -> new WebsitesAdminMutation().importWebsite(IMPORT_PATH, SITE_KEY))
                    .as("an administrator must reach the import; a gate that always denies would "
                            + "pass every other test in this class")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(SENTINEL);

            bundleUtils.verify(() -> BundleUtils.getOsgiService(SettingsBean.class, null), times(1));
        }
    }

    // -------------------------------------------------------------------------
    // C2 — the traversal guard on the untrusted siteKey
    // -------------------------------------------------------------------------

    /**
     * C2. {@code resolveImportRoot} validates {@code siteKey} with a second
     * {@code PathSecurity.resolveContained(resolved, siteKey)} call whose <b>return value is
     * discarded</b>: it is pure side-effect validation. Any "remove unused result" cleanup, or an
     * IDE quick-fix, silently deletes traversal validation on untrusted GraphQL input — and every
     * other test in the suite stays green, because {@code siteKey} is only used later as a
     * directory name and a descriptor field.
     *
     * <p>This test is the pin. The import tree is deliberately complete and the caller is
     * deliberately a server administrator, so the <em>only</em> thing that can stop the run is the
     * siteKey check. If it is removed, {@code runImport} is entered and the sentinel fires instead
     * of the expected {@code false}.
     */
    @Test
    public void importWebsite_rejectsATraversingSiteKey_andImportsNothing() throws Exception {
        // Arrange — administrator, valid importPath, valid export.properties; only siteKey is hostile
        stageAValidImportTree();
        SettingsBean settings = settings();
        ServicesRegistry registry = registryTrippingTheSentinel();
        JCRSessionFactory sessionFactory = sessionFactoryWithAdmin(true);

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(SettingsBean.class, null)).thenReturn(settings);
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Boolean result = new WebsitesAdminMutation().importWebsite(IMPORT_PATH, "../../etc");

            // Assert
            assertThat(result)
                    .as("a siteKey escaping the import root must be refused; the discarded-return "
                            + "PathSecurity.resolveContained(resolved, siteKey) call in "
                            + "resolveImportRoot is LOAD-BEARING validation, not dead code")
                    .isFalse();
            registryStatic.verify(ServicesRegistry::getInstance, never());
        }
    }

    /** The first {@code resolveContained} call — the {@code importPath} half of the same guard. */
    @Test
    public void importWebsite_rejectsATraversingImportPath_andImportsNothing() throws Exception {
        // Arrange
        stageAValidImportTree();
        SettingsBean settings = settings();
        ServicesRegistry registry = registryTrippingTheSentinel();
        JCRSessionFactory sessionFactory = sessionFactoryWithAdmin(true);

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(SettingsBean.class, null)).thenReturn(settings);
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Boolean result = new WebsitesAdminMutation().importWebsite("../../etc", SITE_KEY);

            // Assert
            assertThat(result).isFalse();
            registryStatic.verify(ServicesRegistry::getInstance, never());
        }
    }

    /**
     * The descriptor file is what tells the importer which Jahia release produced the tree; an
     * import directory without it is refused before anything is imported.
     */
    @Test
    public void importWebsite_returnsFalse_andImportsNothing_whenExportPropertiesIsMissing() throws Exception {
        // Arrange — administrator and a resolvable path, but no export.properties on disk
        assertThat(new File(importsBaseDir, IMPORT_PATH).mkdirs()).isTrue();
        SettingsBean settings = settings();
        ServicesRegistry registry = registryTrippingTheSentinel();
        JCRSessionFactory sessionFactory = sessionFactoryWithAdmin(true);

        try (MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            bundleUtils.when(() -> BundleUtils.getOsgiService(SettingsBean.class, null)).thenReturn(settings);
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Boolean result = new WebsitesAdminMutation().importWebsite(IMPORT_PATH, SITE_KEY);

            // Assert
            assertThat(result).isFalse();
            registryStatic.verify(ServicesRegistry::getInstance, never());
        }
    }
}
