package org.tornotron.echno_backend.common.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * Object-store clients.
 *
 * <p>Two clients talk to the same store: {@link S3Client} for server-side calls and
 * {@link S3Presigner} for URLs handed to the browser. They differ only in the endpoint
 * they address, because the browser cannot reach the internal one. Everything else,
 * credentials, region and above all the addressing style, comes from the single
 * {@link #objectStoreServiceConfiguration()} bean below, so the two clients cannot end
 * up disagreeing about how a bucket is addressed.
 */
@Configuration
public class SpacesConfig {

    /** Internal endpoint used for server-side calls. */
    @Value("${digital-ocean.uri}")
    private String spaces;

    /** Public endpoint presigned URLs are signed against, since the browser resolves that one. */
    @Value("${digital-ocean.presign-endpoint}")
    private String presignEndpoint;

    @Value("${digital-ocean.access-key-id}")
    private String accessKeyId;

    @Value("${digital-ocean.secret-access-key}")
    private String secretAccessKey;

    /**
     * Where the bucket name goes in a request URL. Path-style puts it in the path
     * ({@code http://host/bucket/key}); the alternative, virtual-host style, puts it in the
     * hostname ({@code http://bucket.host/key}).
     *
     * <p>Default true, because it is the setting that works on every store we deploy against.
     * Self-hosted MinIO serves path-style only, so virtual-host style resolves the bucket into
     * a hostname that does not exist. DigitalOcean Spaces and AWS S3 both still serve path-style,
     * so the same default is correct there. Set {@code digital-ocean.path-style-access=false}
     * only for a store that refuses path-style, and be aware that a bucket name containing dots
     * then breaks TLS certificate matching.
     */
    @Value("${digital-ocean.path-style-access:true}")
    private boolean pathStyleAccess;

    /**
     * The one addressing decision, shared by every client below. Both beans take it as an
     * argument rather than building their own, which is what stops them drifting apart:
     * a client that wants a different style has to change this bean, and changing it moves
     * both clients together.
     */
    @Bean
    public S3Configuration objectStoreServiceConfiguration() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyleAccess)
                .build();
    }

    @Bean
    public S3Client s3Client(S3Configuration objectStoreServiceConfiguration) {
        return S3Client.builder()
                .endpointOverride(URI.create(spaces))
                .serviceConfiguration(objectStoreServiceConfiguration)
                .credentialsProvider(objectStoreCredentials())
                .region(Region.US_EAST_1)
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(S3Configuration objectStoreServiceConfiguration) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(presignEndpoint))
                .serviceConfiguration(objectStoreServiceConfiguration)
                .credentialsProvider(objectStoreCredentials())
                .region(Region.US_EAST_1)
                .build();
    }

    private AwsCredentialsProvider objectStoreCredentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, secretAccessKey)
        );
    }
}
