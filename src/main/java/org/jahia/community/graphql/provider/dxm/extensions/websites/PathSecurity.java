package org.jahia.community.graphql.provider.dxm.extensions.websites;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Path containment helpers shared by the website export/import mutations.
 *
 * <p>All user-supplied path fragments coming from GraphQL input (export path, import path,
 * site key) are untrusted and may contain {@code ..} segments, absolute escapes, or null
 * bytes. These helpers resolve a child against a trusted base directory and verify that the
 * canonical result stays inside that base, rejecting any traversal attempt.
 *
 * <h3>Two-layer containment check</h3>
 * <ol>
 *   <li><b>Lexical check</b> – {@link Path#normalize()} removes {@code ..} segments and is
 *       fast, but does <em>not</em> resolve symbolic links.</li>
 *   <li><b>Real-path check</b> – resolves the real filesystem path of the deepest existing
 *       ancestor of the candidate (walking up until a path component actually exists on
 *       disk), then verifies that real path still starts with the real base. This catches
 *       symlinks placed inside the base directory that point outside it. Export paths may
 *       not yet exist (they will be created by the export), so we canonicalize the nearest
 *       existing ancestor rather than the full path. The real-path layer is only applied
 *       when the base directory itself exists on disk; if it does not exist the lexical
 *       check alone is used (unit-test / early-startup scenario).</li>
 * </ol>
 */
final class PathSecurity {

    private PathSecurity() {
        // utility class
    }

    /**
     * Resolves {@code child} against {@code baseDir} and verifies the normalized result is
     * contained within {@code baseDir}, using both a lexical check and (when the base
     * exists on disk) a real-path symlink-aware check.
     *
     * @param baseDir trusted, already-normalized absolute base directory
     * @param child   untrusted relative fragment from external input (may be {@code null})
     * @return the normalized, contained absolute path
     * @throws IllegalArgumentException if {@code child} is null/blank, contains a null byte,
     *                                  or escapes {@code baseDir}
     */
    static Path resolveContained(Path baseDir, String child) {
        if (child == null || child.trim().isEmpty()) {
            throw new IllegalArgumentException("Path fragment must not be null or blank");
        }
        // Reject null bytes (C-string truncation attack on some JVM/OS combos)
        if (child.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Path fragment must not contain null bytes");
        }
        final Path resolved = baseDir.resolve(child).normalize();

        // Layer 1: lexical containment check
        if (!isContained(baseDir, resolved)) {
            throw new IllegalArgumentException(
                    "Path fragment '" + child + "' resolves outside the allowed directory");
        }

        // Layer 2: real-path (symlink-aware) check.
        // Only applied when the base directory actually exists on disk; skipped otherwise
        // (e.g. in unit tests using non-existent paths like /var/jahia/exports).
        if (Files.exists(baseDir)) {
            try {
                final Path realBase = baseDir.toRealPath();
                final Path realAncestor = toRealPathOfExistingAncestor(resolved);
                if (!realAncestor.startsWith(realBase)) {
                    throw new IllegalArgumentException(
                            "Path fragment '" + child + "' resolves outside the allowed directory (symlink escape detected)");
                }
            } catch (IOException ex) {
                // If we cannot canonicalize, fail closed.
                throw new IllegalArgumentException(
                        "Cannot verify path containment for '" + child + "': " + ex.getMessage());
            }
        }

        return resolved;
    }

    /**
     * Walks up from {@code path} until it finds a component that actually exists on disk,
     * then returns {@link Path#toRealPath()} of that ancestor. This allows validation of
     * paths that do not yet exist (e.g. an export file about to be created).
     *
     * <p>If no ancestor exists at all (not even the filesystem root), returns
     * {@link Path#toAbsolutePath()} of {@code path} to avoid an infinite loop.
     *
     * @throws IOException if {@code toRealPath()} fails for an existing path
     */
    static Path toRealPathOfExistingAncestor(Path path) throws IOException {
        Path candidate = path.toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.exists(candidate)) {
                return candidate.toRealPath();
            }
            candidate = candidate.getParent();
        }
        // Fallback: path has no existing ancestor at all — return as-is.
        return path.toAbsolutePath().normalize();
    }

    /**
     * @return {@code true} when {@code candidate} is {@code baseDir} itself or a descendant
     *         of it (lexical check only — does not resolve symlinks).
     */
    static boolean isContained(Path baseDir, Path candidate) {
        final Path normalizedBase = baseDir.normalize();
        final Path normalizedCandidate = candidate.normalize();
        return normalizedCandidate.startsWith(normalizedBase);
    }
}
