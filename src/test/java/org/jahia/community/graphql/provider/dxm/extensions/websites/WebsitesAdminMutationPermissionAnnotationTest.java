package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link GraphQLRequiresPermission} value on every mutation.
 *
 * <p>These annotations are the only root-level authorization the module has, and nothing else in
 * the test suite reads them: the Cypress specs exercise behaviour through a live instance, so a
 * changed annotation shows up there only if a spec happens to cover that exact user/permission
 * combination. This class asserts the wiring directly and cheaply.
 *
 * <p>Two failure modes are worth naming, because both look like tidying up:
 *
 * <ul>
 *   <li><b>"Harmonising" the split back to a single {@code websitesAdmin}.</b> That would undo
 *       the per-operation delegation — a holder allowed to create sites would regain bulk
 *       instance export.</li>
 *   <li><b>Changing {@link WebsitesAdminMutation#deleteSiteByKey}'s annotation to
 *       {@code websitesDelete}.</b> This reads like an obvious consistency fix and is the most
 *       dangerous edit in the file. The annotation is evaluated at the repository <em>root</em>,
 *       and {@code websitesDelete} is deliberately never granted there — it is carried by the
 *       site-scoped role. Making that change would deny every site-scoped holder before the body
 *       ran, breaking the delegation the SEC-136 fix exists to enable, while still <em>looking</em>
 *       stricter. {@link #deleteSiteByKey_annotationIsTheCoarseGate_notTheTargetScopedOne()}
 *       exists solely to stop it.</li>
 * </ul>
 */
public class WebsitesAdminMutationPermissionAnnotationTest {

    /**
     * Resolves a mutation by name, restricted to {@code @GraphQLField} methods.
     *
     * <p>The {@code @GraphQLField} filter is required, not cosmetic. Several names are overloaded
     * between the exposed mutation and a private helper — {@code exportAllSites()} the mutation
     * versus {@code exportAllSites(Path)} the worker. {@link Class#getDeclaredMethods()} has no
     * specified ordering, so matching on name alone picked one or the other at random between
     * runs and this class failed intermittently with "must carry @GraphQLRequiresPermission".
     */
    private static String permissionOn(String methodName) {
        List<Method> candidates = Arrays.stream(WebsitesAdminMutation.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .filter(m -> m.getAnnotation(graphql.annotations.annotationTypes.GraphQLField.class) != null)
                .collect(Collectors.toList());

        assertThat(candidates)
                .as("exactly one @GraphQLField method should be named %s", methodName)
                .hasSize(1);
        Method method = candidates.get(0);
        GraphQLRequiresPermission annotation = method.getAnnotation(GraphQLRequiresPermission.class);
        assertThat(annotation)
                .as("%s must carry @GraphQLRequiresPermission — an unannotated mutation is ungated", methodName)
                .isNotNull();
        return annotation.value();
    }

    @Test
    public void eachMutationCarriesItsOwnPermission() {
        assertThat(permissionOn("createSiteByKey")).isEqualTo("websitesCreate");
        assertThat(permissionOn("exportAllSites")).isEqualTo("websitesExportAll");
    }

    /**
     * The load-bearing ones. See the class javadoc: naming the fine permission at the annotation
     * level would evaluate it at {@code /}, where it is intentionally never granted.
     */
    @Test
    public void deleteSiteByKey_annotationIsTheCoarseGate_notTheTargetScopedOne() {
        assertThat(permissionOn("deleteSiteByKey"))
                .as("deleteSiteByKey's annotation is evaluated at the repository root; "
                        + "websitesDelete is granted per site, so annotating it there would deny "
                        + "every site-scoped holder. The target-scoped check belongs in the body.")
                .isEqualTo("websitesAdmin")
                .isNotEqualTo("websitesDelete");
    }

    /**
     * Same trap as deletion, and easier to fall into because the fine permission shares its name
     * with the operation. {@code websitesExport} lives only on the site-scoped role.
     */
    @Test
    public void exportWebsite_annotationIsTheCoarseGate_notTheTargetScopedOne() {
        assertThat(permissionOn("exportWebsite"))
                .as("exportWebsite's annotation is evaluated at the repository root; "
                        + "websitesExport is granted per site, so annotating it there would deny "
                        + "every site administrator. The target-scoped check belongs in the body.")
                .isEqualTo("websitesAdmin")
                .isNotEqualTo("websitesExport");
    }

    /** importWebsite's real gate is {@code callerIsServerAdministrator()} in the body. */
    @Test
    public void importWebsite_keepsTheCoarseGate() {
        assertThat(permissionOn("importWebsite")).isEqualTo("websitesAdmin");
    }

    /**
     * The split is only meaningful if the root-delegable operations do not collapse onto one
     * permission. If a future change points creation and bulk export at the same value,
     * delegating one would silently delegate the other.
     *
     * <p>{@code exportWebsite} is deliberately excluded: it shares the coarse
     * {@code websitesAdmin} annotation with delete and import, and its independence comes from
     * the per-site {@code websitesExport} check in the body instead.
     */
    @Test
    public void theRootGatedOperationsDoNotShareOnePermission() {
        Set<String> distinct = new HashSet<>(Arrays.asList(
                permissionOn("createSiteByKey"),
                permissionOn("exportAllSites")));

        assertThat(distinct)
                .as("creation and bulk export must remain independently delegable")
                .hasSize(2);
    }

    /**
     * The surface itself is pinned, not just the gating of it.
     *
     * <p>{@link #everyGraphQLFieldIsGated()} catches a new mutation that carries no annotation at
     * all — but not the likelier mistake: a new mutation annotated with an existing coarse
     * permission, most plausibly {@code websitesAdmin} copied from the method above it. That is
     * exactly how a delegated role silently acquires a capability nobody decided to delegate.
     *
     * <p>There is no way to detect "this permission is too coarse for what the method does" from
     * an annotation, so the surface is fixed instead: adding or removing a mutation fails here and
     * forces a conscious decision about which permission it should carry, and whether it needs an
     * in-body target-scoped check as well.
     */
    @Test
    public void theMutationSurfaceIsExactlyTheFiveKnownOperations() {
        Set<String> mutations = Arrays.stream(WebsitesAdminMutation.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(graphql.annotations.annotationTypes.GraphQLField.class) != null)
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(mutations)
                .as("a new @GraphQLField mutation must be a deliberate act: pick its permission, "
                        + "decide whether it acts on a specific site (if so it needs an in-body "
                        + "check — the annotation is evaluated at '/' and cannot express a target), "
                        + "and update this list")
                .containsExactlyInAnyOrder("createSiteByKey", "deleteSiteByKey", "exportWebsite",
                        "importWebsite", "exportAllSites");
    }

    /** Every public mutation is gated; none may be added without a permission. */
    @Test
    public void everyGraphQLFieldIsGated() {
        Set<String> ungated = Arrays.stream(WebsitesAdminMutation.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(graphql.annotations.annotationTypes.GraphQLField.class) != null)
                .filter(m -> m.getAnnotation(GraphQLRequiresPermission.class) == null)
                .map(Method::getName)
                .collect(Collectors.toSet());

        assertThat(ungated)
                .as("every @GraphQLField mutation must carry @GraphQLRequiresPermission")
                .isEmpty();
    }
}
