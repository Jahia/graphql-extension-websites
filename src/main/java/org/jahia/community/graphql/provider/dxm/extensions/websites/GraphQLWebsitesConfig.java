package org.jahia.community.graphql.provider.dxm.extensions.websites;

import org.apache.commons.lang3.StringUtils;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.Map;

@Component(service = {ManagedService.class, GraphQLWebsitesConfig.class}, property = {
        "service.pid=org.jahia.community.graphql.websites",
        "service.description=GraphQL Websites Extension configuration service",
        "service.vendor=Jahia Solutions Group SA"
}, immediate = true)
public class GraphQLWebsitesConfig implements ManagedService {
    private Logger logger = LoggerFactory.getLogger(GraphQLWebsitesConfig.class);

    static final String AWS_S3_REGION = "aws.s3.region";
    static final String AWS_S3_BUCKET_NAME = "aws.s3.bucketName";
    static final String AWS_S3_ACCESS_KEY = "aws.s3.accessKey";
    static final String AWS_S3_SECRET_ACCESS_KEY = "aws.s3.secretAccessKey";

    private Map<String,String> config;
    private boolean isConfigured;

    @Override
    public void updated(Dictionary<String, ?> dictionary) throws ConfigurationException {
        if (dictionary != null && !dictionary.isEmpty()) {
            // Do something with the configuration
            config = new HashMap<>(4);
            dictionary.keys().asIterator().forEachRemaining(key -> {
                logger.info("Configuration key: {}", key);
                String value = (String) dictionary.get(key);
                if (!StringUtils.isEmpty(value)) {
                    config.put(key, value);
                }
            });
            isConfigured = config.containsKey(AWS_S3_REGION) && config.containsKey(AWS_S3_BUCKET_NAME) && config.containsKey(AWS_S3_ACCESS_KEY) && config.containsKey(AWS_S3_SECRET_ACCESS_KEY);
        }
    }

    public Map<String, String> getConfig() {
        return config;
    }

    public String getAwsS3Region() {
        return config.get(AWS_S3_REGION);
    }

    public String getAwsS3AccessKey() {
        return config.get(AWS_S3_ACCESS_KEY);
    }

    public String getAwsS3BucketName() {
        return config.get(AWS_S3_BUCKET_NAME);
    }

    public String getAwsS3SecretAccessKey() {
        return config.get(AWS_S3_SECRET_ACCESS_KEY);
    }

    public boolean isConfigured() {
        return isConfigured;
    }
}
