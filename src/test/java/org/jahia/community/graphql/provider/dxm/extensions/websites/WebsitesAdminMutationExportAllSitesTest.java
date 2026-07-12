package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.services.importexport.ImportExportService;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guards for the SEC-136 fix on the bulk export
 * ({@link WebsitesAdminMutation#exportAllSites()} / {@code exportAllSites(Path)}).
 *
 * <p><b>D2 — no root escalation.</b> SEC-136 removed a root-user escalation: the remediated
 * code exports under the caller's own session over {@code JahiaSitesService.getSitesNodeList()}
 * and never impersonates root. Because the export delegates to the concrete
 * {@code ImportExportBaseService} — whose static initializer requires a running Jahia container
 * (it calls {@code SettingsBean.getInstance()} at class-load, NPE'ing in a unit test) — a runtime
 * mock of the export call is infeasible. Instead this asserts, container-free, that the compiled
 * {@link WebsitesAdminMutation} class references the caller-scoped {@code getSitesNodeList} API and
 * references NO root-escalation / impersonation API. If any escalation call is reintroduced
 * anywhere in the class, this fails loudly.
 *
 * <p><b>D5 — dead enum value removed (Stage 7).</b> The unreachable {@code ExportAllSitesResults.FAILURE}
 * constant was removed; {@code exportAllSites()} only ever returns {@code SUCCESS} or
 * {@code AWS_S3_BUCKET_NOT_CONFIGURED} (it throws {@link org.jahia.modules.graphql.provider.dxm.DataFetchingException}
 * on error). {@link #exportAllSitesResults_declaresReachableValuesOnly()} pins that closed set.
 */
public class WebsitesAdminMutationExportAllSitesTest {

    /** Reads the compiled bytecode of {@link WebsitesAdminMutation} (constant pool + code). */
    private static String classBytes() throws IOException {
        try (InputStream in = WebsitesAdminMutation.class.getResourceAsStream("WebsitesAdminMutation.class")) {
            assertThat(in).as("compiled WebsitesAdminMutation.class on classpath").isNotNull();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            // Method/field names live in the constant pool as (modified) UTF-8 — ASCII tokens
            // appear verbatim, so an ISO-8859-1 view is a safe superset for substring search.
            return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    public void exportAllSites_referencesCallerScopedSiteListApi() throws IOException {
        assertThat(classBytes())
                .as("bulk export must still enumerate sites via the caller-scoped getSitesNodeList()")
                .contains("getSitesNodeList");
    }

    @Test
    public void exportAllSites_containsNoRootEscalationOrImpersonationApi() throws IOException {
        String bytes = classBytes();
        // None of these escalation APIs currently appears anywhere in the class. Reintroducing a
        // root/system session or an impersonation call to widen the export beyond the caller's
        // rights (the pre-SEC-136 vulnerability) would put one of these tokens back and fail here.
        assertThat(bytes).as("no impersonation").doesNotContain("impersonate");
        assertThat(bytes).as("no root user session").doesNotContain("getRootUserSession");
        assertThat(bytes).as("no explicit system session lookup").doesNotContain("getSystemSession");
        assertThat(bytes).as("no current-user override").doesNotContain("setCurrentUser");
    }

    @Test
    public void buildAllSitesExportParams_includesUsersRolesAndAcl() {
        Map<String, Object> params = WebsitesAdminMutation.buildAllSitesExportParams("/tmp/cleanup.xsl");

        assertThat(params.get(ImportExportService.INCLUDE_USERS)).isEqualTo(true);
        assertThat(params.get(ImportExportService.INCLUDE_ROLES)).isEqualTo(true);
        assertThat(params.get(ImportExportService.VIEW_ACL)).isEqualTo(true);
        assertThat(params.get(ImportExportService.INCLUDE_LIVE_EXPORT)).isEqualTo(true);
        assertThat(params.get(ImportExportService.XSL_PATH)).isEqualTo("/tmp/cleanup.xsl");
    }

    // -------------------------------------------------------------------------
    // D5 — the dead ExportAllSitesResults.FAILURE constant was removed in Stage 7.
    // This pins the enum to exactly the two reachable values the method returns.
    // -------------------------------------------------------------------------

    @Test
    public void exportAllSitesResults_declaresReachableValuesOnly() {
        assertThat(WebsitesAdminMutation.ExportAllSitesResults.values())
                .containsExactlyInAnyOrder(WebsitesAdminMutation.ExportAllSitesResults.SUCCESS,
                        WebsitesAdminMutation.ExportAllSitesResults.AWS_S3_BUCKET_NOT_CONFIGURED);
    }
}
