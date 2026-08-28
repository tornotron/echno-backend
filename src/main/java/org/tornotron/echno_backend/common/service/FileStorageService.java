package org.tornotron.echno_backend.common.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.dto.PresignedUpload;
import org.tornotron.echno_backend.common.dto.StoredFile;
import org.tornotron.echno_backend.common.exception.FileUploadException;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for handling file storage operations with S3-compatible object storage.
 * Provides reusable methods for uploading single or multiple files across all modules.
 */
@Service
public class FileStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${digital-ocean.bucket-name}")
    private String bucketName;

    @Value("${digital-ocean.cdn-endpoint}")
    private String cdnEndpoint;

    public FileStorageService(S3Client s3Client, S3Presigner s3Presigner) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
    }

    /**
     * Uploads a single file to object storage.
     *
     * @param file   The file to upload
     * @param folder The folder/prefix to store the file under (e.g., "projects", "documents")
     * @return StoredFile containing the file metadata and URL
     * @throws FileUploadException if the upload fails
     */
    public StoredFile uploadFile(MultipartFile file, String folder) {
        validateFile(file);

        String key = buildKey(folder, file.getOriginalFilename());

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(file.getContentType())
                .acl(ObjectCannedACL.PRIVATE)
                .build();

        try {
            s3Client.putObject(
                    request,
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
        } catch (IOException e) {
            throw new FileUploadException("Failed to upload file '" + file.getOriginalFilename() + "' to object storage", e);
        }


        return new StoredFile(key,file.getContentType(), file.getSize());
    }

    /**
     * Whether an object is present in storage. Used to confirm a client has
     * actually completed a pre-signed upload before it is recorded, rather than
     * trusting the client's word.
     */
    public boolean objectExists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /**
     * Builds a key and a pre-signed PUT URL so a client can upload straight to
     * object storage.
     *
     * <p>The alternative, streaming the file through this service, puts every
     * upload through the CDN in front of it, which caps request bodies at 100 MB
     * and times out at 100 seconds. Site media routinely exceeds both. Uploading
     * direct to storage removes that ceiling and takes the traffic off the
     * application entirely.
     *
     * <p>The URL is write-only, limited to the single key it names, and expires.
     * The caller registers the object afterwards using the returned key.
     *
     * <p>No canned ACL is set here, deliberately. Every header on the request
     * is folded into {@code X-Amz-SignedHeaders}, and SigV4 requires the client
     * to send back each one byte-for-byte or the signature will not match. A
     * browser PUT sends {@code Content-Type} and nothing else, so signing an
     * {@code x-amz-acl} header made every direct upload fail on the signature.
     * The bucket is private, so objects are private without the canned ACL; the
     * server-side path in {@link #uploadFile} still sets it because it sends the
     * header itself. Anything added to this request must be a header the client
     * is known to send.
     */
    public PresignedUpload generateUploadUrl(String folder, String filename, String contentType, Duration expiry) {
        if (filename == null || filename.isBlank()) {
            throw new FileUploadException("Filename is required to presign an upload");
        }

        String key = buildKey(folder, filename);

        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType)
                .build();

        PresignedPutObjectRequest presignedRequest =
                s3Presigner.presignPutObject(r -> r
                        .signatureDuration(expiry)
                        .putObjectRequest(putObjectRequest)
                );

        return new PresignedUpload(key, presignedRequest.url().toString(), contentType, expiry.toSeconds());
    }

    public String generateDownloadUrl(String key, Duration expiry) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        PresignedGetObjectRequest presignedRequest =
                s3Presigner.presignGetObject(r -> r
                        .signatureDuration(expiry)
                        .getObjectRequest(getObjectRequest)
                );
        return presignedRequest.url().toString();
    }

    /**
     * Uploads multiple files to object storage.
     *
     * @param files  The list of files to upload
     * @param folder The folder/prefix to store the files under (e.g., "projects", "documents")
     * @return List of StoredFile objects containing metadata for each uploaded file
     * @throws FileUploadException if any upload fails
     */
    public List<StoredFile> uploadFiles(List<MultipartFile> files, String folder) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }

        List<StoredFile> storedFiles = new ArrayList<>();
        List<String> uploadedKeys = new ArrayList<>();

        try {
            for (MultipartFile file : files) {
                if (file != null && !file.isEmpty()) {
                    StoredFile storedFile = uploadFile(file, folder);
                    storedFiles.add(storedFile);
                    uploadedKeys.add(storedFile.key());
                }
            }
        } catch (FileUploadException e) {
            // Rollback: delete any files that were successfully uploaded
            rollbackUploadedFiles(uploadedKeys);
            throw new FileUploadException(
                    "Batch upload failed; rolled back " + uploadedKeys.size() + " previously uploaded file(s)", e);
        }

        return storedFiles;
    }

    /**
     * Uploads multiple files to object storage (varargs version for convenience).
     *
     * @param folder The folder/prefix to store the files under
     * @param files  The files to upload
     * @return List of StoredFile objects containing metadata for each uploaded file
     */
    public List<StoredFile> uploadFiles(String folder, MultipartFile... files) {
        return uploadFiles(List.of(files), folder);
    }

    /**
     * The storage key a stored reference points at, when it points into our own
     * bucket.
     *
     * <p>Modules store what a file upload returned, which is either the public URL
     * built from the CDN endpoint or the bare key. Anything else, notably an
     * absolute URL on a host that is not ours, is not one of our objects and comes
     * back empty.
     *
     * <p>This is what keeps a server-side read of a stored reference from becoming
     * a request forgery. A caller that wants the bytes behind a reference resolves
     * it here first, so the only thing it can ever fetch is a key in the configured
     * bucket. A reference the tenant put in the database is never dereferenced as a
     * URL.
     *
     * @param storedReference The value a module recorded, possibly null.
     * @return The key, or empty when the reference does not name an object in this
     *         bucket.
     */
    public Optional<String> keyForStoredReference(String storedReference) {
        if (storedReference == null || storedReference.isBlank()) {
            return Optional.empty();
        }
        String reference = storedReference.trim();

        String endpoint = cdnEndpoint == null ? "" : cdnEndpoint;
        String prefix = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        if (!prefix.isBlank() && reference.startsWith(prefix)) {
            String key = reference.substring(prefix.length());
            return key.isBlank() ? Optional.empty() : Optional.of(key);
        }

        // A bare key: no scheme, no protocol-relative prefix, no path traversal.
        if (reference.contains("://") || reference.startsWith("//") || reference.contains("..")) {
            return Optional.empty();
        }
        return Optional.of(reference.startsWith("/") ? reference.substring(1) : reference);
    }

    /**
     * Reads an object back into memory, refusing anything above a size limit.
     *
     * <p>The size is checked with a HEAD before the body is fetched, so an
     * oversized object costs one metadata round trip rather than the transfer.
     * Callers that embed objects in a rendered document need that: the limit is
     * what stops one large site photo from deciding how much heap a report takes.
     *
     * @param key      Key of the object to read.
     * @param maxBytes Largest object to fetch. An object at or below this is
     *                 returned; a larger one, or a missing one, comes back empty.
     * @return The bytes and the content type recorded on the object.
     */
    public Optional<StoredObject> readObject(String key, long maxBytes) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        try {
            HeadObjectResponse head = s3Client.headObject(
                    HeadObjectRequest.builder().bucket(bucketName).key(key).build());
            if (head.contentLength() == null || head.contentLength() > maxBytes) {
                return Optional.empty();
            }

            ResponseBytes<GetObjectResponse> object = s3Client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucketName).key(key).build());
            return Optional.of(new StoredObject(
                    key, object.response().contentType(), object.asByteArray()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (SdkException e) {
            // Storage being unavailable must not fail whatever is embedding the
            // object; the caller renders a placeholder in its place.
            return Optional.empty();
        }
    }

    /** An object read back out of storage. */
    public record StoredObject(String key, String contentType, byte[] content) {
    }

    /**
     * Deletes a file from object storage.
     *
     * @param key The key of the file to delete
     */
    public void deleteFile(String key) {
        if (key == null || key.isBlank()) {
            return;
        }

        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();

        s3Client.deleteObject(request);
    }

    /**
     * Deletes multiple files from object storage.
     *
     * @param keys The keys of the files to delete
     */
    public void deleteFiles(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String key : keys) {
            deleteFile(key);
        }
    }

    /**
     * Validates that the file is not null and not empty.
     *
     * @param file The file to validate
     * @throws FileUploadException if the file is invalid
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileUploadException("No file was provided, or the file is empty");
        }
    }

    /**
     * Builds the storage key for a file.
     *
     * @param folder   The folder prefix
     * @param filename The original filename
     * @return The complete storage key
     */
    private String buildKey(String folder, String filename) {
        String sanitizedFolder = folder != null ? folder.replaceAll("^/+|/+$", "") : "uploads";
        String uniqueFilename = UUID.randomUUID() + "-" + filename;
        return sanitizedFolder + "/" + uniqueFilename;
    }

    /**
     * Builds the public URL for a stored file.
     *
     * @param key The storage key
     * @return The public URL
     */
    private String buildFileUrl(String key) {
        String endpoint = cdnEndpoint.endsWith("/") ? cdnEndpoint : cdnEndpoint + "/";
        return endpoint + key;
    }

    /**
     * Rolls back uploaded files by deleting them.
     *
     * @param keys The keys of files to delete
     */
    private void rollbackUploadedFiles(List<String> keys) {
        for (String key : keys) {
            try {
                deleteFile(key);
            } catch (Exception ignored) {
                // Best effort cleanup - log this in production
            }
        }
    }
}
