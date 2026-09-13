package org.tornotron.echno_backend.common.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The two calls the dataset export makes on the store (#791): a server-side copy out of the
 * attachment bucket, and a manifest written into the dataset bucket. Both are asserted on the
 * request the SDK receives, so a swapped bucket or key would fail here rather than in MinIO.
 */
class FileStorageServiceCopyTest {

    private S3Client s3Client;
    private FileStorageService storage;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        storage = new FileStorageService(s3Client, mock(S3Presigner.class));
        ReflectionTestUtils.setField(storage, "bucketName", "echno-attachments");
    }

    @Test
    void copyObjectTo_copiesFromTheAttachmentBucketIntoTheGivenBucketServerSide() {
        storage.copyObjectTo("inspection/2026/09/slab.jpg", "echno-datasets",
                "construction-images/export/run-1/inspection-evidence/att-11-slab.jpg");

        ArgumentCaptor<CopyObjectRequest> request = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(request.capture());
        assertThat(request.getValue().sourceBucket()).isEqualTo("echno-attachments");
        assertThat(request.getValue().sourceKey()).isEqualTo("inspection/2026/09/slab.jpg");
        assertThat(request.getValue().destinationBucket()).isEqualTo("echno-datasets");
        assertThat(request.getValue().destinationKey())
                .isEqualTo("construction-images/export/run-1/inspection-evidence/att-11-slab.jpg");
    }

    @Test
    void putObject_writesTheBytesToTheGivenBucketAndKey() {
        byte[] manifest = "{\"a\":1}\n".getBytes(StandardCharsets.UTF_8);

        storage.putObject("echno-datasets", "construction-images/export/run-1/manifest.jsonl", manifest,
                "application/x-ndjson");

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(request.capture(), body.capture());
        assertThat(request.getValue().bucket()).isEqualTo("echno-datasets");
        assertThat(request.getValue().key()).isEqualTo("construction-images/export/run-1/manifest.jsonl");
        assertThat(request.getValue().contentType()).isEqualTo("application/x-ndjson");
        assertThat(body.getValue().optionalContentLength()).hasValue((long) manifest.length);
    }
}
