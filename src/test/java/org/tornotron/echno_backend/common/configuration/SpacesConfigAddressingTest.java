package org.tornotron.echno_backend.common.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
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

    private static SpacesConfig configWith(boolean pathStyleAccess) {
        SpacesConfig config = new SpacesConfig();
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
