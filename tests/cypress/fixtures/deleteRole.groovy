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
 * Token: __ROLE_NAME__
 */
def roleName = '__ROLE_NAME__'

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
