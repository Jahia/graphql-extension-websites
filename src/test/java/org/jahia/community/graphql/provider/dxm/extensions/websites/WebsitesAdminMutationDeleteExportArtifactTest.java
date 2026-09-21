package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.junit.After;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Behaviour of {@code WebsitesAdminMutation.deleteExportArtifact(Path, String)} — the shared
 * cleanup helper used by both export mutations, and 0/6 lines covered before this class.
 *
 * <p>It replaced a bare {@code FileUtils.deleteQuietly}, which discarded a failed delete entirely
 * and let stale archives accumulate in the exports directory with no trace. The replacement keeps
 * two properties in tension, and both are easy to lose in a "simplification":
 *
 * <ul>
 *   <li>a file that was <b>never created</b> is not a failure — it returns early, so a routine
 *       cleanup after an export that never got as far as writing anything stays silent;</li>
 *   <li>a delete that <b>fails</b> is reported (a warning) but must <em>not</em> fail the
 *       surrounding mutation — the export itself may well have succeeded.</li>
 * </ul>
 *
 * <p><b>What is asserted, and what is not.</b> There is no SLF4J binding on the test classpath
 * (slf4j-api is {@code provided} and no implementation is a declared dependency), so the warning
 * itself cannot be captured without adding a logging dependency. What is asserted is everything
 * observable: the early return, the successful deletes, and — the part that actually matters to a
 * caller — that a failed delete neither throws nor removes anything. The end-to-end deletion
 * behaviour through the mutation is covered by
 * {@link WebsitesAdminMutationExportDeletionGuardTest}.
 *
 * <p>The method is private, so it is reached reflectively. That is deliberate: exercising it
 * through {@code exportWebsite} cannot reach the "delete failed" branch, because making the
 * exports directory read-only would also stop the export from being set up.
 */
public class WebsitesAdminMutationDeleteExportArtifactTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * Set only once a directory has actually been made read-only, so
     * {@link #restoreWriteAccessOrFailLoudly()} knows there is something to undo.
     */
    private File lockedDir;

    /**
     * Gives write access back so {@link TemporaryFolder} can delete the tree, and fails the run
     * loudly — naming the directory — if the JVM says it could not.
     *
     * <p><b>Why here and not in a {@code finally} block.</b> An assertion inside {@code finally}
     * runs while the test's own exception is in flight and would <em>replace</em> it, hiding the
     * real defect behind a cleanup complaint. JUnit 4 runs {@code @After} through
     * {@code RunAfters}, which collects what is thrown here <em>alongside</em> the body's failure
     * (a {@code MultipleFailureException}) instead of substituting it — so both are reported and
     * neither is lost. The rule wraps the {@code @After}, so this still runs before the folder is
     * deleted. Simply discarding the boolean, as this test used to, turns a failed restore into an
     * opaque {@code TemporaryFolder} teardown error pointing at the wrong thing entirely.
     */
    @After
    public void restoreWriteAccessOrFailLoudly() {
        if (lockedDir == null) {
            return;
        }
        File dir = lockedDir;
        lockedDir = null;
        if (!dir.setWritable(true, false)) {
            throw new IllegalStateException("Could not restore write access to " + dir
                    + "; TemporaryFolder cannot clean it up and later tests may see a stale tree");
        }
    }

    /**
     * Invokes the helper with the artifact's own parent as {@code protectedBase}, i.e. the
     * ordinary case where the artifact sits inside the exports directory. The base-equality
     * refusal itself is exercised by
     * {@link #deleteExportArtifact_refusesToDeleteTheProtectedBaseItself()}.
     */
    private static void deleteExportArtifact(Path path) throws Exception {
        deleteExportArtifact(path, path.getParent());
    }

    private static void deleteExportArtifact(Path path, Path protectedBase) throws Exception {
        Method method = WebsitesAdminMutation.class
                .getDeclaredMethod("deleteExportArtifact", Path.class, Path.class, String.class);
        method.setAccessible(true);
        method.invoke(null, path, protectedBase, "unit-test");
    }

    /** A path that was never written must be a no-op, not an error and not a fresh file. */
    @Test
    public void deleteExportArtifact_ignoresAFileThatWasNeverCreated() throws Exception {
        // Arrange
        Path missing = tmp.getRoot().toPath().resolve("export-never-written.zip");

        // Act + Assert
        assertThatCode(() -> deleteExportArtifact(missing)).doesNotThrowAnyException();
        assertThat(missing)
                .as("cleanup must not have the side effect of creating what it was asked to remove")
                .doesNotExist();
    }

    @Test
    public void deleteExportArtifact_removesAnExistingArchive() throws Exception {
        // Arrange
        File archive = tmp.newFile("export-20260814090703-0123abcd.zip");

        // Act
        deleteExportArtifact(archive.toPath());

        // Assert
        assertThat(archive).doesNotExist();
    }

    /** The single-site export writes a directory tree, not a file; it must be removed whole. */
    @Test
    public void deleteExportArtifact_removesAnExistingDirectoryTree() throws Exception {
        // Arrange
        File tree = tmp.newFolder("previous-export");
        Path nested = tree.toPath().resolve("nested/content.xml");
        Files.createDirectories(nested.getParent());
        Files.write(nested, "<x/>".getBytes("UTF-8"));

        // Act
        deleteExportArtifact(tree.toPath());

        // Assert
        assertThat(tree).doesNotExist();
    }

    /**
     * The reported-but-not-fatal branch. A failed cleanup must leave the caller running: the
     * export it followed may have succeeded, and turning a cleanup problem into a mutation failure
     * would be a regression in its own right.
     *
     * <p>Delete failure is provoked with a read-only parent directory, which on POSIX prevents
     * unlinking the entry. Skipped when the JVM cannot make the directory read-only or is running
     * as root, where the permission bits do not apply.
     */
    /**
     * SEC-363. The helper's last-line guard: whatever the caller worked out, it must never
     * recursively delete the directory it was told to protect.
     *
     * <p>This is deliberately redundant with the refusal in {@code exportWebsite}, and the
     * redundancy is the point. The live incident was one contained-but-equal path
     * ({@code exportPath: "."}) reaching {@code FileUtils.deleteQuietly}, which on a directory
     * deletes recursively; every site's archives went with it and the mutation still returned
     * {@code true}. A single check at one call site is one edit away from being gone.
     *
     * <p>Note the assertion is on the <em>contents</em>, not just the directory: an earlier
     * shape of this bug emptied the base and left the now-empty directory in place, so
     * asserting only {@code exists()} on the base would pass against the vulnerable code.
     */
    @Test
    public void deleteExportArtifact_refusesToDeleteTheProtectedBaseItself() throws Exception {
        // Arrange — an exports base standing in for a shared directory holding other tenants' archives
        File exportsDir = tmp.newFolder("exports");
        File otherTenantArchive = new File(exportsDir, "tenantB.zip");
        Files.write(otherTenantArchive.toPath(), "another tenant's backup".getBytes("UTF-8"));

        // Act — path and protectedBase are the same directory, as "." resolves them
        assertThatCode(() -> deleteExportArtifact(exportsDir.toPath(), exportsDir.toPath()))
                .as("a refusal is logged, never thrown — this also runs from a finally block")
                .doesNotThrowAnyException();

        // Assert
        assertThat(otherTenantArchive)
                .as("deleting the exports base is recursive: it destroys archives belonging to "
                        + "sites the caller never named (SEC-363)")
                .exists();
        assertThat(exportsDir).exists();
    }

    /**
     * The counterpart: an artifact that merely lives <em>under</em> the protected base is still
     * deleted. Without this, the guard above could be satisfied by refusing everything, and the
     * idempotent re-export that {@code deleteExportArtifact} exists to enable would break.
     */
    @Test
    public void deleteExportArtifact_stillRemovesAnArtifactNestedUnderTheProtectedBase() throws Exception {
        // Arrange
        File exportsDir = tmp.newFolder("exports-nested");
        File previousExport = new File(exportsDir, "previous-export");
        Files.createDirectories(previousExport.toPath().resolve("nested"));

        // Act
        deleteExportArtifact(previousExport.toPath(), exportsDir.toPath());

        // Assert
        assertThat(previousExport).doesNotExist();
        assertThat(exportsDir).exists();
    }

    @Test
    public void deleteExportArtifact_survivesAFailedDeleteWithoutThrowingOrRemovingAnything() throws Exception {
        // Arrange
        File exportsDir = tmp.newFolder("read-only-exports");
        File archive = new File(exportsDir, "export-stuck.zip");
        Files.write(archive.toPath(), "content".getBytes("UTF-8"));
        Assume.assumeFalse("Skipping: running as root, permission bits do not prevent unlink",
                "root".equals(System.getProperty("user.name")));
        Assume.assumeTrue("Skipping: filesystem does not support a read-only directory",
                exportsDir.setWritable(false, false));
        // Registered only now that the bits really changed; restoreWriteAccessOrFailLoudly() undoes
        // it whether this test passes, fails or throws — see that method for why not a finally.
        lockedDir = exportsDir;

        // Act + Assert — the failure is logged, never propagated
        assertThatCode(() -> deleteExportArtifact(archive.toPath()))
                .as("a cleanup failure must not fail the surrounding export mutation")
                .doesNotThrowAnyException();
        assertThat(archive)
                .as("the artifact really was undeletable, so this exercised the warning branch "
                        + "rather than the happy path")
                .exists();
    }
}
