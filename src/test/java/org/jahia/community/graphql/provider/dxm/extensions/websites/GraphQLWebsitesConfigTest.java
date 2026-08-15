package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.junit.Test;

import java.util.Hashtable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GraphQLWebsitesConfig}.
 *
 * These tests drive the {@code updated()} / getter contract directly without OSGi
 * container involvement.  Item L of the fix specification.
 */
public class GraphQLWebsitesConfigTest {

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static GraphQLWebsitesConfig freshConfig() {
        return new GraphQLWebsitesConfig();
    }

    private static Hashtable<String, Object> allKeys(String region, String bucket, String accessKey, String secretKey) {
        Hashtable<String, Object> dict = new Hashtable<>();
        dict.put(GraphQLWebsitesConfig.AWS_S3_REGION, region);
        dict.put(GraphQLWebsitesConfig.AWS_S3_BUCKET_NAME, bucket);
        dict.put(GraphQLWebsitesConfig.AWS_S3_ACCESS_KEY, accessKey);
        dict.put(GraphQLWebsitesConfig.AWS_S3_SECRET_ACCESS_KEY, secretKey);
        return dict;
    }

    // -------------------------------------------------------------------------
    // updated(null) keeps not-configured
    // -------------------------------------------------------------------------

    @Test
    public void updated_nullDictionary_remainsNotConfigured() throws Exception {
        // Arrange
        GraphQLWebsitesConfig cfg = freshConfig();

        // Act
        cfg.updated(null);

        // Assert
        assertThat(cfg.isConfigured()).isFalse();
        assertThat(cfg.getAwsS3Region()).isNull();
        assertThat(cfg.getAwsS3BucketName()).isNull();
        assertThat(cfg.getAwsS3AccessKey()).isNull();
        assertThat(cfg.getAwsS3SecretAccessKey()).isNull();
    }

    // `updated_nullDictionary_doesNotThrow` was removed here: updated_nullDictionary_remainsNotConfigured
    // above already calls updated(null) on the same fresh instance, so any throw fails that test
    // first. A separate no-throw assertion added no behavioural signal, only a second thing to
    // maintain.

    // -------------------------------------------------------------------------
    // All keys present → configured
    // -------------------------------------------------------------------------

    @Test
    public void updated_allKeysPresent_isConfigured() throws Exception {
        // Arrange
        GraphQLWebsitesConfig cfg = freshConfig();
        Hashtable<String, Object> dict = allKeys("us-east-1", "my-bucket", "AKID", "secret");

        // Act
        cfg.updated(dict);

        // Assert
        assertThat(cfg.isConfigured()).isTrue();
    }

    @Test
    public void updated_allKeysPresent_gettersReturnExpectedValues() throws Exception {
        // Arrange
        GraphQLWebsitesConfig cfg = freshConfig();
        cfg.updated(allKeys("eu-west-1", "test-bucket", "key123", "secret456"));

        // Assert
        assertThat(cfg.getAwsS3Region()).isEqualTo("eu-west-1");
        assertThat(cfg.getAwsS3BucketName()).isEqualTo("test-bucket");
        assertThat(cfg.getAwsS3AccessKey()).isEqualTo("key123");
        assertThat(cfg.getAwsS3SecretAccessKey()).isEqualTo("secret456");
    }

    // -------------------------------------------------------------------------
    // One key missing → not configured
    // -------------------------------------------------------------------------

    @Test
    public void updated_missingRegion_isNotConfigured() throws Exception {
        GraphQLWebsitesConfig cfg = freshConfig();
        Hashtable<String, Object> dict = allKeys("us-east-1", "bucket", "key", "secret");
        dict.remove(GraphQLWebsitesConfig.AWS_S3_REGION);

        cfg.updated(dict);

        assertThat(cfg.isConfigured()).isFalse();
    }

    @Test
    public void updated_missingBucketName_isNotConfigured() throws Exception {
        GraphQLWebsitesConfig cfg = freshConfig();
        Hashtable<String, Object> dict = allKeys("us-east-1", "bucket", "key", "secret");
        dict.remove(GraphQLWebsitesConfig.AWS_S3_BUCKET_NAME);

        cfg.updated(dict);

        assertThat(cfg.isConfigured()).isFalse();
    }

    @Test
    public void updated_missingAccessKey_isNotConfigured() throws Exception {
        GraphQLWebsitesConfig cfg = freshConfig();
        Hashtable<String, Object> dict = allKeys("us-east-1", "bucket", "key", "secret");
        dict.remove(GraphQLWebsitesConfig.AWS_S3_ACCESS_KEY);

        cfg.updated(dict);

        assertThat(cfg.isConfigured()).isFalse();
    }

    @Test
    public void updated_missingSecretKey_isNotConfigured() throws Exception {
        GraphQLWebsitesConfig cfg = freshConfig();
        Hashtable<String, Object> dict = allKeys("us-east-1", "bucket", "key", "secret");
        dict.remove(GraphQLWebsitesConfig.AWS_S3_SECRET_ACCESS_KEY);

        cfg.updated(dict);

        assertThat(cfg.isConfigured()).isFalse();
    }

    // -------------------------------------------------------------------------
    // Blank value excluded → not configured
    // -------------------------------------------------------------------------

    @Test
    public void updated_blankRegion_isNotConfigured() throws Exception {
        GraphQLWebsitesConfig cfg = freshConfig();
        Hashtable<String, Object> dict = allKeys("   ", "bucket", "key", "secret");

        cfg.updated(dict);

        assertThat(cfg.isConfigured()).isFalse();
        assertThat(cfg.getAwsS3Region()).isNull();
    }

    @Test
    public void updated_blankBucketName_isNotConfigured() throws Exception {
        GraphQLWebsitesConfig cfg = freshConfig();
        Hashtable<String, Object> dict = allKeys("us-east-1", "", "key", "secret");

        cfg.updated(dict);

        assertThat(cfg.isConfigured()).isFalse();
        assertThat(cfg.getAwsS3BucketName()).isNull();
    }

    // -------------------------------------------------------------------------
    // Snapshot atomicity: second updated() replaces first completely
    // -------------------------------------------------------------------------

    @Test
    public void updated_calledTwice_secondCallReplacesFirst() throws Exception {
        GraphQLWebsitesConfig cfg = freshConfig();
        cfg.updated(allKeys("us-east-1", "bucket1", "key1", "sec1"));
        cfg.updated(allKeys("ap-northeast-1", "bucket2", "key2", "sec2"));

        assertThat(cfg.getAwsS3Region()).isEqualTo("ap-northeast-1");
        assertThat(cfg.getAwsS3BucketName()).isEqualTo("bucket2");
        assertThat(cfg.isConfigured()).isTrue();
    }

    @Test
    public void updated_configuredThenNull_becomesNotConfigured() throws Exception {
        GraphQLWebsitesConfig cfg = freshConfig();
        cfg.updated(allKeys("us-east-1", "bucket", "key", "secret"));
        assertThat(cfg.isConfigured()).isTrue();

        cfg.updated(null);

        assertThat(cfg.isConfigured()).isFalse();
        assertThat(cfg.getAwsS3Region()).isNull();
    }
}
