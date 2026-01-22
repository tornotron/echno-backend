package org.tornotron.echno_backend.common.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.tornotron.echno_backend.common.dto.StoredFile;
import org.tornotron.echno_backend.common.exception.FileUploadException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Service for handling file storage operations with S3-compatible object storage.
 * Provides reusable methods for uploading single or multiple files across all modules.
 */
@Service
public class FileStorageService {

    private final S3Client s3Client;

    @Value("${digital-ocean.bucket-name}")
    private String bucketName;

    @Value("${digital-ocean.cdn-endpoint}")
    private String cdnEndpoint;

    public FileStorageService(S3Client s3Client) {
        this.s3Client = s3Client;
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
            throw new FileUploadException("Failed to upload file: " + file.getOriginalFilename(), e);
        }

        String fileUrl = buildFileUrl(key);

        return new StoredFile(key, fileUrl, file.getContentType(), file.getSize());
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
            throw new FileUploadException("Batch upload failed. Rolled back " + uploadedKeys.size() + " uploaded files.", e);
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
            throw new FileUploadException("File cannot be null or empty");
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
