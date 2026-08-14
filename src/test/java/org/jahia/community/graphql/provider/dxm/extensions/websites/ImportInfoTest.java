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
 * returns an empty map. Whether {@code importSiteZip} needs those keys is a Jahia-behaviour
 * question that cannot be settled without an integration test against a running container, so the
 * behaviour is pinned rather than changed. If it is fixed later,
 * {@link #asMap_isEmptyWhenSitePropertiesAreUnset()} is the test that should fail and be updated.
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
