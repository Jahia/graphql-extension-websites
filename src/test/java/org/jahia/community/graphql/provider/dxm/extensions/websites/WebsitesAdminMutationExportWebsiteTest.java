package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.services.importexport.ImportExportService;
import org.junit.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization test for the single-site export params of
 * {@link WebsitesAdminMutation#exportWebsite} (D6).
 *
 * <p>A "single-site" export actually carries users, roles and ACLs: the params map sets
 * {@code INCLUDE_USERS=true}, {@code INCLUDE_ROLES=true}, {@code VIEW_ACL=true} and
 * {@code INCLUDE_LIVE_EXPORT=!onlyStaging}. This is an undocumented information-exposure
 * nuance (bounded by the caller's own read rights — no escalation). The params construction
 * was extracted into {@link WebsitesAdminMutation#buildSingleSiteExportParams} so these flags
 * can be pinned without a Jahia container (the real export delegates to the concrete
 * {@code ImportExportBaseService}, which cannot be initialized outside a running Jahia).
 */
public class WebsitesAdminMutationExportWebsiteTest {

    @Test
    public void buildSingleSiteExportParams_includesUsersRolesAndAcl() {
        Map<String, Object> params = WebsitesAdminMutation.buildSingleSiteExportParams(
                "/var/jahia/exports/my-site", "/etc/jahia/cleanup.xsl", false);

        assertThat(params.get(ImportExportService.INCLUDE_USERS)).isEqualTo(true);
        assertThat(params.get(ImportExportService.INCLUDE_ROLES)).isEqualTo(true);
        assertThat(params.get(ImportExportService.VIEW_ACL)).isEqualTo(true);
        assertThat(params.get(ImportExportService.SERVER_DIRECTORY)).isEqualTo("/var/jahia/exports/my-site");
        assertThat(params.get(ImportExportService.XSL_PATH)).isEqualTo("/etc/jahia/cleanup.xsl");
    }

    @Test
    public void buildSingleSiteExportParams_liveExportFollowsOnlyStagingFlag() {
        // onlyStaging=false → live content included
        Map<String, Object> full = WebsitesAdminMutation.buildSingleSiteExportParams("d", "x", false);
        assertThat(full.get(ImportExportService.INCLUDE_LIVE_EXPORT)).isEqualTo(true);

        // onlyStaging=true → live content excluded
        Map<String, Object> stagingOnly = WebsitesAdminMutation.buildSingleSiteExportParams("d", "x", true);
        assertThat(stagingOnly.get(ImportExportService.INCLUDE_LIVE_EXPORT)).isEqualTo(false);
    }
}
