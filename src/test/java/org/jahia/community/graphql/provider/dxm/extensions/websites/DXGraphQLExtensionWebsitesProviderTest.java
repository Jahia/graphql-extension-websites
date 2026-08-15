package org.jahia.community.graphql.provider.dxm.extensions.websites;

import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLExtensionsProvider;
import org.jahia.modules.graphql.provider.dxm.admin.GqlJahiaAdminMutation;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how the module attaches itself to the DXM GraphQL schema.
 *
 * <p>{@link DXGraphQLExtensionWebsitesProvider#getExtensions()} and
 * {@link WebsitesMutation#websites()} were both 0% covered, and they are the entire wiring: drop
 * {@code WebsitesMutation.class} from the returned list, or remove the
 * {@code @GraphQLTypeExtension(GqlJahiaAdminMutation.class)}, and the whole GraphQL API of this
 * module vanishes at runtime. Nothing in the Java suite failed; only a Cypress run against a live
 * instance would have noticed, and only if it happened to call a mutation.
 *
 * <p>The container name is pinned for the same reason. Every mutation lives at
 * {@code admin.jahia.websites.<operation>} — a flat {@code admin.jahia.<operation>} path does not
 * resolve — so renaming the {@code @GraphQLName("websites")} field silently breaks every existing
 * client query while the schema still builds cleanly. That grouping is also what keeps this module
 * from colliding with another bundle's root fields.
 */
public class DXGraphQLExtensionWebsitesProviderTest {

    @Test
    public void theProviderRegistersExactlyTheWebsitesMutationContainer() {
        // Arrange
        DXGraphQLExtensionsProvider provider = new DXGraphQLExtensionWebsitesProvider();

        // Act
        Collection<Class<?>> extensions = provider.getExtensions();

        // Assert
        assertThat(extensions)
                .as("dropping WebsitesMutation from this list removes the module's entire GraphQL "
                        + "API at runtime, with no compilation or unit-test failure")
                .containsExactly(WebsitesMutation.class);
    }

    @Test
    public void theContainerExtendsTheJahiaAdminMutationType() {
        GraphQLTypeExtension extension = WebsitesMutation.class.getAnnotation(GraphQLTypeExtension.class);

        assertThat(extension)
                .as("without @GraphQLTypeExtension the container is never attached to the schema")
                .isNotNull();
        assertThat(extension.value())
                .as("the mutations must hang off GqlJahiaAdminMutation, i.e. admin.jahia.websites.*")
                .isEqualTo(GqlJahiaAdminMutation.class);
    }

    @Test
    public void theContainerFieldIsNamedWebsites() throws Exception {
        Method websites = WebsitesMutation.class.getDeclaredMethod("websites");

        assertThat(websites.getAnnotation(GraphQLField.class))
                .as("without @GraphQLField the namespace field is not exposed at all")
                .isNotNull();
        assertThat(websites.getAnnotation(GraphQLName.class))
                .isNotNull()
                .extracting(GraphQLName::value)
                .as("renaming this field breaks every existing admin.jahia.websites.* query while "
                        + "the schema still builds")
                .isEqualTo("websites");
        assertThat(Modifier.isStatic(websites.getModifiers()))
                .as("graphql-java-annotations resolves the container through a static accessor here")
                .isTrue();
    }

    @Test
    public void theContainerReturnsTheMutationHolder() {
        // Act
        WebsitesAdminMutation container = WebsitesMutation.websites();

        // Assert — a fresh holder per call; the mutations themselves are stateless
        assertThat(container).isNotNull();
        assertThat(WebsitesMutation.websites())
                .as("the accessor must not hand out shared mutable state between requests")
                .isNotSameAs(container);
    }
}
