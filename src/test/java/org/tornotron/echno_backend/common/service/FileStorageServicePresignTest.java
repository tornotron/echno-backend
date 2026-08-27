package org.tornotron.echno_backend.common.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the header set a presigned upload URL commits the client to.
 *
 * <p>SigV4 folds every header on the signed request into
 * {@code X-Amz-SignedHeaders}, and the client must reproduce each of them
 * byte-for-byte or storage rejects the PUT as a signature mismatch. The browser
 * uploads with {@code Content-Type} and nothing else, so a signed
 * {@code x-amz-acl} broke every direct-to-storage attachment upload. These
 * tests fail if a header the client does not send creeps back onto the request.
 */
class FileStorageServicePresignTest {

    private S3Presigner presigner;
    private FileStorageService service;

    @BeforeEach
    void setUp() {
        presigner = S3Presigner.builder()
                .endpointOverride(URI.create("https://storage.example.test"))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test-key-id", "test-secret-key")))
                .build();

        service = new FileStorageService(mock(S3Client.class), presigner);
        ReflectionTestUtils.setField(service, "bucketName", "test-bucket");
    }

    @AfterEach
    void tearDown() {
        presigner.close();
    }

    @Test
    void signsOnlyTheHeadersTheClientSends() {
        PresignedUpload upload = service.generateUploadUrl(
                "issue", "site-photo.png", "image/png", Duration.ofMinutes(15));

        assertThat(signedHeadersOf(upload)).containsExactlyInAnyOrder("content-type", "host");
    }

    @Test
    void doesNotSignACannedAcl() {
        PresignedUpload upload = service.generateUploadUrl(
                "issue", "site-photo.png", "image/png", Duration.ofMinutes(15));

        assertThat(signedHeadersOf(upload)).doesNotContain("x-amz-acl");
    }

    @Test
    void signsTheContentTypeItReportsBackToTheCaller() {
        PresignedUpload upload = service.generateUploadUrl(
                "issue", "drawing.pdf", "application/pdf", Duration.ofMinutes(15));

        // The caller PUTs with exactly this value, so it has to be the signed one.
        assertThat(upload.contentType()).isEqualTo("application/pdf");
        assertThat(signedHeadersOf(upload)).contains("content-type");
    }

    /** Reads the lower-cased header names out of a presigned URL's query string. */
    private static List<String> signedHeadersOf(PresignedUpload upload) {
        Map<String, String> query = Arrays.stream(URI.create(upload.url()).getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(
                        pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""));

        return Arrays.asList(query.getOrDefault("X-Amz-SignedHeaders", "").split(";"));
    }
}
