package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.services.importexport.ImportExportBaseService;
import org.jahia.services.sites.JahiaSitesService;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the descriptor wiring extracted from {@link WebsitesAdminMutation#importWebsite}.
 *
 * <p>{@code importWebsite} used to build these three {@link ImportInfo} objects inline as ~26 lines
 * of near-identical setter boilerplate. Extracting it to
 * {@link WebsitesAdminMutation#buildImportDescriptors} is only safe if the wiring is provably
 * unchanged — several fields are easy to get subtly wrong and none of them fail loudly:
 *
 * <ul>
 *   <li>the roles entry is scoped to the <em>system site</em>, not the imported site;</li>
 *   <li>the users entry deliberately carries a {@code null} site key, because users are
 *       repository-wide — a "helpful" fix to the imported site key would silently misplace them;</li>
 *   <li>the site entry's directory <em>and</em> import file name are both the raw site key, which
 *       is what {@code importSite} later resolves {@code site.properties} against;</li>
 *   <li>order matters: roles and users must precede the site import.</li>
 * </ul>
 */
public class WebsitesAdminMutationImportDescriptorsTest {

    private static final String SITE_KEY = "cypress-roundtrip-site";
    private static final Path IMPORT_ROOT = Paths.get("/var/jahia/imports/cypress-import");

    private static Properties exportProperties() {
        Properties properties = new Properties();
        properties.setProperty("JahiaRelease", "8.2");
        return properties;
    }

    private static List<ImportInfo> descriptors() {
        return WebsitesAdminMutation.buildImportDescriptors(IMPORT_ROOT, SITE_KEY, exportProperties());
    }

    @Test
    public void buildImportDescriptors_returnsRolesThenUsersThenSite() {
        // Act
        List<ImportInfo> descriptors = descriptors();

        // Assert
        assertThat(descriptors).hasSize(3);
        assertThat(descriptors.get(0).getImportFileName()).isEqualTo(ImportExportBaseService.ROLES_ZIP);
        assertThat(descriptors.get(1).getImportFileName()).isEqualTo(ImportExportBaseService.USERS_ZIP);
        assertThat(descriptors.get(2).getImportFileName()).isEqualTo(SITE_KEY);
    }

    @Test
    public void buildImportDescriptors_scopesRolesToTheSystemSite() {
        ImportInfo roles = descriptors().get(0);

        assertThat(roles.getSiteKey()).isEqualTo(JahiaSitesService.SYSTEM_SITE_KEY);
        assertThat(roles.getSiteKey()).isEqualTo("systemsite");
        assertThat(roles.getType()).isEqualTo("files");
        assertThat(roles.getImportFile()).isEqualTo(IMPORT_ROOT.resolve("roles").toFile());
    }

    @Test
    public void buildImportDescriptors_leavesTheUsersSiteKeyNull() {
        ImportInfo users = descriptors().get(1);

        // Users are repository-wide. Scoping them to a site would misplace them.
        assertThat(users.getSiteKey()).isNull();
        assertThat(users.getType()).isEqualTo("files");
        assertThat(users.getImportFile()).isEqualTo(IMPORT_ROOT.resolve("users").toFile());
    }

    @Test
    public void buildImportDescriptors_pointsTheSiteEntryAtTheSiteKeyDirectory() {
        ImportInfo site = descriptors().get(2);

        assertThat(site.getSiteKey()).isEqualTo(SITE_KEY);
        assertThat(site.getType()).isEqualTo("site");
        assertThat(site.getImportFile()).isEqualTo(IMPORT_ROOT.resolve(SITE_KEY).toFile());
    }

    @Test
    public void buildImportDescriptors_marksEveryEntrySelectedAndStampsTheOriginatingRelease() {
        List<ImportInfo> descriptors = descriptors();

        assertThat(descriptors).allSatisfy(info -> {
            assertThat(info.isSelected()).isTrue();
            assertThat(info.getOriginatingJahiaRelease()).isEqualTo("8.2");
        });
    }

    @Test
    public void buildImportDescriptors_toleratesAMissingJahiaReleaseProperty() {
        // An export.properties without JahiaRelease must not blow up here; the version is only
        // consumed later by ImportUpdateService.
        List<ImportInfo> descriptors =
                WebsitesAdminMutation.buildImportDescriptors(IMPORT_ROOT, SITE_KEY, new Properties());

        assertThat(descriptors).hasSize(3);
        assertThat(descriptors).allSatisfy(info -> assertThat(info.getOriginatingJahiaRelease()).isNull());
    }
}
