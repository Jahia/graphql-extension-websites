package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.api.settings.SettingsBean;
import org.jahia.exceptions.JahiaException;
import org.jahia.osgi.BundleUtils;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.sites.JahiaSite;
import org.jahia.services.sites.JahiaSitesService;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jahia.services.content.JCRTemplate;
import org.mockito.MockedStatic;

import javax.jcr.RepositoryException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WebsitesAdminMutation#exportWebsite} deletes whatever already sits at the resolved export
 * path before exporting (Jahia refuses a non-empty server export directory). That deletion is the
 * one destructive side effect of an otherwise read-only mutation, so <em>what may reach it</em> is
 * a security property in its own right. This class pins the two controls around it.
 *
 * <h3>H1 — the symbolic-link refusal</h3>
 *
 * <p>A symlink at the export location would redirect the delete to the link's target. Both branches
 * of that check were uncovered. Note {@link PathSecurity} does <em>not</em> already cover this:
 * its symlink-aware layer canonicalizes the nearest <em>existing</em> ancestor, so a link whose
 * target is inside the exports directory passes containment cleanly — and the deletion would then
 * follow the link and empty the target. {@link #exportWebsite_refusesToDeleteASymlinkedExportPath()}
 * plants exactly that and asserts the target's contents survive.
 *
 * <h3>H2 — cleanup must stay below the authorization gate</h3>
 *
 * <p>{@code deleteExportArtifact(resolvedExportPath, "exportWebsite")} currently sits after the
 * per-site permission check. Hoisting it above — an easy, innocuous-looking reordering, e.g. while
 * grouping the path handling together — would let any root-level {@code websitesAdmin} holder
 * delete arbitrary directories under {@code exports/} for sites they hold no rights on. All four
 * pre-existing scope tests stay green under that edit because none of them puts a file on disk.
 * {@link #exportWebsite_deletesNothing_whenTheCallerIsNotAuthorizedOnTheSite()} does.
 *
 * <h3>Why a sentinel exception marks "the export was reached"</h3>
 *
 * <p>The real export delegates to {@code ImportExportBaseService}, which cannot be
 * class-initialized outside a running Jahia. So the allowed path stubs
 * {@code ServicesRegistry.getImportExportService()} to throw {@link #SENTINEL}: the run stops at
 * exactly the point where the mutation would hand over to Jahia, after the cleanup has happened.
 */
public class WebsitesAdminMutationExportDeletionGuardTest {

    /** Marks the point where the mutation hands over to the real Jahia exporter. */
    private static final String SENTINEL = "REACHED_EXPORTER";

    private static final String SITE_KEY = "cypress-test-website";
    private static final String SITE_PATH = "/sites/" + SITE_KEY;
    private static final String EXPORT_PERMISSION = "websitesExport";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** Stands in for {@code jahiaVarDiskPath}; the mutation appends {@code exports}. */
    private File varDiskPath;
    private Path exportsDir;

    @Before
    public void createExportsDirectory() throws Exception {
        varDiskPath = tmp.newFolder("var");
        exportsDir = new File(varDiskPath, "exports").toPath();
        Files.createDirectories(exportsDir);
    }

    private SettingsBean settings() {
        SettingsBean settings = mock(SettingsBean.class);
        when(settings.getJahiaVarDiskPath()).thenReturn(varDiskPath.getAbsolutePath());
        when(settings.getJahiaEtcDiskPath()).thenReturn(new File(varDiskPath, "etc").getAbsolutePath());
        return settings;
    }

    /**
     * A {@link JCRSiteNode}, not a bare {@link JahiaSite}: {@code exportWebsite} casts the site it
     * resolved to {@code JCRSiteNode} when building the export list, so a plain {@code JahiaSite}
     * mock would fail with {@code ClassCastException} on the allowed path and never reach the
     * exporter.
     */
    private static JCRSiteNode site() {
        JCRSiteNode site = mock(JCRSiteNode.class);
        when(site.getJCRLocalPath()).thenReturn(SITE_PATH);
        return site;
    }

    /**
     * A registry that resolves the site and then trips {@link #SENTINEL} when the exporter is
     * requested. Passing {@code null} for the site models "site not found".
     */
    private static ServicesRegistry registry(JCRSiteNode site) throws Exception {
        JahiaSitesService sites = mock(JahiaSitesService.class);
        when(sites.getSiteByKey(SITE_KEY)).thenReturn(site);
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sites);
        when(registry.getImportExportService()).thenThrow(new IllegalStateException(SENTINEL));
        return registry;
    }

    private static JCRSessionFactory sessionFactoryGranting(boolean granted) throws RepositoryException {
        JCRNodeWrapper siteNode = mock(JCRNodeWrapper.class);
        when(siteNode.hasPermission(EXPORT_PERMISSION)).thenReturn(granted);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode(SITE_PATH)).thenReturn(siteNode);
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        when(factory.getCurrentUserSession()).thenReturn(session);
        return factory;
    }

    /** Runs the mutation with all three Jahia singletons stubbed. */
    private Boolean export(String exportPath, JCRSessionFactory sessionFactory, ServicesRegistry registry) {
        SettingsBean settings = settings();
        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            bundleUtils.when(() -> BundleUtils.getOsgiService(SettingsBean.class, null)).thenReturn(settings);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            return new WebsitesAdminMutation().exportWebsite(SITE_KEY, exportPath, false);
        }
    }

    /** Creates {@code <exports>/<name>/keep.txt} and returns the marker file. */
    private Path directoryContainingAMarker(String name) throws IOException {
        Path dir = exportsDir.resolve(name);
        Files.createDirectories(dir);
        Path marker = dir.resolve("keep.txt");
        Files.write(marker, "must survive".getBytes("UTF-8"));
        return marker;
    }

    // -------------------------------------------------------------------------
    // H1 — symbolic-link refusal (both branches)
    // -------------------------------------------------------------------------

    /**
     * A symlink inside {@code exports/} pointing at another directory inside {@code exports/}
     * satisfies {@link PathSecurity} containment, so this check is the only thing left between the
     * link and {@code FileUtils.deleteQuietly}, which follows it: {@code File.isDirectory()}
     * resolves the link, {@code cleanDirectory} then empties the <em>target</em>.
     *
     * <p>The assertion is therefore not merely {@code false} — it is that the target's contents are
     * still on disk.
     */
    @Test
    public void exportWebsite_refusesToDeleteASymlinkedExportPath() throws Exception {
        // Arrange — exports/real-target/keep.txt, and exports/my-export -> exports/real-target
        Path marker = directoryContainingAMarker("real-target");
        Path link = exportsDir.resolve("my-export");
        try {
            Files.createSymbolicLink(link, exportsDir.resolve("real-target"));
        } catch (UnsupportedOperationException | IOException ex) {
            Assume.assumeNoException("Skipping: filesystem does not support symlinks", ex);
        }
        ServicesRegistry registry = registry(site());

        // Act — the caller IS authorized, so only the symlink check can refuse
        Boolean result = export("my-export", sessionFactoryGranting(true), registry);

        // Assert
        assertThat(result).isFalse();
        assertThat(marker)
                .as("deleting through a symlink would empty its target — the refusal at "
                        + "exportWebsite is an anti-arbitrary-deletion control, not a formality")
                .exists();
        assertThat(Files.isSymbolicLink(link)).as("the link itself must be left alone").isTrue();
        verify(registry, never()).getImportExportService();
    }

    /**
     * The other branch: a plain directory is <em>not</em> refused. It is deleted (so a repeated
     * export to the same path is idempotent rather than failing Jahia's "server directory must be
     * empty" check) and the run proceeds to the exporter.
     *
     * <p>This is what stops the symlink check from being satisfiable by refusing everything.
     */
    @Test
    public void exportWebsite_deletesAPreviousPlainExportAndProceeds() throws Exception {
        // Arrange
        Path marker = directoryContainingAMarker("previous-export");
        ServicesRegistry registry = registry(site());

        // Act + Assert — the sentinel proves the exporter was reached
        assertThatThrownBy(() -> export("previous-export", sessionFactoryGranting(true), registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(SENTINEL);

        assertThat(marker).doesNotExist();
        assertThat(exportsDir.resolve("previous-export"))
                .as("a stale export directory must be cleared, or Jahia rejects the server directory")
                .doesNotExist();
    }

    /**
     * {@code exportWebsite} must run the export under the <em>caller's own</em> session, never a
     * system session — the §4.3 confidentiality property depends entirely on it.
     *
     * <p>Without this test that property has no lock. {@code exportAllSites} has one
     * ({@code WebsitesAdminMutationExportAllSitesFailureTest} verifies {@code JCRTemplate} is
     * never consulted); {@code exportWebsite} did not. Wrapping the {@code exportSites} call in
     * {@code JCRTemplate.getInstance().doExecuteWithSystemSession(...)} is a plausible edit —
     * {@link WebsitesAdminMutation#createSiteByKey} does exactly that, and it is what someone
     * would reach for on an "empty archive" report from a site administrator — and it would leave
     * every other test in this module green while turning a delegated site administrator's export
     * into a full instance dump of users, roles and ACLs ({@code INCLUDE_USERS},
     * {@code INCLUDE_ROLES} and {@code VIEW_ACL} are all set on the export params). That is the
     * SEC-136 exfiltration class returning through a different door.
     *
     * <p>Driven through the same authorized happy path as the test above, so the assertion is made
     * at the moment the exporter is actually reached.
     */
    @Test
    public void exportWebsite_neverRunsTheExportUnderASystemSession() throws Exception {
        // Arrange
        directoryContainingAMarker("session-scope-export");
        ServicesRegistry registry = registry(site());

        try (MockedStatic<JCRTemplate> jcrTemplateStatic = mockStatic(JCRTemplate.class)) {
            // Act — sentinel confirms we got as far as the exporter hand-off
            assertThatThrownBy(() -> export("session-scope-export", sessionFactoryGranting(true), registry))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(SENTINEL);

            // Assert
            jcrTemplateStatic.verify(JCRTemplate::getInstance, never());
        }
    }

    // -------------------------------------------------------------------------
    // H2 — cleanup stays below the authorization gate
    // -------------------------------------------------------------------------

    /**
     * H2. An unauthorized caller must not be able to use {@code exportPath} as a delete primitive.
     * The directory named here belongs to nobody in particular — the point is that a caller who
     * fails the per-site {@code websitesExport} check reaches no destructive code at all.
     *
     * <p>This test fails if {@code deleteExportArtifact} is ever hoisted above the
     * {@code callerMayActOnSite} call.
     */
    @Test
    public void exportWebsite_deletesNothing_whenTheCallerIsNotAuthorizedOnTheSite() throws Exception {
        // Arrange
        Path marker = directoryContainingAMarker("someone-elses-export");
        ServicesRegistry registry = registry(site());

        // Act
        Boolean result = export("someone-elses-export", sessionFactoryGranting(false), registry);

        // Assert
        assertThat(result).isFalse();
        assertThat(marker)
                .as("cleanup must stay BELOW the permission gate; above it, a root-level "
                        + "websitesAdmin holder could delete any directory under exports/ for a "
                        + "site they hold no rights on")
                .exists();
        assertThat(exportsDir.resolve("someone-elses-export")).exists();
        verify(registry, never()).getImportExportService();
    }

    // -------------------------------------------------------------------------
    // M4 / M5 — the two earlier refusals, and their ordering
    // -------------------------------------------------------------------------

    /**
     * M4. A traversing {@code exportPath} is rejected by {@code resolveContainedOrNull} before the
     * site is even looked up, so the mutation leaks nothing about which sites exist and — more to
     * the point — never reaches the deletion.
     */
    @Test
    public void exportWebsite_rejectsATraversingExportPath_beforeLookingUpTheSite() throws Exception {
        // Arrange — a file outside the exports directory that a traversal would target
        Path outside = tmp.newFile("outside-target.txt").toPath();
        ServicesRegistry registry = registry(site());

        // Act
        Boolean result = export("../../" + outside.getFileName(), sessionFactoryGranting(true), registry);

        // Assert
        assertThat(result).isFalse();
        assertThat(outside).exists();
        verify(registry, never()).getJahiaSitesService();
        verify(registry, never()).getImportExportService();
    }

    /**
     * A domain-level failure during the site lookup is reported as {@code false} and, like every
     * other refusal, leaves the export path on disk untouched. This is the
     * {@code catch (JahiaException | …)} arm of {@code exportWebsite}.
     */
    @Test
    public void exportWebsite_returnsFalse_andDeletesNothing_whenTheSiteLookupFails() throws Exception {
        // Arrange
        Path marker = directoryContainingAMarker("doomed-export");
        JahiaSitesService sites = mock(JahiaSitesService.class);
        when(sites.getSiteByKey(SITE_KEY)).thenThrow(new JahiaException("boom", "boom",
                JahiaException.DATA_ERROR, JahiaException.ERROR_SEVERITY));
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sites);

        // Act
        Boolean result = export("doomed-export", sessionFactoryGranting(true), registry);

        // Assert
        assertThat(result).isFalse();
        assertThat(marker).exists();
        verify(registry, never()).getImportExportService();
    }

    /** M5 — an unknown site key is refused before any permission check or deletion. */
    @Test
    public void exportWebsite_returnsFalse_whenTheSiteIsNotFound() throws Exception {
        // Arrange — the path is valid and a directory exists there; the site does not
        Path marker = directoryContainingAMarker("orphan-export");
        ServicesRegistry registry = registry(null);
        JCRSessionFactory sessionFactory = sessionFactoryGranting(true);

        // Act
        Boolean result = export("orphan-export", sessionFactory, registry);

        // Assert
        assertThat(result).isFalse();
        assertThat(marker).exists();
        verify(sessionFactory, never()).getCurrentUserSession();
        verify(registry, never()).getImportExportService();
    }
}
