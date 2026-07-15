package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.services.content.JCRCallback;
import org.jahia.services.content.JCRTemplate;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization test for {@link WebsitesAdminMutation#createSiteByKey} (U6).
 *
 * <p>Site creation runs under a JCR <em>system</em> session, not the caller's session:
 * the body wraps {@code addSite(...)} in
 * {@code JCRTemplate.getInstance().doExecuteWithSystemSession(...)}. This is not observable
 * end-to-end (root and system both succeed), so it is pinned here with a static mock of the
 * {@link JCRTemplate} singleton. If a refactor switches to a caller-scoped
 * {@code doExecute(...)} this test fails.
 */
public class WebsitesAdminMutationCreateSiteTest {

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
                    "cypress-test", "localhost", null, "Title", "default", new String[0], "en");

            // Assert
            assertThat(result).isTrue();
            verify(jcrTemplate, times(1)).doExecuteWithSystemSession(any(JCRCallback.class));
        }
    }
}
