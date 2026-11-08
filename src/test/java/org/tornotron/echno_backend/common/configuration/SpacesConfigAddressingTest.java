package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins how the object-store clients address a bucket.
 *
 * <p>The server-side client and the presigner used to be built independently, and only the
 * presigner was given path-style addressing. The client therefore folded the bucket into the
 * hostname and every server-side call died on {@code UnknownHostException:
 * echno-objects.echno-minio}, because MinIO answers to one name and serves buckets from the
 * path. These tests fail if either client goes back to bucket-in-host, and fail if the two
 * ever stop agreeing.
 */
class SpacesConfigAddressingTest {

    private static final String INTERNAL_ENDPOINT = "http://echno-minio:9000";
    private static final String PUBLIC_ENDPOINT = "https://storage.echno.test";
    private static final String BUCKET = "echno-objects";
    private static final String KEY = "issue/site-photo.png";

    @Test
    void serverSideClientPutsTheBucketInThePathNotTheHost() {
        SpacesConfig config = configWith(true);

        try (S3Client client = config.s3Client(config.objectStoreServiceConfiguration())) {
            URI url = urlFor(client);

            assertThat(url.getHost()).isEqualTo("echno-minio");
            assertThat(url.getPath()).isEqualTo("/" + BUCKET + "/" + KEY);
            assertThat(url.getHost()).doesNotContain(BUCKET);
        }
    }

    @Test
    void presignerPutsTheBucketInThePathNotTheHost() {
        SpacesConfig config = configWith(true);

        try (S3Presigner presigner = config.s3Presigner(config.objectStoreServiceConfiguration())) {
            URI url = presignedUrlFor(presigner);

            assertThat(url.getHost()).isEqualTo("storage.echno.test");
            assertThat(url.getPath()).isEqualTo("/" + BUCKET + "/" + KEY);
        }
    }

    @Test
    void bothClientsAddressTheBucketTheSameWay() {
        SpacesConfig config = configWith(true);
        S3Configuration shared = config.objectStoreServiceConfiguration();

        try (S3Client client = config.s3Client(shared);
             S3Presigner presigner = config.s3Presigner(shared)) {

            // Endpoints differ by design, the addressing style must not.
            assertThat(urlFor(client).getPath()).isEqualTo(presignedUrlFor(presigner).getPath());
        }
    }

    @Test
    void virtualHostStyleIsAvailableForStoresThatRequireIt() {
        SpacesConfig config = configWith(false);
        S3Configuration shared = config.objectStoreServiceConfiguration();

        try (S3Client client = config.s3Client(shared);
             S3Presigner presigner = config.s3Presigner(shared)) {

            // Both flip together, which is the point of the shared configuration.
            assertThat(urlFor(client).getHost()).isEqualTo(BUCKET + ".echno-minio");
            assertThat(presignedUrlFor(presigner).getHost()).isEqualTo(BUCKET + ".storage.echno.test");
        }
    }

    @Test
    void bothClientsSignForTheConfiguredRegion() {
        SpacesConfig config = configWith(true);
        ReflectionTestUtils.setField(config, "region", "eu-central-1");
        S3Configuration shared = config.objectStoreServiceConfiguration();

        try (S3Client client = config.s3Client(shared);
             S3Presigner presigner = config.s3Presigner(shared)) {

            assertThat(client.serviceClientConfiguration().region()).isEqualTo(Region.of("eu-central-1"));
            // The credential scope in the query string is what a store checks the region against.
            assertThat(presignedUrlFor(presigner).getQuery())
                    .contains("/eu-central-1/s3/aws4_request")
                    .doesNotContain("us-east-1");
        }
    }

    @Test
    void anEnvironmentThatSetsNoRegionKeepsSigningForUsEast1() throws Exception {
        assertThat(propertyIn(new MockEnvironment(), "digital-ocean.region")).isEqualTo("us-east-1");
    }

    @Test
    void theRegionIsReadFromItsEnvironmentVariable() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DIGITAL_OCEAN_SPACES_REGION", "eu-central-1");

        assertThat(propertyIn(environment, "digital-ocean.region")).isEqualTo("eu-central-1");
    }

    @Test
    void anEnvironmentThatSetsNoAddressingStyleKeepsPathStyle() throws Exception {
        assertThat(propertyIn(new MockEnvironment(), "digital-ocean.path-style-access")).isEqualTo("true");
    }

    @Test
    void theAddressingStyleIsReadFromItsEnvironmentVariable() throws Exception {
        MockEnvironment on = new MockEnvironment().withProperty("DIGITAL_OCEAN_SPACES_PATH_STYLE", "true");
        MockEnvironment off = new MockEnvironment().withProperty("DIGITAL_OCEAN_SPACES_PATH_STYLE", "false");

        assertThat(propertyIn(on, "digital-ocean.path-style-access")).isEqualTo("true");
        assertThat(propertyIn(off, "digital-ocean.path-style-access")).isEqualTo("false");
    }

    private static String propertyIn(MockEnvironment environment, String property) throws Exception {
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return environment.getProperty(property);
    }

    private static SpacesConfig configWith(boolean pathStyleAccess) {
        SpacesConfig config = new SpacesConfig();
        ReflectionTestUtils.setField(config, "region", "us-east-1");
        ReflectionTestUtils.setField(config, "spaces", INTERNAL_ENDPOINT);
        ReflectionTestUtils.setField(config, "presignEndpoint", PUBLIC_ENDPOINT);
        ReflectionTestUtils.setField(config, "accessKeyId", "test-key-id");
        ReflectionTestUtils.setField(config, "secretAccessKey", "test-secret-key");
        ReflectionTestUtils.setField(config, "pathStyleAccess", pathStyleAccess);
        return config;
    }

    /** The URL the client would call for an object, resolved offline through the SDK's own rules. */
    private static URI urlFor(S3Client client) {
        return URI.create(client.utilities()
                .getUrl(GetUrlRequest.builder().bucket(BUCKET).key(KEY).build())
                .toString());
    }

    private static URI presignedUrlFor(S3Presigner presigner) {
        return URI.create(presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofMinutes(15))
                        .putObjectRequest(PutObjectRequest.builder().bucket(BUCKET).key(KEY).build())
                        .build())
                .url()
                .toString());
    }
}
