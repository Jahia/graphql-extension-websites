package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PathSecurity}, the containment guard protecting the website
 * export/import mutations against path traversal from untrusted GraphQL input.
 */
public class PathSecurityTest {

    private final Path baseDir = Paths.get("/var/jahia/exports").toAbsolutePath().normalize();

    @Test
    public void resolveContained_simpleChild_returnsContainedPath() {
        // Arrange / Act
        Path resolved = PathSecurity.resolveContained(baseDir, "my-site");

        // Assert
        assertThat(resolved).isEqualTo(baseDir.resolve("my-site"));
        assertThat(PathSecurity.isContained(baseDir, resolved)).isTrue();
    }

    @Test
    public void resolveContained_nestedChild_returnsContainedPath() {
        Path resolved = PathSecurity.resolveContained(baseDir, "a/b/c");

        assertThat(resolved).isEqualTo(baseDir.resolve("a/b/c"));
    }

    @Test
    public void resolveContained_parentTraversal_isRejected() {
        assertThatThrownBy(() -> PathSecurity.resolveContained(baseDir, "../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed directory");
    }

    @Test
    public void resolveContained_traversalAfterValidPrefix_isRejected() {
        assertThatThrownBy(() -> PathSecurity.resolveContained(baseDir, "valid/../../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void resolveContained_absoluteEscape_isRejected() {
        // An absolute fragment replaces the base entirely when resolved.
        assertThatThrownBy(() -> PathSecurity.resolveContained(baseDir, "/etc/shadow"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void resolveContained_nullChild_isRejected() {
        assertThatThrownBy(() -> PathSecurity.resolveContained(baseDir, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    public void resolveContained_blankChild_isRejected() {
        assertThatThrownBy(() -> PathSecurity.resolveContained(baseDir, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null or blank");
    }

    @Test
    public void isContained_baseItself_isTrue() {
        assertThat(PathSecurity.isContained(baseDir, baseDir)).isTrue();
    }

    @Test
    public void isContained_siblingDirectory_isFalse() {
        Path sibling = Paths.get("/var/jahia/imports").toAbsolutePath().normalize();
        assertThat(PathSecurity.isContained(baseDir, sibling)).isFalse();
    }

    @Test
    public void isContained_prefixCollisionSibling_isFalse() {
        // /var/jahia/exports-other must NOT be considered inside /var/jahia/exports
        Path tricky = Paths.get("/var/jahia/exports-other/file").toAbsolutePath().normalize();
        assertThat(PathSecurity.isContained(baseDir, tricky)).isFalse();
    }
}
