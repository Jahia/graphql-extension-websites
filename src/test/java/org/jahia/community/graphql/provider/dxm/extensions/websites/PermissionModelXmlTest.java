package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants of the shipped permission model — {@code src/main/import/permissions.xml} and
 * {@code src/main/import/roles.xml}.
 *
 * <p>The SEC-136 remediation is half Java and half JCR content, and only the Java half had tests.
 * The XML half rests on two structural invariants that a one-token edit can break with no
 * compilation error, no test failure and no visible symptom — the module simply stops enforcing
 * the thing it was written to enforce, on a live instance, silently. Both edits look like
 * housekeeping:
 *
 * <ol>
 *   <li><b>Nesting a permission.</b> Jahia registers nested permission nodes as <em>aggregated
 *       sub-privileges</em> ({@code JahiaPrivilegeRegistry.registerPrivileges} →
 *       {@code new PrivilegeImpl(..., subPrivileges, ...)}), so moving {@code websitesDelete} or
 *       {@code websitesExport} inside {@code websitesAdmin} — an obvious-looking way to express
 *       "these belong to the websites admin area" — hands them straight back to every holder of
 *       the root-granted server role. The permission split would still be there to read, and would
 *       grant exactly nothing.</li>
 *   <li><b>Adding a permission to the server role.</b> That role is granted at the JCR root
 *       ({@code j:nodeTypes="rep:root"}) and JCR permissions inherit downward, so anything listed
 *       there is held on <em>every</em> node, including every site. Listing {@code websitesDelete}
 *       or {@code websitesExport} there makes the per-site check in {@code deleteSiteByKey} /
 *       {@code exportWebsite} pass everywhere — vacuous, and the original vulnerability is back.
 *       {@code jcr:read_default} is the same mistake in the confidentiality dimension: it is what
 *       made "the export is bounded by the caller's read rights" a claim about the entire
 *       repository (§4.3).</li>
 * </ol>
 *
 * <p>These tests are cheap, need no container, and are the only thing in the repository that can
 * fail when the model is wrong. The Cypress specs cannot substitute: they exercise a live
 * instance with fixed users, so a permission that has quietly become broader still lets every
 * assertion pass.
 */
public class PermissionModelXmlTest {

    private static final String SERVER_ROLE = "graphql-extension-websites-administrator";
    private static final String SITE_ROLE = "graphql-extension-websites-site-administrator";

    /** Target-scoped permissions: they must never be reachable from the repository root. */
    private static final List<String> TARGET_SCOPED_PERMISSIONS =
            Arrays.asList("websitesDelete", "websitesExport");

    private static Document permissions;
    private static Document roles;

    @BeforeClass
    public static void parseTheShippedModel() throws Exception {
        permissions = parse(Paths.get("src", "main", "import", "permissions.xml"));
        roles = parse(Paths.get("src", "main", "import", "roles.xml"));
    }

    /**
     * Parses with a <em>namespace-unaware</em> builder on purpose: the model uses prefixed
     * attributes ({@code j:permissionNames}, {@code jcr:primaryType}) and element names are the
     * permission and role names themselves, so treating the prefixes as literal text is both
     * simpler and closer to how the file is read by a human reviewer.
     */
    private static Document parse(Path relativePath) throws Exception {
        File file = relativePath.toFile();
        assertThat(file)
                .as("%s must exist — the whole SEC-136 permission model ships in it", relativePath)
                .exists();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Untrusted-input hygiene, even though this file is our own: no DTDs, no external entities.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(file);
    }

    /** Direct child elements of {@code parent}, in document order. */
    private static List<Element> childElements(Node parent) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element) {
                children.add((Element) nodes.item(i));
            }
        }
        return children;
    }

    private static List<String> namesOf(List<Element> elements) {
        List<String> names = new ArrayList<>(elements.size());
        for (Element element : elements) {
            names.add(element.getTagName());
        }
        return names;
    }

    /** The single {@code <admin>} container every permission of this module hangs off. */
    private static Element adminPermission() {
        List<Element> roots = childElements(permissions.getDocumentElement());
        assertThat(namesOf(roots))
                .as("permissions.xml must declare exactly one <admin> container")
                .containsExactly("admin");
        return roots.get(0);
    }

    private static Element roleNamed(String name) {
        for (Element role : childElements(roles.getDocumentElement())) {
            if (name.equals(role.getTagName())) {
                return role;
            }
        }
        throw new AssertionError("roles.xml no longer declares the '" + name + "' role");
    }

    /** The {@code j:permissionNames} of a role, split on whitespace. */
    private static Set<String> permissionNamesOf(Element role) {
        String raw = role.getAttribute("j:permissionNames");
        Set<String> names = new LinkedHashSet<>();
        for (String token : raw.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                names.add(token);
            }
        }
        return names;
    }

    // -------------------------------------------------------------------------
    // Invariant (a): the target-scoped permissions are SIBLINGS of websitesAdmin
    // -------------------------------------------------------------------------

    @Test
    public void allFivePermissionsAreDeclaredAsSiblingsUnderAdmin() {
        List<String> declared = namesOf(childElements(adminPermission()));

        assertThat(declared)
                .as("all five permissions must be siblings directly under <admin>: nesting any of "
                        + "them under another makes it an aggregated sub-privilege of that one, and "
                        + "holding the parent then implies the child")
                .containsExactlyInAnyOrder("websitesAdmin", "websitesCreate", "websitesExport",
                        "websitesExportAll", "websitesDelete");
    }

    /**
     * The sharpest form of invariant (a): {@code websitesAdmin} must be a leaf. Anything nested
     * beneath it is aggregated into it, so a delegated holder of the coarse entry gate would
     * acquire it for free — which is precisely the split SEC-136 introduced.
     */
    @Test
    public void websitesAdminHasNoNestedPermissions() {
        Element websitesAdmin = null;
        for (Element permission : childElements(adminPermission())) {
            if ("websitesAdmin".equals(permission.getTagName())) {
                websitesAdmin = permission;
            }
        }
        assertThat(websitesAdmin).as("permissions.xml no longer declares websitesAdmin").isNotNull();

        assertThat(namesOf(childElements(websitesAdmin)))
                .as("websitesAdmin must stay a LEAF. Jahia registers nested permission nodes as "
                        + "aggregated sub-privileges, so nesting websitesDelete or websitesExport "
                        + "here would re-grant them to every holder of the root-granted "
                        + "'%s' role — a permission split that changes nothing", SERVER_ROLE)
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Invariant (b): the root-granted server role stays narrow
    // -------------------------------------------------------------------------

    /** The inheritance argument below only holds because this role is granted at the JCR root. */
    @Test
    public void theServerRoleIsGrantedAtTheRepositoryRoot() {
        Element serverRole = roleNamed(SERVER_ROLE);

        assertThat(serverRole.getAttribute("j:nodeTypes"))
                .as("if this role stops being root-granted, the reasoning behind every assertion "
                        + "in this class changes and they must all be revisited")
                .isEqualTo("rep:root");
        assertThat(serverRole.getAttribute("j:roleGroup")).isEqualTo("server-role");
    }

    @Test
    public void theServerRoleGrantsNeitherTargetScopedPermission() {
        Set<String> granted = permissionNamesOf(roleNamed(SERVER_ROLE));

        assertThat(granted)
                .as("'%s' is granted at '/' and JCR permissions inherit downward, so granting %s "
                        + "here satisfies the per-site check in deleteSiteByKey / exportWebsite on "
                        + "EVERY site — the check becomes vacuous and SEC-136 is reinstated. These "
                        + "belong on the site-scoped '%s' role only",
                        SERVER_ROLE, TARGET_SCOPED_PERMISSIONS, SITE_ROLE)
                .doesNotContainAnyElementsOf(TARGET_SCOPED_PERMISSIONS);
    }

    @Test
    public void theServerRoleGrantsNoRepositoryWideRead() {
        Set<String> granted = permissionNamesOf(roleNamed(SERVER_ROLE));

        assertThat(granted)
                .as("jcr:read_default at '/' gives every holder read over the entire repository, "
                        + "which is what made 'exportAllSites is bounded by the caller's read "
                        + "rights' vacuous for this role (§4.3). Read is granted per site through "
                        + "'%s' instead. It is not needed to reach the API", SITE_ROLE)
                .doesNotContain("jcr:read_default");
    }

    /**
     * The complement of the two tests above: the server role must still grant enough to reach the
     * API. A "fix" that empties it would satisfy every restriction here while breaking delegation
     * entirely — the failure mode the SEC-136 notes warn about repeatedly.
     */
    @Test
    public void theServerRoleStillGrantsApiAccess() {
        Set<String> granted = permissionNamesOf(roleNamed(SERVER_ROLE));

        assertThat(granted)
                .as("without these a delegated holder cannot reach the websites mutations at all")
                .contains("graphqlAdminMutation", "websitesAdmin");
    }

    // -------------------------------------------------------------------------
    // The site-scoped role is where the target-scoped permissions actually live
    // -------------------------------------------------------------------------

    @Test
    public void theSiteRoleCarriesBothTargetScopedPermissionsAndSiteRead() {
        Element siteRole = roleNamed(SITE_ROLE);

        assertThat(siteRole.getAttribute("j:nodeTypes"))
                .as("the role must be grantable on a site, not at the root")
                .isEqualTo("jnt:virtualsite");
        assertThat(siteRole.getAttribute("j:roleGroup")).isEqualTo("site-role");
        assertThat(permissionNamesOf(siteRole))
                .as("this role IS the delegation mechanism: without these permissions the in-body "
                        + "per-site checks can never pass for anyone but a server administrator, "
                        + "and exportWebsite would produce an empty archive without site read")
                .contains("websitesDelete", "websitesExport", "jcr:read_default");
    }

    // -------------------------------------------------------------------------
    // Cross-file consistency
    // -------------------------------------------------------------------------

    /**
     * A permission named in a role but never declared is silently ignored by Jahia — the role
     * simply grants less than it appears to. A typo in either file would otherwise be invisible
     * until someone noticed an operation failing for a user who should have been allowed.
     */
    @Test
    public void everyWebsitesPermissionNamedByARoleIsDeclaredInPermissionsXml() {
        List<String> declared = namesOf(childElements(adminPermission()));
        Set<String> referenced = new LinkedHashSet<>();
        referenced.addAll(permissionNamesOf(roleNamed(SERVER_ROLE)));
        referenced.addAll(permissionNamesOf(roleNamed(SITE_ROLE)));
        referenced.removeIf(name -> !name.startsWith("websites"));

        assertThat(declared)
                .as("roles.xml references websites permissions that permissions.xml does not "
                        + "declare; an undeclared permission is silently ignored, so the role "
                        + "grants less than it reads as")
                .containsAll(referenced);
    }
}
