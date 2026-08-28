package org.tornotron.echno_backend.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private static final long CAP = 1_000L;

    private S3Client s3Client;
    private FileStorageService fileStorage;

    @BeforeEach
    void buildService() {
        s3Client = mock(S3Client.class);
        fileStorage = new FileStorageService(s3Client, mock(S3Presigner.class));
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

    /**
     * The size limit has to bind the transfer, not only the metadata check. An
     * object can be replaced between the HEAD and the GET, and a cap a race can
     * step over is not a cap.
     */
    @Test
    void asksForOneByteMoreThanTheCapSoAnOversizedObjectCannotBeAllocated() {
        givenHead(500L);
        givenBody(new byte[500], "image/jpeg");

        assertThat(fileStorage.readObject("inspections/crack.jpg", CAP)).isPresent();

        ArgumentCaptor<GetObjectRequest> get = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObjectAsBytes(get.capture());
        assertThat(get.getValue().range()).isEqualTo("bytes=0-" + CAP);
    }

    @Test
    void refusesAnObjectThatGrewPastTheCapBetweenTheHeadAndTheGet() {
        givenHead(500L);
        // the replacement is larger, so the ranged read comes back one byte too long
        givenBody(new byte[(int) CAP + 1], "image/jpeg");

        assertThat(fileStorage.readObject("inspections/crack.jpg", CAP)).isEmpty();
    }

    @Test
    void refusesAnObjectTheHeadAlreadyReportsAsTooLarge() {
        givenHead(CAP + 1);

        assertThat(fileStorage.readObject("inspections/crack.jpg", CAP)).isEmpty();
        verify(s3Client, org.mockito.Mockito.never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    void refusesAnObjectWhoseLengthTheStoreDoesNotReport() {
        givenHead(null);

        assertThat(fileStorage.readObject("inspections/crack.jpg", CAP)).isEmpty();
    }

    @Test
    void refusesNoKeyAtAllWithoutTouchingStorage() {
        assertThat(fileStorage.readObject(null, CAP)).isEmpty();
        assertThat(fileStorage.readObject("  ", CAP)).isEmpty();
        verify(s3Client, org.mockito.Mockito.never()).headObject(any(HeadObjectRequest.class));
    }

    private void givenHead(Long contentLength) {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(contentLength).build());
    }

    private void givenBody(byte[] content, String contentType) {
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().contentType(contentType).build(), content));
    }
}
