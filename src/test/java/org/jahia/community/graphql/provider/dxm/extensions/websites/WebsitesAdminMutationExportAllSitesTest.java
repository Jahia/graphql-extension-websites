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
 * and never impersonates root. This asserts, container-free, that the compiled
 * {@link WebsitesAdminMutation} class references the caller-scoped {@code getSitesNodeList} API and
 * references NO root-escalation / impersonation API. If any escalation call is reintroduced
 * anywhere in the class, this fails loudly.
 *
 * <p><b>What this scan can and cannot see.</b> It is a constant-pool substring search over the
 * <em>whole class</em>, not over {@code exportAllSites} specifically — hence the method names
 * below deliberately say "the class". That makes it a strong lock on the <em>historical</em>
 * mechanism (a named escalation API leaves a token behind wherever it is used) and a weak one on
 * the escalation route available today: wrapping the export in
 * {@code JCRTemplate.getInstance().doExecuteWithSystemSession(...)} adds no new token at all,
 * because {@code createSiteByKey} and {@code doImportFiles} already use that API legitimately in
 * the same class, so the token is present either way. Banning it here is therefore impossible.
 *
 * <p>That gap is closed from the other side, at runtime, by
 * {@code WebsitesAdminMutationExportAllSitesFailureTest#exportAllSites_runsTheExportWithoutAskingForASystemSession()},
 * which drives the mutation into the export call with {@link org.jahia.services.content.JCRTemplate}
 * static-mocked and fails if the singleton is consulted at all. Keep both: the scan catches a
 * reintroduced escalation API anywhere in the file, the runtime test catches a system-session
 * wrapper around the bulk export.
 *
 * <p><b>D5 — dead enum value removed (Stage 7).</b> The unreachable {@code ExportAllSitesResults.FAILURE}
 * constant was removed, leaving three reachable values: {@code NOT_SERVER_ADMINISTRATOR} (added in
 * SEC-136 §4.3, and the <em>first</em> one the method can return — the administrator gate is checked
 * before the S3 precondition), {@code AWS_S3_BUCKET_NOT_CONFIGURED} and {@code SUCCESS}. Anything
 * unexpected is thrown as {@link org.jahia.modules.graphql.provider.dxm.DataFetchingException}
 * rather than returned. {@link #exportAllSitesResults_declaresReachableValuesOnly()} pins that
 * closed set.
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
    public void theClassStillReferencesTheCallerScopedSiteListApi() throws IOException {
        assertThat(classBytes())
                .as("bulk export must still enumerate sites via the caller-scoped getSitesNodeList()")
                .contains("getSitesNodeList");
    }

    @Test
    public void theClassReferencesNoRootEscalationOrImpersonationApi() throws IOException {
        String bytes = classBytes();
        // None of these escalation APIs currently appears anywhere in the class. Reintroducing a
        // root/system session or an impersonation call to widen the export beyond the caller's
        // rights (the pre-SEC-136 vulnerability) would put one of these tokens back and fail here.
        assertThat(bytes).as("no impersonation").doesNotContain("impersonate");
        // Covers getRootUserSession() too — any API whose name starts this way hands back root.
        assertThat(bytes).as("no root user session").doesNotContain("getRootUser");
        assertThat(bytes).as("no root user lookup").doesNotContain("lookupRootUser");
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
    // This pins the enum to exactly the reachable values the method returns.
    // NOT_SERVER_ADMINISTRATOR was added in SEC-136 §4.3, when the bulk export was
    // restricted to server administrators.
    // -------------------------------------------------------------------------

    /**
     * The single home for this assertion — {@code WebsitesAdminMutationExportPreconditionTest}
     * carried an identical copy until the duplicate was folded in here.
     *
     * <p>The set is closed in both directions. Nothing may be <em>removed</em>: each constant is
     * returned by a reachable branch. Nothing may be <em>added</em> casually either: the enum
     * carries expected, operator-actionable outcomes only — {@code NOT_SERVER_ADMINISTRATOR}
     * qualifies (grant the admin role), {@code AWS_S3_BUCKET_NOT_CONFIGURED} qualifies (complete
     * the configuration). Unexpected failures are raised as {@link DataFetchingException} so their
     * cause survives into the GraphQL error extensions
     * ({@link org.jahia.modules.graphql.provider.dxm.DataFetchingException}); flattening one into
     * a constant here would discard exactly the information needed to diagnose it. See
     * {@code WebsitesAdminMutationExportAllSitesFailureTest} for the exception half of that
     * contract.
     */
    @Test
    public void exportAllSitesResults_declaresReachableValuesOnly() {
        assertThat(WebsitesAdminMutation.ExportAllSitesResults.values())
                .containsExactlyInAnyOrder(WebsitesAdminMutation.ExportAllSitesResults.SUCCESS,
                        WebsitesAdminMutation.ExportAllSitesResults.AWS_S3_BUCKET_NOT_CONFIGURED,
                        WebsitesAdminMutation.ExportAllSitesResults.NOT_SERVER_ADMINISTRATOR);
    }
}
