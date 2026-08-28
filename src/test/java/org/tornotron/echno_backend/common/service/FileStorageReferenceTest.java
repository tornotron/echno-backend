package org.tornotron.echno_backend.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Resolving a stored file reference back to the key it names.
 *
 * <p>This is the guard that keeps a server-side read of a stored reference from
 * turning into a request forgery. A report that embeds a defect photograph reads
 * it through the object store, and it resolves the reference here first, so the
 * only thing it can ever fetch is a key in the configured bucket. A reference a
 * tenant put in the database is never dereferenced as a URL.
 *
 * <p>Plain JUnit with a mocked S3 client: nothing here touches storage, and a
 * context this needs nothing from is a context the 1 GB test JVM must not start.
 */
class FileStorageReferenceTest {

    private static final String CDN = "https://echno.blr1.cdn.digitaloceanspaces.test";

    private FileStorageService fileStorage;

    @BeforeEach
    void buildService() {
        fileStorage = new FileStorageService(mock(S3Client.class), mock(S3Presigner.class));
        ReflectionTestUtils.setField(fileStorage, "bucketName", "echno-object-store");
        ReflectionTestUtils.setField(fileStorage, "cdnEndpoint", CDN);
    }

    @Test
    void readsTheKeyOutOfAPublicUrlThisBucketIssued() {
        assertThat(fileStorage.keyForStoredReference(CDN + "/inspections/abc-crack.jpg"))
                .contains("inspections/abc-crack.jpg");
    }

    @Test
    void acceptsATrailingSlashOnTheConfiguredEndpoint() {
        ReflectionTestUtils.setField(fileStorage, "cdnEndpoint", CDN + "/");

        assertThat(fileStorage.keyForStoredReference(CDN + "/inspections/abc-crack.jpg"))
                .contains("inspections/abc-crack.jpg");
    }

    @Test
    void takesABareKeyAsItStands() {
        assertThat(fileStorage.keyForStoredReference("inspections/abc-crack.jpg"))
                .contains("inspections/abc-crack.jpg");
        assertThat(fileStorage.keyForStoredReference("/inspections/abc-crack.jpg"))
                .contains("inspections/abc-crack.jpg");
    }

    /**
     * The one that matters. A reference on any other host is not one of our objects,
     * and resolving it to something fetchable would make a stored string into an
     * outbound request the server makes on the tenant's behalf.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://attacker.example/inspections/abc.jpg",
            "http://169.254.169.254/latest/meta-data/",
            "//attacker.example/abc.jpg",
            "file:///etc/passwd",
            "s3://another-bucket/abc.jpg"
    })
    void refusesAReferenceThatDoesNotNameAnObjectInThisBucket(String reference) {
        assertThat(fileStorage.keyForStoredReference(reference)).isEmpty();
    }

    @Test
    void refusesAKeyThatTriesToClimbOutOfItsPrefix() {
        assertThat(fileStorage.keyForStoredReference("inspections/../../secrets/key.pem"))
                .isEmpty();
    }

    @Test
    void refusesNothingAtAll() {
        assertThat(fileStorage.keyForStoredReference(null)).isEmpty();
        assertThat(fileStorage.keyForStoredReference("   ")).isEmpty();
        assertThat(fileStorage.keyForStoredReference(CDN + "/")).isEmpty();
    }
}
