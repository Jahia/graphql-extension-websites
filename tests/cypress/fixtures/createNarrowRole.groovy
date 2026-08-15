import org.jahia.services.content.JCRTemplate
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.JCRCallback
import javax.jcr.PathNotFoundException

/**
 * Creates a server role carrying a caller-chosen subset of the module's permissions.
 *
 * The module ships one broad role (`graphql-extension-websites-administrator`), which cannot
 * demonstrate that the per-operation permission split has any effect. It carries
 * `graphqlAdminMutation websitesAdmin websitesCreate websitesExportAll`, so it clears the
 * annotation gate on every mutation — the split between create and bulk export is invisible
 * through it. A narrow role built here is what makes the split falsifiable: grant only
 * `websitesCreate` and the holder must be able to create a site and be REFUSED a bulk instance
 * export.
 *
 * (The server role does NOT pass every gate: `websitesDelete` and `websitesExport` live only on
 * the site-scoped role, and exportAllSites/importWebsite each require full server administrator
 * in the method body. It is broad at the ANNOTATION layer, which is the layer this fixture is
 * about.)
 *
 * `graphqlAdminMutation` is always included because it is required to traverse
 * `admin { jahia { ... } }` at all; without it the caller is denied on the wrapper field and the
 * test would pass for the wrong reason.
 *
 * `jcr:read_default` is deliberately NOT included. An earlier revision granted it here and
 * claimed it was needed to reach the API — verified false on a live instance: a caller holding
 * only `graphqlAdminMutation` + `websitesCreate`, with no read at all, invokes createSiteByKey
 * successfully. It is also the exact grant §4.3 removed from the shipped server role
 * (src/main/import/roles.xml), because this role is granted at `/` and JCR permissions inherit
 * downward, so a root read grant hands the holder read over the whole repository — which would
 * make this fixture broader than the role it is meant to narrow.
 *
 * Tokens: __ROLE_NAME__, __PERMISSIONS__ (space-separated permission names).
 */
// `Node.getNode(relPath)` and `Node.addNode(relPath)` both take a RELATIVE PATH, so `..` is a
// legal segment: an unsubstituted or malformed value would walk out of /roles and remove or
// create an arbitrary subtree under a SYSTEM session. Validate as a single safe path segment and
// fail loudly — a guard that silently does not apply is worse than no guard. Same check as
// deleteRole.groovy, which cleans up after this script.
def roleName = '__ROLE_NAME__'.trim()
if (!(roleName ==~ /[A-Za-z0-9][A-Za-z0-9._-]*/)) {
    throw new IllegalArgumentException("createNarrowRole: refusing unsafe role name: '${roleName}'")
}

def permissions = '__PERMISSIONS__'.trim().split('\\s+') as List

JCRTemplate.getInstance().doExecuteWithSystemSession({ JCRSessionWrapper session ->

    def roles = session.getNode('/roles')

    // Idempotent: a re-run must not fail on an existing node, and must not silently keep a
    // stale permission list from a previous run.
    try {
        roles.getNode(roleName).remove()
        session.save()
    } catch (PathNotFoundException ignored) {
        // first run
    }

    def role = roles.addNode(roleName, 'jnt:role')
    role.setProperty('j:nodeTypes', ['rep:root'] as String[])
    role.setProperty('j:roleGroup', 'server-role')
    role.setProperty('j:privilegedAccess', true)

    def all = (['graphqlAdminMutation'] + permissions).unique()
    role.setProperty('j:permissionNames', all as String[])

    session.save()

    log.info("NARROW-ROLE created ${roleName} with permissions ${all}")
    return null
} as JCRCallback)
