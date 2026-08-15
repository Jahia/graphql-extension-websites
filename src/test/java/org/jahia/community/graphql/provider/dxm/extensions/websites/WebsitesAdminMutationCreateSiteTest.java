package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.exceptions.JahiaException;
import org.jahia.registries.ServicesRegistry;
import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.sites.JahiaSitesService;
import org.jahia.services.sites.SiteCreationInfo;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import javax.jcr.RepositoryException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebsitesAdminMutation#createSiteByKey} (U6).
 *
 * <h3>The system session</h3>
 *
 * <p>Site creation runs under a JCR <em>system</em> session, not the caller's session: the body
 * wraps {@code addSite(...)} in {@code JCRTemplate.getInstance().doExecuteWithSystemSession(...)}.
 * That escalation is load-bearing — creating {@code /sites/<siteKey>} needs write rights on
 * {@code /sites} that a delegated {@code websitesAdmin} holder does not have, so a caller-scoped
 * {@code doExecute(...)} would break the mutation for exactly the users the permission exists to
 * serve. It is not observable end to end (root and system both succeed), so it is pinned here with
 * a static mock of the {@link JCRTemplate} singleton.
 *
 * <h3>Why the callback is executed rather than stubbed away</h3>
 *
 * <p>{@link #createSiteByKey_runsUnderSystemSession()} stubs the system-session call to return
 * {@code TRUE} and asserts the result is {@code true}. On its own that assertion is a tautology —
 * it restates the stub — and it left the callback body (the part that actually builds the
 * {@link SiteCreationInfo} and calls {@code addSite}) entirely unexecuted: every field could be
 * wired to the wrong argument, or {@code addSite} dropped altogether, with the suite still green.
 *
 * <p>The remaining tests therefore capture the {@link JCRCallback} with an {@link ArgumentCaptor}
 * and run it against a mocked {@link JahiaSitesService}, which is the only way to observe the
 * argument mapping and the two failure translations without a Jahia container.
 */
public class WebsitesAdminMutationCreateSiteTest {

    private static final String SITE_KEY = "cypress-test";

    /** A {@link JahiaException} is awkward to construct; this keeps the tests readable. */
    private static JahiaException jahiaException() {
        return new JahiaException("boom", "boom", JahiaException.DATA_ERROR, JahiaException.ERROR_SEVERITY);
    }

    @Test
    public void createSiteByKey_runsUnderSystemSession() throws Exception {
        // Arrange
        JCRTemplate jcrTemplate = mock(JCRTemplate.class);
        try (MockedStatic<JCRTemplate> jcrTemplateStatic = mockStatic(JCRTemplate.class)) {
            jcrTemplateStatic.when(JCRTemplate::getInstance).thenReturn(jcrTemplate);
            // Stub the system-session execution; return TRUE without running the real callback.
            when(jcrTemplate.doExecuteWithSystemSession(any(JCRCallback.class))).thenReturn(Boolean.TRUE);

            // Act
            Boolean result = new WebsitesAdminMutation().createSiteByKey(
                    SITE_KEY, "localhost", null, "Title", "default", Collections.emptyList(), "en");

            // Assert
            assertThat(result).isTrue();
            verify(jcrTemplate, times(1)).doExecuteWithSystemSession(any(JCRCallback.class));
        }
    }

    /**
     * Invokes the mutation with the supplied arguments and returns the {@link JCRCallback} it
     * handed to the system session, without executing it.
     *
     * <p>The callback only closes over the mutation's arguments, so it can safely be run later,
     * outside the {@link JCRTemplate} static-mock scope.
     */
    @SuppressWarnings("unchecked")
    private static JCRCallback<Boolean> captureCallback(String siteKey, String serverName, String serverNameAliases,
                                                        String title, String templateSet,
                                                        java.util.List<String> modulesToDeploy, String locale)
            throws Exception {
        JCRTemplate jcrTemplate = mock(JCRTemplate.class);
        ArgumentCaptor<JCRCallback> captor = ArgumentCaptor.forClass(JCRCallback.class);
        try (MockedStatic<JCRTemplate> jcrTemplateStatic = mockStatic(JCRTemplate.class)) {
            jcrTemplateStatic.when(JCRTemplate::getInstance).thenReturn(jcrTemplate);
            when(jcrTemplate.doExecuteWithSystemSession(any(JCRCallback.class))).thenReturn(Boolean.TRUE);

            new WebsitesAdminMutation().createSiteByKey(siteKey, serverName, serverNameAliases,
                    title, templateSet, modulesToDeploy, locale);

            verify(jcrTemplate).doExecuteWithSystemSession(captor.capture());
        }
        return captor.getValue();
    }

    /**
     * The callback must map every GraphQL argument onto the matching {@link SiteCreationInfo}
     * field, and must hand the <em>system</em> session it was given straight to {@code addSite} —
     * not fetch another one.
     *
     * <p>{@code modulesToDeploy} is the interesting one: it arrives as a {@code List<String>}
     * (which it must, or graphql-java-annotations cannot bind it — see
     * {@code WebsitesAdminMutationArgumentTypeTest}) and has to reach {@code SiteCreationInfo} as a
     * {@code String[]} via {@code toModulesArray}.
     */
    @Test
    public void createSiteByKey_populatesSiteCreationInfoFromItsArguments() throws Exception {
        // Arrange
        JCRCallback<Boolean> callback = captureCallback(SITE_KEY, "localhost", "alias1,alias2",
                "My Title", "my-template-set", Arrays.asList("module-a", "module-b"), "fr");
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);
        JCRSessionWrapper systemSession = mock(JCRSessionWrapper.class);
        ArgumentCaptor<SiteCreationInfo> info = ArgumentCaptor.forClass(SiteCreationInfo.class);

        // Act
        Boolean result;
        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            result = callback.doInJCR(systemSession);
        }

        // Assert
        assertThat(result).isTrue();
        verify(sitesService, times(1)).addSite(info.capture(), any(JCRSessionWrapper.class));
        assertThat(info.getValue().getSiteKey()).isEqualTo(SITE_KEY);
        assertThat(info.getValue().getServerName()).isEqualTo("localhost");
        assertThat(info.getValue().getServerNameAliases())
                .as("setServerNameAliasesAsString splits the comma-separated GraphQL argument")
                .containsExactly("alias1", "alias2");
        assertThat(info.getValue().getTitle()).isEqualTo("My Title");
        assertThat(info.getValue().getTemplateSet()).isEqualTo("my-template-set");
        assertThat(info.getValue().getModulesToDeploy())
                .as("the List<String> argument must reach SiteCreationInfo as a String[]")
                .containsExactly("module-a", "module-b");
        assertThat(info.getValue().getLocale()).isEqualTo("fr");
        verify(sitesService, times(1)).addSite(any(SiteCreationInfo.class), org.mockito.ArgumentMatchers.same(systemSession));
    }

    /** Omitting {@code modulesToDeploy} must stay distinguishable from supplying an empty list. */
    @Test
    public void createSiteByKey_leavesModulesToDeployNullWhenTheArgumentIsOmitted() throws Exception {
        // Arrange
        JCRCallback<Boolean> callback = captureCallback(SITE_KEY, "localhost", null,
                "Title", "default", null, "en");
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);
        ArgumentCaptor<SiteCreationInfo> info = ArgumentCaptor.forClass(SiteCreationInfo.class);

        // Act
        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            callback.doInJCR(mock(JCRSessionWrapper.class));
        }

        // Assert
        verify(sitesService).addSite(info.capture(), any(JCRSessionWrapper.class));
        assertThat(info.getValue().getModulesToDeploy()).isNull();
        assertThat(info.getValue().getServerNameAliases()).isNull();
    }

    /**
     * An I/O failure while writing the new site is a domain-level failure and is reported as
     * {@code false} — not raised. This is the {@code catch (IOException | JahiaException)} arm
     * inside the callback, which no test executed before.
     */
    @Test
    public void createSiteByKey_callbackReturnsFalse_whenAddSiteFailsWithIOException() throws Exception {
        // Arrange
        JCRCallback<Boolean> callback = captureCallback(SITE_KEY, "localhost", null,
                "Title", "default", Collections.emptyList(), "en");
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        doThrow(new IOException("disk full"))
                .when(sitesService).addSite(any(SiteCreationInfo.class), any(JCRSessionWrapper.class));
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);

        // Act
        Boolean result;
        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            result = callback.doInJCR(mock(JCRSessionWrapper.class));
        }

        // Assert
        assertThat(result).isFalse();
    }

    /**
     * The commonest real failure: the requested template set is not installed, so {@code addSite}
     * raises a {@link JahiaException}. It must be reported as {@code false}, matching the
     * documented contract.
     */
    @Test
    public void createSiteByKey_callbackReturnsFalse_whenAddSiteFailsWithJahiaException() throws Exception {
        // Arrange
        JCRCallback<Boolean> callback = captureCallback(SITE_KEY, "localhost", null,
                "Title", "not-installed-template-set", Collections.emptyList(), "en");
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        doThrow(jahiaException())
                .when(sitesService).addSite(any(SiteCreationInfo.class), any(JCRSessionWrapper.class));
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);

        // Act
        Boolean result;
        try (MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);
            result = callback.doInJCR(mock(JCRSessionWrapper.class));
        }

        // Assert
        assertThat(result).isFalse();
    }

    /**
     * The outer {@code catch (RepositoryException)}: if the system session itself cannot be
     * obtained, the mutation answers {@code false} rather than propagating a JCR exception to the
     * GraphQL layer, and nothing is created.
     */
    @Test
    public void createSiteByKey_returnsFalse_whenTheSystemSessionCannotBeObtained() throws Exception {
        // Arrange
        JCRTemplate jcrTemplate = mock(JCRTemplate.class);
        when(jcrTemplate.doExecuteWithSystemSession(any(JCRCallback.class)))
                .thenThrow(new RepositoryException("no session"));
        JahiaSitesService sitesService = mock(JahiaSitesService.class);
        ServicesRegistry registry = mock(ServicesRegistry.class);
        when(registry.getJahiaSitesService()).thenReturn(sitesService);

        try (MockedStatic<JCRTemplate> jcrTemplateStatic = mockStatic(JCRTemplate.class);
             MockedStatic<ServicesRegistry> registryStatic = mockStatic(ServicesRegistry.class)) {
            jcrTemplateStatic.when(JCRTemplate::getInstance).thenReturn(jcrTemplate);
            registryStatic.when(ServicesRegistry::getInstance).thenReturn(registry);

            // Act
            Boolean result = new WebsitesAdminMutation().createSiteByKey(
                    SITE_KEY, "localhost", null, "Title", "default", Collections.emptyList(), "en");

            // Assert
            assertThat(result).isFalse();
            verify(sitesService, never()).addSite(any(SiteCreationInfo.class), any(JCRSessionWrapper.class));
        }
    }
}
