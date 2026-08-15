import org.jahia.services.content.JCRTemplate
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.JCRCallback
import javax.jcr.PathNotFoundException

/*
 * Removes a role created by createNarrowRole.groovy.
 *
 * Teardown for the custom roles a spec builds: without it the role node survives the run, so the
 * next run starts against an instance that is no longer the shipped configuration — and a role
 * left behind under /roles is visible to every other test and to a human inspecting the instance.
 *
 * Idempotent: a role that is already gone is not an error.
 *
 * Token: __ROLE_NAME__ — validated as a single safe path segment before anything is removed. It is
 * a literal constant at the only call site today, but Node.getNode(relPath) accepts a RELATIVE
 * PATH, so '..' is a legal segment: a malformed value would walk out of /roles and remove an
 * arbitrary subtree under a SYSTEM session. Same guard as cleanupExportImportDirs.groovy, for the
 * same reason.
 */
def roleName = '__ROLE_NAME__'.trim()

if (!(roleName ==~ /[A-Za-z0-9][A-Za-z0-9._-]*/)) {
    throw new IllegalArgumentException("Refusing to remove role '${roleName}': not a safe single path segment")
}

JCRTemplate.getInstance().doExecuteWithSystemSession({ JCRSessionWrapper session ->
    try {
        session.getNode('/roles').getNode(roleName).remove()
        session.save()
        log.info("DELETE-ROLE removed ${roleName}")
    } catch (PathNotFoundException ignored) {
        log.info("DELETE-ROLE ${roleName} was already absent")
    }

    return null
} as JCRCallback)
