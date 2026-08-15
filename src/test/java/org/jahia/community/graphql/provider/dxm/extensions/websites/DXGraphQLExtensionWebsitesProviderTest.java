package org.jahia.community.graphql.provider.dxm.extensions.websites;

import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLExtensionsProvider;
import org.jahia.modules.graphql.provider.dxm.admin.GqlJahiaAdminMutation;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

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

    /**
     * The accessor must hand back a usable holder — returning {@code null} would build a valid
     * schema whose every {@code admin.jahia.websites.*} field resolves to nothing.
     *
     * <p>Deliberately <em>not</em> asserted: that each call returns a distinct instance. The real
     * concern is shared mutable state between requests, and that is pinned directly by
     * {@link #theMutationHolderCarriesNoInstanceState()}. Because the holder has no instance state,
     * returning a cached singleton would be a legitimate optimisation, and an identity assertion
     * here would reject it while adding no safety of its own.
     */
    @Test
    public void theContainerReturnsTheMutationHolder() {
        // Act
        WebsitesAdminMutation container = WebsitesMutation.websites();

        // Assert
        assertThat(container)
                .as("a null container makes every mutation under admin.jahia.websites unresolvable "
                        + "while the schema still builds cleanly")
                .isNotNull();
    }

    /**
     * The property that makes the container safe to share across concurrent GraphQL requests,
     * asserted as itself rather than through the proxy of instance identity: the holder declares no
     * instance fields at all, so two requests resolving the same field cannot interfere.
     *
     * <p>Adding one — a cached {@code SettingsBean}, a per-call export path, a reused S3 client —
     * would make the lifetime of the object suddenly load-bearing, and mutations are dispatched
     * concurrently. If this fails, either drop the field or make the sharing model explicit before
     * relaxing the test.
     */
    @Test
    public void theMutationHolderCarriesNoInstanceState() {
        List<String> instanceFields = Arrays.stream(WebsitesAdminMutation.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                // Coverage and mocking agents synthesise fields into the class; they are not state
                // the module wrote and must not fail an otherwise-clean build.
                .filter(field -> !field.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toList());

        assertThat(instanceFields)
                .as("the websites mutations are dispatched concurrently and the container is created "
                        + "per field resolution; per-instance state would be shared or lost "
                        + "depending on a lifetime nothing in the schema guarantees")
                .isEmpty();
    }
}
