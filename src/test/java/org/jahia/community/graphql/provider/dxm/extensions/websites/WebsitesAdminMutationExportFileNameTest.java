package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WebsitesAdminMutation#buildExportFileName(LocalDateTime)}.
 *
 * <p>The bulk export previously named its archive {@code export-<yyyyMMddHHmm>.zip}. That name is
 * also the S3 object key, and it was minute-granular: two exports started within the same minute
 * resolved to the same on-disk path and the same key. The second overwrote the first mid-upload,
 * and whichever finished first deleted the shared file in its {@code finally} block.
 *
 * <p>Seconds alone would only narrow that window, so the name carries a random suffix and the
 * uniqueness test below is the point of this class. The format test exists because the leading
 * timestamp is load-bearing for a different reason — it keeps archives sorting chronologically
 * in the bucket.
 */
public class WebsitesAdminMutationExportFileNameTest {

    /** {@code export-} + 14 timestamp digits + {@code -} + 8 hex + {@code .zip}. */
    private static final Pattern EXPORT_NAME = Pattern.compile("^export-\\d{14}-[0-9a-f]{8}\\.zip$");

    @Test
    public void buildExportFileName_matchesTimestampedZipFormat() {
        // Arrange
        LocalDateTime timestamp = LocalDateTime.of(2026, Month.AUGUST, 14, 9, 7, 3);

        // Act
        String name = WebsitesAdminMutation.buildExportFileName(timestamp);

        // Assert
        assertThat(name).matches(EXPORT_NAME);
    }

    @Test
    public void buildExportFileName_embedsTheSuppliedTimestampToTheSecond() {
        // Arrange — zero-padding matters; a single-digit month/hour must not shorten the name
        LocalDateTime timestamp = LocalDateTime.of(2026, Month.JANUARY, 2, 3, 4, 5);

        // Act
        String name = WebsitesAdminMutation.buildExportFileName(timestamp);

        // Assert
        assertThat(name).startsWith("export-20260102030405-");
    }

    /**
     * <b>If this ever fails intermittently, suspect the birthday bound before suspecting a
     * regression.</b> The suffix is 8 hex characters, i.e. a 32-bit space, and 500 draws from it
     * collide with probability ≈ 500²/2·2³² ≈ 2.9e-5 — about one run in 34,000. A genuine
     * regression (a suffix that stops being random, or is dropped) fails this <em>every</em> time
     * and usually collapses the set to a single element, so check the reported size before
     * reaching for the git log: 499 means bad luck, 1 means the suffix is gone.
     */
    @Test
    public void buildExportFileName_isUniqueAcrossCallsWithinTheSameSecond() {
        // Arrange — the identical timestamp is exactly the collision the old format could not survive
        LocalDateTime sameInstant = LocalDateTime.of(2026, Month.AUGUST, 14, 9, 7, 3);
        Set<String> names = new HashSet<>();
        int calls = 500;

        // Act
        for (int i = 0; i < calls; i++) {
            names.add(WebsitesAdminMutation.buildExportFileName(sameInstant));
        }

        // Assert
        assertThat(names)
                .as("every export archive name must be distinct even within one second")
                .hasSize(calls);
    }
}
