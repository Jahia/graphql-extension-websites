package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.api.settings.SettingsBean;
import org.jahia.osgi.BundleUtils;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SEC-136 §4.3 — {@link WebsitesAdminMutation#exportWebsite} is target-scoped.
 *
 * <p>Exporting a named site is authorized the same way deleting one is: the caller must hold
 * {@code websitesExport} <em>on that site</em>, checked in their own session. The root-evaluated
 * {@code @GraphQLRequiresPermission("websitesExport")} annotation cannot express this, because its
 * path is static while the target arrives as a runtime argument.
 *
 * <p>It would be tempting to argue this check is unnecessary — the export already runs under the
 * caller's session, so an unauthorized caller would get an empty archive rather than data. That
 * reasoning makes the security property depend on read ACLs happening to line up, and it still
 * writes a misleading near-empty archive to disk. These tests pin the explicit refusal instead:
 * the assertion that matters is that the exporter is never even requested from the registry, not the {@code false}
 * return, since {@code exportWebsite} already returns {@code false} for several other reasons.
 */
public class WebsitesAdminMutationExportScopeTest {

    private static final String SITE_KEY = "cypress-test-website";
    private static final String SITE_PATH = "/sites/" + SITE_KEY;
    private static final String EXPORT_PERMISSION = "websitesExport";

    /** A base dir that does not exist on disk, so PathSecurity uses its lexical check only. */
    private static final String VAR_DISK_PATH = "/var/jahia-unit-test";

    private static JahiaSite site() {
        JahiaSite site = mock(JahiaSite.class);
        when(site.getJCRLocalPath()).thenReturn(SITE_PATH);
        return site;
    }

    private static SettingsBean settings() {
        SettingsBean settings = mock(SettingsBean.class);
        when(settings.getJahiaVarDiskPath()).thenReturn(VAR_DISK_PATH);
        return settings;
    }

    /**
     * The exporter itself is deliberately NOT mocked. {@code ImportExportBaseService} cannot be
     * class-initialized outside a running Jahia (it fails with NoClassDefFoundError), which is the
     * same constraint that keeps {@code WebsitesAdminMutationExportWebsiteTest} to pure param
     * construction. Instead these tests assert the service is never <em>requested</em> from the
     * registry — if the export proceeded past the gate it would have to ask for it, so this is an
     * equally strong signal and needs no instance.
     */
    private static ServicesRegistry registryResolvingTheSite() throws Exception {
        JahiaSitesService sites = mock(JahiaSitesService.class);
        JahiaSite site = site();
        when(sites.getSiteByKey(SITE_KEY)).thenReturn(site);
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sites);
        return registry;
    }

    /** Session factory answering {@code granted} for websitesExport on the site node. */
    private static JCRSessionFactory sessionFactoryGranting(boolean granted) throws RepositoryException {
        JCRNodeWrapper siteNode = mock(JCRNodeWrapper.class);
        when(siteNode.hasPermission(EXPORT_PERMISSION)).thenReturn(granted);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode(SITE_PATH)).thenReturn(siteNode);
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        when(factory.getCurrentUserSession()).thenReturn(session);
        return factory;
    }

    private static JCRSessionFactory sessionFactoryThrowing(RepositoryException failure) throws RepositoryException {
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode(SITE_PATH)).thenThrow(failure);
        JCRSessionFactory factory = mock(JCRSessionFactory.class);
        when(factory.getCurrentUserSession()).thenReturn(session);
        return factory;
    }

    /**
     * Runs exportWebsite against {@code registry}, which the caller owns so it can verify against
     * it afterwards. Deliberately passed in rather than stashed in a static field: shared mutable
     * state between test methods is an ordering hazard waiting to happen.
     */
    private static Boolean exportWith(JCRSessionFactory sessionFactory, ServicesRegistry registry) throws Exception {
        SettingsBean settings = settings();

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class);
             MockedStatic<BundleUtils> bundleUtils = mockStatic(BundleUtils.class);
             MockedStatic<JCRSessionFactory> factoryStatic = mockStatic(JCRSessionFactory.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            bundleUtils.when(() -> BundleUtils.getOsgiService(SettingsBean.class, null)).thenReturn(settings);
            factoryStatic.when(JCRSessionFactory::getInstance).thenReturn(sessionFactory);

            return new WebsitesAdminMutation().exportWebsite(SITE_KEY, "my-export", false);
        }
    }

    @Test
    public void exportWebsite_refusesAndExportsNothing_whenCallerLacksPermissionOnTheSite() throws Exception {
        // Arrange
        ServicesRegistry registry = registryResolvingTheSite();

        // Act
        Boolean result = exportWith(sessionFactoryGranting(false), registry);

        // Assert — never() is the load-bearing half; `false` alone is also returned for a bad
        // path, a missing site, or a symlinked export target.
        assertThat(result).isFalse();
        verify(registry, never()).getImportExportService();
    }

    @Test
    public void exportWebsite_failsClosed_whenTheSiteIsNotVisibleToTheCaller() throws Exception {
        ServicesRegistry registry = registryResolvingTheSite();

        Boolean result = exportWith(sessionFactoryThrowing(new PathNotFoundException(SITE_PATH)), registry);

        assertThat(result).isFalse();
        verify(registry, never()).getImportExportService();
    }

    @Test
    public void exportWebsite_failsClosed_whenThePermissionCheckErrors() throws Exception {
        ServicesRegistry registry = registryResolvingTheSite();

        Boolean result = exportWith(sessionFactoryThrowing(new RepositoryException("jcr down")), registry);

        assertThat(result).isFalse();
        verify(registry, never()).getImportExportService();
    }

    /**
     * The check must consult the site's own node in the caller's session — not the repository
     * root, which the server role would satisfy for every site.
     */
    @Test
    public void exportWebsite_checksThePermissionOnTheSiteNodeInTheCallerSession() throws Exception {
        JCRNodeWrapper siteNode = mock(JCRNodeWrapper.class);
        when(siteNode.hasPermission(EXPORT_PERMISSION)).thenReturn(false);
        JCRSessionWrapper session = mock(JCRSessionWrapper.class);
        when(session.getNode(SITE_PATH)).thenReturn(siteNode);
        JCRSessionFactory sessionFactory = mock(JCRSessionFactory.class);
        when(sessionFactory.getCurrentUserSession()).thenReturn(session);

        exportWith(sessionFactory, registryResolvingTheSite());

        verify(session).getNode(SITE_PATH);
        verify(siteNode).hasPermission(EXPORT_PERMISSION);
    }
}
