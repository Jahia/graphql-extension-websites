package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PathSecurity}, the containment guard protecting the website
 * export/import mutations against path traversal from untrusted GraphQL input.
 *
 * <p><b>Which half of the guard a test exercises must not depend on the developer's filesystem.</b>
 * {@code PathSecurity} is two layers: a lexical {@code normalize()} check, and a symlink-aware
 * real-path check gated on {@code Files.exists(baseDir)}. The lexical group below therefore uses
 * {@link #baseDir}, a path chosen so it cannot exist on any machine — this used to be
 * {@code /var/jahia/exports}, which <em>does</em> exist on a developer box with a real Jahia
 * install, so those tests silently exercised different code there than in CI. (The same
 * {@code /var/jahia-unit-test} convention is already used deliberately by
 * {@code WebsitesAdminMutationExportScopeTest}.)
 *
 * <p>The real-path layer is covered separately and explicitly, by the tests that build a base
 * directory with {@link TemporaryFolder} — including
 * {@link #resolveContained_parentTraversal_isRejected_whenTheBaseExistsOnDisk()}, which repeats a
 * lexical case against an existing base so the two layers are known to agree.
 */
public class PathSecurityTest {

    /**
     * Deliberately a path no machine has: it keeps the tests below on the lexical layer only,
     * whatever is installed locally. Do not point this at a real Jahia var directory.
     */
    private final Path baseDir = Paths.get("/var/jahia-unit-test/exports").toAbsolutePath().normalize();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // -------------------------------------------------------------------------
    // Original lexical tests (must continue to pass unchanged)
    // -------------------------------------------------------------------------

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
        Path sibling = Paths.get("/var/jahia-unit-test/imports").toAbsolutePath().normalize();
        assertThat(PathSecurity.isContained(baseDir, sibling)).isFalse();
    }

    @Test
    public void isContained_prefixCollisionSibling_isFalse() {
        // A sibling whose name merely STARTS WITH the base name must not count as inside it —
        // the classic startsWith(String) bug. Keep this path a real sibling of baseDir.
        Path tricky = Paths.get("/var/jahia-unit-test/exports-other/file").toAbsolutePath().normalize();
        assertThat(PathSecurity.isContained(baseDir, tricky)).isFalse();
    }

    /**
     * Guards the guard: if {@link #baseDir} ever starts existing, every lexical test above quietly
     * changes meaning, because {@code resolveContained} would begin applying its real-path layer
     * as well.
     */
    @Test
    public void theLexicalBaseDirectoryDoesNotExistOnDisk() {
        assertThat(baseDir)
                .as("the lexical tests assume PathSecurity skips its symlink-aware layer; that "
                        + "only holds while this base does not exist on the machine running them")
                .doesNotExist();
    }

    /**
     * The same traversal, rejected against a base that <em>does</em> exist — so the refusal is
     * known to hold on both sides of the {@code Files.exists(baseDir)} branch rather than being
     * decided by the filesystem.
     */
    @Test
    public void resolveContained_parentTraversal_isRejected_whenTheBaseExistsOnDisk() throws IOException {
        Path realBase = tmp.newFolder("exports").toPath().toRealPath();

        assertThatThrownBy(() -> PathSecurity.resolveContained(realBase, "../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed directory");
    }

    /** Likewise for an absolute escape, with the real-path layer active. */
    @Test
    public void resolveContained_absoluteEscape_isRejected_whenTheBaseExistsOnDisk() throws IOException {
        Path realBase = tmp.newFolder("exports").toPath().toRealPath();

        assertThatThrownBy(() -> PathSecurity.resolveContained(realBase, "/etc/shadow"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Item K: new cases for symlink hardening, null-byte, dot-only, new file
    // -------------------------------------------------------------------------

    /**
     * A symlink placed inside the base directory that points to an outside target must
     * be rejected.  The test is skipped gracefully if the filesystem or OS does not
     * support symbolic links (e.g. some Windows CI environments).
     */
    @Test
    public void resolveContained_symlinkInsideBasePointingOutside_isRejected() throws IOException {
        // Arrange: set up a real temp directory as the "base"
        File baseFile = tmp.newFolder("exports");
        Path realBase = baseFile.toPath().toRealPath();

        // Create the outside target directory that the symlink will point to
        File outsideTarget = tmp.newFolder("outside");

        // Attempt to create a symlink inside the base pointing at the outside dir
        Path symlinkPath = realBase.resolve("escape-link");
        try {
            Files.createSymbolicLink(symlinkPath, outsideTarget.toPath().toRealPath());
        } catch (UnsupportedOperationException | IOException e) {
            // Filesystem does not support symlinks — skip this test
            Assume.assumeNoException("Skipping: filesystem does not support symlinks", e);
        }

        // Act / Assert: resolveContained must reject "escape-link" because its real path
        // lands outside the real base
        assertThatThrownBy(() -> PathSecurity.resolveContained(realBase, "escape-link"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A child containing a null byte must be rejected (C-string truncation attack).
     */
    @Test
    public void resolveContained_nullByteInChild_isRejected() {
        assertThatThrownBy(() -> PathSecurity.resolveContained(baseDir, "valid\0/../../escape"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null bytes");
    }

    /**
     * A child of "." resolves to the base directory itself.
     * Expected behaviour: ALLOWED — the caller receives the base dir path.
     * Rationale: "." is a valid relative path meaning "current directory" (the base),
     * and {@code base.resolve(".").normalize()} == {@code base}.  No traversal occurs.
     */
    @Test
    public void resolveContained_dotOnlyChild_resolvesToBase() throws IOException {
        File baseFile = tmp.newFolder("exports");
        Path realBase = baseFile.toPath().toRealPath();

        Path resolved = PathSecurity.resolveContained(realBase, ".");

        assertThat(resolved).isEqualTo(realBase);
        assertThat(PathSecurity.isContained(realBase, resolved)).isTrue();
    }

    /**
     * A path fragment that names a file that does NOT yet exist (it will be created later
     * by the export) must still be ALLOWED — the real-path canonicalization must not
     * break creation of new files under the base.
     */
    @Test
    public void resolveContained_newNonExistentFileUnderBase_isAllowed() throws IOException {
        // Arrange: a real base that exists on disk
        File baseFile = tmp.newFolder("exports");
        Path realBase = baseFile.toPath().toRealPath();

        // The target file does not exist yet
        String newFileName = "new-export-20991231.zip";

        // Act / Assert: must NOT throw
        Path resolved = PathSecurity.resolveContained(realBase, newFileName);

        assertThat(resolved).isEqualTo(realBase.resolve(newFileName));
        assertThat(PathSecurity.isContained(realBase, resolved)).isTrue();
    }

    /**
     * {@link PathSecurity#toRealPathOfExistingAncestor} returns the real path of the
     * nearest existing ancestor when the full path does not exist.
     */
    @Test
    public void toRealPathOfExistingAncestor_nonExistentChild_returnsRealParent() throws IOException {
        File baseFile = tmp.newFolder("exports");
        Path realBase = baseFile.toPath().toRealPath();
        Path nonExistent = realBase.resolve("does-not-exist.zip");

        Path ancestor = PathSecurity.toRealPathOfExistingAncestor(nonExistent);

        // The existing ancestor is the base dir itself
        assertThat(ancestor).isEqualTo(realBase);
    }
}
