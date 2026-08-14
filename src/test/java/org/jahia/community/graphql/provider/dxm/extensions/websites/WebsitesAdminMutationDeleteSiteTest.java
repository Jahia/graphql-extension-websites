package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.exceptions.JahiaException;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.sites.JahiaSite;
import org.jahia.services.sites.JahiaSitesService;
import org.junit.Test;
import org.mockito.MockedStatic;

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
 * branches. These tests pin all four outcomes container-free by static-mocking the
 * {@link ServicesRegistry} singleton.
 *
 * <p>The most important case here is
 * {@link #deleteSiteByKey_propagatesUnexpectedRuntimeException()}. {@code deleteSiteByKey}
 * previously caught {@code JahiaException | RuntimeException}, so a programming error such as an
 * NPE inside {@code removeSite} was reported to the caller as an ordinary {@code false} — the same
 * answer as "this site could not be deleted". That test locks in the narrowed catch so the two
 * cannot be conflated again.
 */
public class WebsitesAdminMutationDeleteSiteTest {

    private static final String SITE_KEY = "cypress-test-website";

    /** A {@link JahiaException} is awkward to construct; this keeps the tests readable. */
    private static JahiaException jahiaException() {
        return new JahiaException("boom", "boom", JahiaException.DATA_ERROR, JahiaException.ERROR_SEVERITY);
    }

    @Test
    public void deleteSiteByKey_returnsTrue_whenSiteExists() throws Exception {
        // Arrange
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JahiaSite site = mock(JahiaSite.class);
        when(sitesService.getSiteByKey(SITE_KEY)).thenReturn(site);
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);

            // Act
            Boolean result = new WebsitesAdminMutation().deleteSiteByKey(SITE_KEY);

            // Assert
            assertThat(result).isTrue();
            verify(sitesService, times(1)).removeSite(site);
        }
    }

    @Test
    public void deleteSiteByKey_returnsFalse_andRemovesNothing_whenSiteNotFound() throws Exception {
        // Arrange
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        when(sitesService.getSiteByKey("no-such-site")).thenReturn(null);
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);

            // Act
            Boolean result = new WebsitesAdminMutation().deleteSiteByKey("no-such-site");

            // Assert
            assertThat(result).isFalse();
            verify(sitesService, never()).removeSite(any(JahiaSite.class));
        }
    }

    @Test
    public void deleteSiteByKey_returnsFalse_whenRemoveSiteFailsWithJahiaException() throws Exception {
        // Arrange
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        JahiaSite site = mock(JahiaSite.class);
        when(sitesService.getSiteByKey(SITE_KEY)).thenReturn(site);
        doThrow(jahiaException()).when(sitesService).removeSite(site);
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);

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
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);

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
        JahiaSite site = mock(JahiaSite.class);
        when(sitesService.getSiteByKey(SITE_KEY)).thenReturn(site);
        doThrow(new IllegalStateException("unexpected")).when(sitesService).removeSite(site);
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);

        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);

            // Act + Assert — must surface to the GraphQL layer, NOT be flattened into `false`
            assertThatThrownBy(() -> new WebsitesAdminMutation().deleteSiteByKey(SITE_KEY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("unexpected");
        }
    }
}
