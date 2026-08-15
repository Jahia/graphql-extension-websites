import org.jahia.services.content.JCRTemplate
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.JCRCallback
import javax.jcr.PathNotFoundException

/**
 * Creates a server role carrying a caller-chosen subset of the module's permissions.
 *
 * The module ships one broad role (`graphql-extension-websites-administrator`), which cannot
 * demonstrate that the per-operation permission split has any effect — it carries every
 * permission, so it passes every gate either way. A narrow role built here is what makes the
 * split falsifiable: grant only `websitesCreate` and the holder must be able to create a site
 * and be REFUSED a bulk instance export.
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
def roleName = '__ROLE_NAME__'
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
