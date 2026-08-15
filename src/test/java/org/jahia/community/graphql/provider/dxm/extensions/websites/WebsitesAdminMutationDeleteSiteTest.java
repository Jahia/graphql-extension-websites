package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.exceptions.JahiaException;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.sites.JahiaSite;
import org.jahia.services.sites.JahiaSitesService;
import org.junit.Test;
import org.mockito.MockedStatic;

import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebsitesAdminMutation#deleteSiteByKey(String)}.
 *
 * <p>Before this class the mutation had no JUnit coverage at all — only two end-to-end Cypress
 * assertions (delete-existing and delete-missing), neither of which can reach the failure
 * branches. These tests pin every outcome container-free by static-mocking the
 * {@link ServicesRegistry} and {@link JCRSessionFactory} singletons.
 *
 * <p><b>SEC-136.</b> The authorization tests below are the reason this class exists in its
 * current shape. Until 2.1.0 {@code deleteSiteByKey} ran straight from {@code getSiteByKey} to
 * {@code removeSite} with no target-scoped check, so a holder of the delegated
 * {@code graphql-extension-websites-administrator} role could destroy any site on the instance.
 * The gap survived a first remediation pass precisely because no test asserted it:
 * {@link #deleteSiteByKey_returnsFalse_andRemovesNothing_whenCallerLacksPermissionOnTheSite()}
 * is the regression lock, and it must fail if the {@code hasPermission} call is removed.
 *
 * <p>Note that {@code verify(sitesService, never()).removeSite(...)} — not merely a {@code false}
 * return — is the assertion that matters on the denied path. {@code deleteSiteByKey} already
 * returns {@code false} for a site that does not exist, so a {@code false} on its own cannot
 * distinguish "denied" from "not found".
 *
 * <p>The other important case is
 * {@link #deleteSiteByKey_propagatesUnexpectedRuntimeException()}. {@code deleteSiteByKey}
 * previously caught {@code JahiaException | RuntimeException}, so a programming error such as an
 * NPE inside {@code removeSite} was reported to the caller as an ordinary {@code false} — the same
 * answer as "this site could not be deleted". That test locks in the narrowed catch so the two
 * cannot be conflated again.
 */
public class WebsitesAdminMutationDeleteSiteTest {

    private static final String SITE_KEY = "cypress-test-website";
    private static final String SITE_PATH = "/sites/" + SITE_KEY;
    private static final String DELETE_PERMISSION = "websitesDelete";

    /** A {@link JahiaException} is awkward to construct; this keeps the tests readable. */
    private static JahiaException jahiaException() {
        return new JahiaException("boom", "boom", JahiaException.DATA_ERROR, JahiaException.ERROR_SEVERITY);
    }

    private static JahiaSite site() {
        JahiaSite site = mock(JahiaSite.class);
        when(site.getJCRLocalPath()).thenReturn(SITE_PATH);
        return site;
    }

    private static ServicesRegistry registryReturning(JahiaSitesService sitesService) {
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);
        return registry;
    }

    /**
     * Builds a {@link JCRSessionFactory} whose current-user session answers {@code permitted}
     * for {@code websitesDelete} on {@link #SITE_PATH}.
     *
     * <p>Built <em>before</em> any {@code MockedStatic} scope is opened: calling {@code when(...)}
     * while a {@code MockedStatic.when(...)} is mid-flight raises
     * {@code UnfinishedStubbingException}.
     */
    private static JCRSessionFactory sessionFactoryGranting(boolean permitted) throws RepositoryException {
        JCRNodeWrapper siteNode = mock(JCRNodeWrapper.class);
        when(siteNode.hasPermission(DELETE_PERMISSION)).thenReturn(permitted);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode(SITE_PATH)).thenReturn(siteNode);
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        when(factory.getCurrentUserSession()).thenReturn(session);
        return factory;
    }

    /** A session factory whose {@code getNode} throws, standing in for an unreadable site. */
    private static JCRSessionFactory sessionFactoryThrowing(RepositoryException failure) throws RepositoryException {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode(SITE_PATH)).thenThrow(failure);
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        when(factory.getCurrentUserSession()).thenReturn(session);
        return factory;
    }

    @Test
    public void deleteSiteByKey_returnsTrue_whenSiteExistsAndCallerIsAuthorized() throws Exception {
        // Arrange
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JahiaSite site = site();
        when(sitesService.getSiteByKey(SITE_KEY)).thenReturn(site);
        ServicesRegistry registry = registryReturning(sitesService);
        JCRSessionFactory sessionFactory = sessionFactoryGranting(true);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Boolean result = new WebsitesAdminMutation().deleteSiteByKey(SITE_KEY);

            // Assert
            assertThat(result).isTrue();
            verify(sitesService, times(1)).removeSite(site);
        }
    }

    /**
     * SEC-136 regression lock. A caller holding {@code websitesAdmin} at the root — enough to
     * pass the GraphQL annotation and reach this method — but <em>not</em> {@code websitesDelete}
     * on the target site must not destroy it.
     */
    @Test
    public void deleteSiteByKey_returnsFalse_andRemovesNothing_whenCallerLacksPermissionOnTheSite() throws Exception {
        // Arrange — the site exists and is resolvable; only the permission is missing
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JahiaSite site = site();
        when(sitesService.getSiteByKey(SITE_KEY)).thenReturn(site);
        ServicesRegistry registry = registryReturning(sitesService);
        JCRSessionFactory sessionFactory = sessionFactoryGranting(false);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Boolean result = new WebsitesAdminMutation().deleteSiteByKey(SITE_KEY);

            // Assert — the never() is the load-bearing half; `false` alone would also be
            // returned for a site that does not exist.
            assertThat(result).isFalse();
            verify(sitesService, never()).removeSite(any(JahiaSite.class));
        }
    }

    /**
     * The permission must be checked against the <em>site's own</em> node, in the caller's own
     * session. A check against the repository root would be satisfied by the root-granted server
     * role and would reinstate the vulnerability.
     */
    @Test
    public void deleteSiteByKey_checksThePermissionOnTheSiteNodeInTheCallerSession() throws Exception {
        // Arrange
        JCRNodeWrapper siteNode = mock(JCRNodeWrapper.class);
        when(siteNode.hasPermission(DELETE_PERMISSION)).thenReturn(true);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode(SITE_PATH)).thenReturn(siteNode);
        JCRSessionFactory sessionFactory = mock(JCRSessionFactory.class);
        when(sessionFactory.getCurrentUserSession()).thenReturn(session);

        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JahiaSite site = site(); // built before the stubbing call below opens (see sessionFactoryGranting)
        when(sitesService.getSiteByKey(SITE_KEY)).thenReturn(site);
        ServicesRegistry registry = registryReturning(sitesService);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            new WebsitesAdminMutation().deleteSiteByKey(SITE_KEY);

            // Assert — the caller's session was used, on the site path, for websitesDelete
            verify(sessionFactory, times(1)).getCurrentUserSession();
            verify(session, times(1)).getNode(SITE_PATH);
            verify(siteNode, times(1)).hasPermission(DELETE_PERMISSION);
        }
    }

    /** A caller who cannot resolve the site is denied, not permitted. */
    @Test
    public void deleteSiteByKey_failsClosed_whenTheSiteIsNotVisibleToTheCaller() throws Exception {
        // Arrange
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JahiaSite site = site();
        when(sitesService.getSiteByKey(SITE_KEY)).thenReturn(site);
        ServicesRegistry registry = registryReturning(sitesService);
        JCRSessionFactory sessionFactory = sessionFactoryThrowing(new PathNotFoundException(SITE_PATH));

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Boolean result = new WebsitesAdminMutation().deleteSiteByKey(SITE_KEY);

            // Assert
            assertThat(result).isFalse();
            verify(sitesService, never()).removeSite(any(JahiaSite.class));
        }
    }

    /** A repository failure while checking the permission denies rather than defaulting open. */
    @Test
    public void deleteSiteByKey_failsClosed_whenThePermissionCheckErrors() throws Exception {
        // Arrange
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JahiaSite site = site();
        when(sitesService.getSiteByKey(SITE_KEY)).thenReturn(site);
        ServicesRegistry registry = registryReturning(sitesService);
        JCRSessionFactory sessionFactory = sessionFactoryThrowing(new RepositoryException("jcr down"));

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Boolean result = new WebsitesAdminMutation().deleteSiteByKey(SITE_KEY);

            // Assert
            assertThat(result).isFalse();
            verify(sitesService, never()).removeSite(any(JahiaSite.class));
        }
    }

    @Test
    public void deleteSiteByKey_returnsFalse_andRemovesNothing_whenSiteNotFound() throws Exception {
        // Arrange
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        when(sitesService.getSiteByKey("no-such-site")).thenReturn(null);
        ServicesRegistry registry = registryReturning(sitesService);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);

            // Act
            Boolean result = new WebsitesAdminMutation().deleteSiteByKey("no-such-site");

            // Assert — returns before any permission check, so no JCRSessionFactory mock is needed
            assertThat(result).isFalse();
            verify(sitesService, never()).removeSite(any(JahiaSite.class));
        }
    }

    @Test
    public void deleteSiteByKey_returnsFalse_whenRemoveSiteFailsWithJahiaException() throws Exception {
        // Arrange
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JahiaSite site = site();
        when(sitesService.getSiteByKey(SITE_KEY)).thenReturn(site);
        doThrow(jahiaException()).when(sitesService).removeSite(site);
        ServicesRegistry registry = registryReturning(sitesService);
        JCRSessionFactory sessionFactory = sessionFactoryGranting(true);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act
            Boolean result = new WebsitesAdminMutation().deleteSiteByKey(SITE_KEY);

            // Assert — a domain-level failure is reported as false, not raised
            assertThat(result).isFalse();
        }
    }

    @Test
    public void deleteSiteByKey_returnsFalse_whenSiteLookupFailsWithJahiaException() throws Exception {
        // Arrange
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        when(sitesService.getSiteByKey(SITE_KEY)).thenThrow(jahiaException());
        ServicesRegistry registry = registryReturning(sitesService);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);

            // Act
            Boolean result = new WebsitesAdminMutation().deleteSiteByKey(SITE_KEY);

            // Assert
            assertThat(result).isFalse();
        }
    }

    @Test
    public void deleteSiteByKey_propagatesUnexpectedRuntimeException() throws Exception {
        // Arrange — an unchecked failure stands in for a programming error inside removeSite
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JahiaSite site = site();
        when(sitesService.getSiteByKey(SITE_KEY)).thenReturn(site);
        doThrow(new IllegalStateException("unexpected")).when(sitesService).removeSite(site);
        ServicesRegistry registry = registryReturning(sitesService);
        JCRSessionFactory sessionFactory = sessionFactoryGranting(true);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            // Act + Assert — must surface to the GraphQL layer, NOT be flattened into `false`
            assertThatThrownBy(() -> new WebsitesAdminMutation().deleteSiteByKey(SITE_KEY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("unexpected");
        }
    }
}
