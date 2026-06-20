package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;

@Component(service = {ManagedService.class, GraphQLWebsitesConfig.class}, property = {
        "service.pid=org.jahia.community.graphql.websites",
        "service.description=GraphQL Websites Extension configuration service",
        "service.vendor=Jahia Solutions Group SA"
}, immediate = true)
public class GraphQLWebsitesConfig implements ManagedService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLWebsitesConfig.class);

    static final String AWS_S3_REGION = "aws.s3.region";
    static final String AWS_S3_BUCKET_NAME = "aws.s3.bucketName";
    static final String AWS_S3_ACCESS_KEY = "aws.s3.accessKey";
    static final String AWS_S3_SECRET_ACCESS_KEY = "aws.s3.secretAccessKey";

    /**
     * Immutable snapshot of resolved configuration values.
     * Published via a single volatile field so reads are atomic — either the caller
     * sees the old complete snapshot or the new complete snapshot, never a mix.
     */
    private static final class ConfigSnapshot {
        final String awsS3Region;
        final String awsS3BucketName;
        final String awsS3AccessKey;
        final String awsS3SecretAccessKey;
        final boolean configured;

        ConfigSnapshot(String region, String bucket, String accessKey, String secretKey) {
            this.awsS3Region = region;
            this.awsS3BucketName = bucket;
            this.awsS3AccessKey = accessKey;
            this.awsS3SecretAccessKey = secretKey;
            this.configured = !StringUtils.isBlank(region)
                    && !StringUtils.isBlank(bucket)
                    && !StringUtils.isBlank(accessKey)
                    && !StringUtils.isBlank(secretKey);
        }

        /** Empty, not-configured snapshot used before the first updated() call. */
        static final ConfigSnapshot EMPTY = new ConfigSnapshot(null, null, null, null);
    }

    // Single volatile reference to an IMMUTABLE snapshot, replaced atomically on each
    // updated() call. Safe publication via a volatile reference to an immutable object is
    // the correct idiom, so S3077 ("volatile is not enough") is a false positive here.
    @SuppressWarnings("java:S3077")
    private volatile ConfigSnapshot snapshot = ConfigSnapshot.EMPTY;

    @Override
    public void updated(Dictionary<String, ?> dictionary) throws ConfigurationException {
        if (dictionary == null) {
            // OSGi may call updated(null) to signal configuration deletion.
            snapshot = ConfigSnapshot.EMPTY;
            return;
        }
        final String region     = extract(dictionary, AWS_S3_REGION);
        final String bucket     = extract(dictionary, AWS_S3_BUCKET_NAME);
        final String accessKey  = extract(dictionary, AWS_S3_ACCESS_KEY);
        final String secretKey  = extract(dictionary, AWS_S3_SECRET_ACCESS_KEY);

        snapshot = new ConfigSnapshot(region, bucket, accessKey, secretKey);
        LOGGER.info("GraphQLWebsitesConfig updated; configured={}", snapshot.configured);
    }

    /** Returns the non-blank value for {@code key}, or {@code null} if absent or blank. */
    private static String extract(Dictionary<String, ?> dict, String key) {
        Object raw = dict.get(key);
        if (raw == null) {
            return null;
        }
        String value = raw.toString();
        return StringUtils.isBlank(value) ? null : value;
    }

    public String getAwsS3Region() {
        return snapshot.awsS3Region;
    }

    public String getAwsS3AccessKey() {
        return snapshot.awsS3AccessKey;
    }

    public String getAwsS3BucketName() {
        return snapshot.awsS3BucketName;
    }

    public String getAwsS3SecretAccessKey() {
        return snapshot.awsS3SecretAccessKey;
    }

    public boolean isConfigured() {
        return snapshot.configured;
    }
}
