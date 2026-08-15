package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.exceptions.JahiaException;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRObservationManager;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.importexport.ImportUpdateService;
import org.jahia.services.sites.JahiaSitesService;
import org.jahia.services.sites.SiteCreationInfo;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The site-creation half of {@code importWebsite}: {@code importSite}, which reads the imported
 * tree's {@code site.properties} and turns it into the {@link SiteCreationInfo} handed to
 * {@code JahiaSitesService.addSite}.
 *
 * <p>It is the only part of the import orchestration reachable without a Jahia container — the
 * rest goes through {@code ImportExportBaseService}, which cannot even be <em>mocked</em> outside
 * one (ByteBuddy fails with {@code InternalError: class redefinition failed: invalid class}). It
 * is also the part most worth pinning: eleven properties are copied across by string key, so a
 * transposed pair (title ↔ description, {@code siteservername} ↔ {@code siteservernamealiases})
 * compiles, runs, and produces a subtly wrong site that only a human comparing the imported result
 * against the source would notice.
 *
 * <p>{@code importSite} is private and is reached reflectively; driving it through
 * {@code importWebsite} is impossible for the reason above.
 */
public class WebsitesAdminMutationImportSiteTest {

    private static final String SITE_KEY = "cypress-roundtrip-site";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File importRoot;
    private File siteDirectory;

    @Before
    public void createImportTree() throws Exception {
        importRoot = tmp.newFolder("import-root");
        siteDirectory = new File(importRoot, SITE_KEY);
        assertThat(siteDirectory.mkdirs()).isTrue();
    }

    /** Writes the {@code site.properties} an export produces for a site with two modules. */
    private void writeSiteProperties() throws Exception {
        try (FileWriter writer = new FileWriter(new File(siteDirectory, "site.properties"))) {
            writer.write("sitetitle=My Imported Site\n");
            writer.write("siteservername=www.example.com\n");
            writer.write("siteservernamealiases=alias.example.com\n");
            writer.write("description=Imported by the round trip\n");
            writer.write("templatePackageName=my-template-set\n");
            writer.write("defaultLanguage=fr\n");
            writer.write("installedModules.1=module-a\n");
            writer.write("installedModules.2=module-b\n");
        }
    }

    /** Exactly what {@code buildImportDescriptors} produces for the site entry. */
    private ImportInfo descriptor() {
        ImportInfo info = new ImportInfo();
        info.setImportFile(siteDirectory);
        info.setSiteKey(SITE_KEY);
        info.setImportFileName(SITE_KEY);
        info.setType("site");
        info.setSelected(true);
        info.setOriginatingJahiaRelease("8.2");
        return info;
    }

    /**
     * Invokes the private {@code importSite}, with {@link ImportUpdateService} and
     * {@link JCRObservationManager} static-mocked. The observation manager is stubbed to actually
     * run the callback it is given — mocking it into a no-op would skip the whole body under test.
     */
    @SuppressWarnings("unchecked")
    private boolean importSite(JahiaSitesService sitesService, File updatedImportFile) throws Exception {
        ImportUpdateService updateService = mock(ImportUpdateService.class);
        when(updateService.updateImport(ArgumentMatchers.<File>any(), anyString(), anyString(), any()))
                .thenReturn(updatedImportFile);

        Method method = WebsitesAdminMutation.class.getDeclaredMethod(
                "importSite", JahiaSitesService.class, ImportInfo.class, String.class);
        method.setAccessible(true);

        try (MockedStatic<ImportUpdateService> updateStatic = mockStatic(ImportUpdateService.class);
             MockedStatic<JCRObservationManager> observationStatic = mockStatic(JCRObservationManager.class)) {
            updateStatic.when(ImportUpdateService::getInstance).thenReturn(updateService);
            // ArgumentMatchers.<T>any() rather than any(Type.class): the production call passes a
            // null session (the import supplies its own), and any(Type.class) does not match null.
            observationStatic.when(() -> JCRObservationManager.doWithOperationType(
                            ArgumentMatchers.<JCRSessionWrapper>any(), anyInt(),
                            ArgumentMatchers.<JCRCallback<Object>>any()))
                    .thenAnswer(invocation -> ((JCRCallback<Object>) invocation.getArgument(2)).doInJCR(null));

            return (Boolean) method.invoke(null, sitesService, descriptor(), importRoot.getAbsolutePath());
        }
    }

    /**
     * Every {@code site.properties} key must land on the matching {@link SiteCreationInfo} field.
     * The {@code installedModules.*} entries are the awkward one: they are enumerated by key
     * prefix and collected into the {@code modulesToDeploy} array, so the site comes back with the
     * modules it was exported with.
     */
    @Test
    public void importSite_buildsTheSiteCreationInfoFromSiteProperties() throws Exception {
        // Arrange
        writeSiteProperties();
        File updatedImportFile = tmp.newFile("updated-import.zip");
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        ArgumentCaptor<SiteCreationInfo> info = ArgumentCaptor.forClass(SiteCreationInfo.class);

        // Act
        boolean successful = importSite(sitesService, updatedImportFile);

        // Assert
        assertThat(successful).isTrue();
        verify(sitesService, times(1)).addSite(info.capture());
        SiteCreationInfo created = info.getValue();
        assertThat(created.getSiteKey()).isEqualTo(SITE_KEY);
        assertThat(created.getTitle()).isEqualTo("My Imported Site");
        assertThat(created.getServerName()).isEqualTo("www.example.com");
        assertThat(created.getServerNameAliases()).containsExactly("alias.example.com");
        assertThat(created.getDescription()).isEqualTo("Imported by the round trip");
        assertThat(created.getTemplateSet()).isEqualTo("my-template-set");
        assertThat(created.getLocale()).isEqualTo("fr");
        assertThat(created.getModulesToDeploy())
                .as("installedModules.* must be collected in file order, or the imported site "
                        + "comes back missing modules it was exported with")
                .containsExactly("module-a", "module-b");
        assertThat(created.getOriginatingJahiaRelease()).isEqualTo("8.2");
        assertThat(created.getFileImportName()).isEqualTo(SITE_KEY);
        assertThat(created.getFirstImport())
                .as("the site content is created from the archive, not empty")
                .isEqualTo("fileImport");
        assertThat(created.getFileImport())
                .as("the archive returned by ImportUpdateService must be the one imported")
                .isNotNull();
        assertThat(created.getFileImport().getFile()).isEqualTo(updatedImportFile);
    }

    /**
     * {@code ImportUpdateService} returns {@code null} when the archive needs no upgrade. That is
     * a normal outcome, not a failure, and must not become a {@code FileSystemResource(null)}.
     */
    @Test
    public void importSite_toleratesAnUnchangedArchive() throws Exception {
        // Arrange
        writeSiteProperties();
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        ArgumentCaptor<SiteCreationInfo> info = ArgumentCaptor.forClass(SiteCreationInfo.class);

        // Act
        boolean successful = importSite(sitesService, null);

        // Assert
        assertThat(successful).isTrue();
        verify(sitesService).addSite(info.capture());
        assertThat(info.getValue().getFileImport()).isNull();
    }

    /** A tree without {@code site.properties} is a failed import, reported as {@code false}. */
    @Test
    public void importSite_returnsFalse_andCreatesNothing_whenSitePropertiesIsMissing() throws Exception {
        // Arrange — the site directory exists but carries no descriptor
        JahiaSitesService sitesService = mock(JahiaSitesService.class);

        // Act
        boolean successful = importSite(sitesService, null);

        // Assert
        assertThat(successful).isFalse();
        verify(sitesService, never()).addSite(any(SiteCreationInfo.class));
    }

    /**
     * A domain failure from {@code addSite} — the template set named in {@code site.properties} is
     * not installed on this instance, say — is wrapped into a {@link javax.jcr.RepositoryException}
     * by the callback and reported as {@code false} rather than escaping to the GraphQL layer.
     */
    @Test
    public void importSite_returnsFalse_whenAddSiteFails() throws Exception {
        // Arrange
        writeSiteProperties();
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        doThrow(new JahiaException("no such template set", "no such template set",
                JahiaException.DATA_ERROR, JahiaException.ERROR_SEVERITY))
                .when(sitesService).addSite(any(SiteCreationInfo.class));

        // Act
        boolean successful = importSite(sitesService, null);

        // Assert
        assertThat(successful).isFalse();
    }
}
