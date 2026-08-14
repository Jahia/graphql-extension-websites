package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.junit.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization tests for {@link ImportInfo#asMap()}, the payload handed to
 * {@code importSiteZip} during {@link WebsitesAdminMutation#importWebsite}.
 *
 * <p><b>These tests document current behaviour; they do not endorse it.</b>
 * {@code asMap()} wraps its <em>entire</em> body in {@code if (siteProperties != null)},
 * including the explicit {@code map.put("sitekey", ...)} / {@code "sitetitle"} / {@code
 * "siteservername"} / {@code "siteservernamealiases"} / {@code "templates"} entries, none of
 * which actually depend on {@code siteProperties}. When {@code siteProperties} is null the
 * method therefore returns an <em>empty</em> map and silently drops the site identity.
 *
 * <p>That matters because {@code importWebsite} never calls {@code setSiteProperties(...)} on any
 * of the three {@link ImportInfo} instances it builds — so in production {@code asMap()} always
 * returns an empty map.
 *
 * <p><b>Reachability (traced against Jahia 8.2 sources).</b> The map is only ever handed to
 * {@code ImportExportBaseService.importSiteZip(file, site, infos, ...)}, which forwards it to
 * {@code importAdditionalFilesIfPresentInArchiveOrPerformLegacyImportIfNeeded(...)}. That method
 * reads {@code infos} in exactly one place — inside {@code if (legacyImport)}, calling
 * {@code performLegacyImport(...)} — and {@code legacyImport} is only true when the archive
 * contains Jahia 5.x/6.1 descriptors (entries starting with {@code export_}). For a modern 8.x
 * export, which is the only thing this module's own {@code exportWebsite} produces, the map is
 * never read. (The {@code infos.get("sitekey")} reads elsewhere in that class belong to
 * {@code performSiteImport(..., Properties infos)}, a different entry point this module does not
 * call.) The site itself is unaffected either way: {@code importSite()} builds its
 * {@code SiteCreationInfo} by reading {@code site.properties} directly, not from {@code asMap()}.
 *
 * <p>So the defect is <b>real but currently inert</b>: it would only bite when importing a legacy
 * 5.x/6.x archive, where {@code performLegacyImport} would receive no site identity. It is
 * therefore pinned rather than corrected — changing it would alter legacy-import behaviour that no
 * test in this repository can exercise, for no gain on any supported path. The end-to-end round
 * trip in {@code 03-graphqlExtensionWebsites-Import.cy.ts} confirms modern imports succeed with
 * the empty map. If this is ever fixed, {@link #asMap_isEmptyWhenSitePropertiesAreUnset()} is the
 * test that should fail and be updated.
 */
public class ImportInfoTest {

    @Test
    public void asMap_isEmptyWhenSitePropertiesAreUnset() {
        // Arrange — exactly how importWebsite() populates an ImportInfo: no siteProperties
        ImportInfo info = new ImportInfo();
        info.setSiteKey("mysite");
        info.setSiteTitle("My Site");
        info.setImportFileName("mysite");
        info.setType("site");
        info.setSelected(true);

        // Act
        Map<Object, Object> map = info.asMap();

        // Assert — the site identity is dropped, not merely the (absent) properties
        assertThat(map)
                .as("asMap() guards every entry on siteProperties, so an unset Properties yields nothing")
                .isEmpty();
    }

    @Test
    public void asMap_includesSiteIdentityWhenSitePropertiesArePresent() {
        // Arrange
        Properties siteProperties = new Properties();
        siteProperties.setProperty("defaultLanguage", "en");
        ImportInfo info = new ImportInfo();
        info.setSiteProperties(siteProperties);
        info.setSiteKey("mysite");
        info.setSiteTitle("My Site");
        info.setSiteServername("www.example.com");
        info.setTemplates("templateSet");

        // Act
        Map<Object, Object> map = info.asMap();

        // Assert
        assertThat(map).containsEntry("defaultLanguage", "en");
        assertThat(map).containsEntry("sitekey", "mysite");
        assertThat(map).containsEntry("sitetitle", "My Site");
        assertThat(map).containsEntry("siteservername", "www.example.com");
        assertThat(map).containsEntry("templates", "templateSet");
    }

    @Test
    public void asMap_overlaysExplicitEntriesOnTopOfSiteProperties() {
        // Arrange — a stale sitekey in the properties file must lose to the explicit field
        Properties siteProperties = new Properties();
        siteProperties.setProperty("sitekey", "stale-key-from-properties-file");
        ImportInfo info = new ImportInfo();
        info.setSiteProperties(siteProperties);
        info.setSiteKey("authoritative-key");

        // Act
        Map<Object, Object> map = info.asMap();

        // Assert
        assertThat(map).containsEntry("sitekey", "authoritative-key");
    }

    @Test
    public void asMap_returnsADetachedCopyPerCall() {
        // Arrange
        Properties siteProperties = new Properties();
        siteProperties.setProperty("defaultLanguage", "en");
        ImportInfo info = new ImportInfo();
        info.setSiteProperties(siteProperties);
        info.setSiteKey("mysite");

        // Act — mutating the returned map must not corrupt the next caller's view
        Map<Object, Object> first = info.asMap();
        first.put("injected", "value");
        Map<Object, Object> second = info.asMap();

        // Assert
        assertThat(second).doesNotContainKey("injected");
        assertThat(second).containsEntry("sitekey", "mysite");
    }
}
