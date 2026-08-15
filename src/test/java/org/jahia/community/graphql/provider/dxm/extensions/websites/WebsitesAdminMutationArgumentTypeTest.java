package org.jahia.community.graphql.provider.dxm.extensions.websites;

import graphql.annotations.annotationTypes.GraphQLField;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards against the argument-binding defect that made {@code createSiteByKey} unusable whenever
 * {@code modulesToDeploy} was supplied.
 *
 * <h3>The defect</h3>
 *
 * <p>{@code modulesToDeploy} was declared {@code String[]}. Calling the mutation with that
 * argument present — even as an empty list {@code []} — failed with
 * {@code IllegalArgumentException: argument type mismatch} for <em>every</em> caller, root
 * included. Omitting the argument worked, because the parameter was then simply {@code null} and
 * no conversion was attempted.
 *
 * <h3>Why</h3>
 *
 * <p>graphql-java-annotations converts a GraphQL list argument only when the Java parameter is a
 * parameterized {@code List<T>}. {@code MethodDataFetcher.buildArg} guards that conversion on
 * {@code instanceof ParameterizedType && instanceof GraphQLList}. An array parameter is a plain
 * {@code Class}, so it misses the branch and the raw {@code ArrayList} is handed to reflective
 * {@code Method.invoke} — which rejects it.
 *
 * <h3>Why this was not caught earlier</h3>
 *
 * <p>The Cypress fixture {@code createSiteByKey.graphql} does not declare {@code modulesToDeploy},
 * and no spec passed it. Every green end-to-end run exercised only the omitted-argument path, which
 * is precisely the one that worked. A schema-level test is therefore not enough on its own —
 * coverage has to include the argument actually being present.
 */
public class WebsitesAdminMutationArgumentTypeTest {

    /**
     * The regression lock. An array parameter on any exposed mutation reintroduces the defect,
     * and the failure only shows up at runtime with the argument populated — so it must be caught
     * structurally here rather than hoped for in an end-to-end run.
     */
    @Test
    public void noGraphQLFieldDeclaresAnArrayParameter() {
        List<String> offenders = Arrays.stream(WebsitesAdminMutation.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(GraphQLField.class) != null)
                .flatMap(m -> Arrays.stream(m.getParameters())
                        .filter(p -> p.getType().isArray())
                        .map(p -> m.getName() + "(" + p.getType().getSimpleName() + " " + p.getName() + ")"))
                .collect(Collectors.toList());

        assertThat(offenders)
                .as("graphql-java-annotations cannot bind a GraphQL list to a Java array parameter; "
                        + "use List<T> and convert. See toModulesArray().")
                .isEmpty();
    }

    /** {@code modulesToDeploy} specifically must be a {@code List}, not an array. */
    @Test
    public void createSiteByKey_takesModulesToDeployAsAList() throws Exception {
        Parameter modules = Arrays.stream(WebsitesAdminMutation.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("createSiteByKey"))
                .flatMap(m -> Arrays.stream(m.getParameters()))
                .filter(p -> {
                    graphql.annotations.annotationTypes.GraphQLName n =
                            p.getAnnotation(graphql.annotations.annotationTypes.GraphQLName.class);
                    return n != null && "modulesToDeploy".equals(n.value());
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("createSiteByKey has no modulesToDeploy argument"));

        assertThat(modules.getType()).isEqualTo(List.class);
        assertThat(modules.getType().isArray()).isFalse();
    }

    /**
     * Characterizes the underlying JVM behaviour the library runs into, so the reason for the
     * {@code List} parameter is demonstrated rather than merely asserted: passing an
     * {@code ArrayList} where the method expects {@code String[]} is exactly what produces
     * {@code IllegalArgumentException: argument type mismatch}.
     */
    @Test
    public void reflectiveInvokeRejectsAListWhereAnArrayIsDeclared() throws Exception {
        Method arrayParam = Probe.class.getDeclaredMethod("takesArray", String[].class);
        Method listParam = Probe.class.getDeclaredMethod("takesList", List.class);
        Probe probe = new Probe();
        List<String> value = new ArrayList<>(Arrays.asList("a", "b"));

        // What graphql-java-annotations effectively did with String[]
        assertThatThrownBy(() -> arrayParam.invoke(probe, value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("argument type mismatch");

        // What it does with List<String> — the shape the mutation now uses
        assertThat(listParam.invoke(probe, value)).isEqualTo(2);
    }

    /** Stand-in for the two parameter shapes; keeps the characterization independent of Jahia. */
    @SuppressWarnings("unused")
    private static final class Probe {
        int takesArray(String[] values) {
            return values.length;
        }

        int takesList(List<String> values) {
            return values.size();
        }
    }

    /** The adapter must preserve "argument omitted" as null rather than inventing an empty array. */
    @Test
    public void toModulesArray_mapsNullAndEmptyDistinctly() throws Exception {
        Method adapter = WebsitesAdminMutation.class.getDeclaredMethod("toModulesArray", List.class);
        adapter.setAccessible(true);

        assertThat((String[]) adapter.invoke(null, (Object) null))
                .as("omitting the argument must behave exactly as before the fix")
                .isNull();
        assertThat((String[]) adapter.invoke(null, new ArrayList<String>())).isEmpty();
        assertThat((String[]) adapter.invoke(null, Arrays.asList("a", "b")))
                .containsExactly("a", "b");
    }
}
