package org.jahia.community.graphql.provider.dxm.extensions.websites;

import java.nio.file.Path;

/**
 * Path containment helpers shared by the website export/import mutations.
 *
 * <p>All user-supplied path fragments coming from GraphQL input (export path, import path,
 * site key) are untrusted and may contain {@code ..} segments or absolute escapes. These
 * helpers resolve a child against a trusted base directory and verify that the canonical
 * result stays inside that base, rejecting any traversal attempt.
 */
final class PathSecurity {

    private PathSecurity() {
        // utility class
    }

    /**
     * Resolves {@code child} against {@code baseDir} and verifies the normalized result is
     * contained within {@code baseDir}.
     *
     * @param baseDir trusted, already-normalized absolute base directory
     * @param child   untrusted relative fragment from external input (may be {@code null})
     * @return the normalized, contained absolute path
     * @throws IllegalArgumentException if {@code child} is null/blank or escapes {@code baseDir}
     */
    static Path resolveContained(Path baseDir, String child) {
        if (child == null || child.trim().isEmpty()) {
            throw new IllegalArgumentException("Path fragment must not be null or blank");
        }
        final Path resolved = baseDir.resolve(child).normalize();
        if (!isContained(baseDir, resolved)) {
            throw new IllegalArgumentException(
                    "Path fragment '" + child + "' resolves outside the allowed directory");
        }
        return resolved;
    }

    /**
     * @return {@code true} when {@code candidate} is {@code baseDir} itself or a descendant of it.
     */
    static boolean isContained(Path baseDir, Path candidate) {
        final Path normalizedBase = baseDir.normalize();
        final Path normalizedCandidate = candidate.normalize();
        return normalizedCandidate.startsWith(normalizedBase);
    }
}
