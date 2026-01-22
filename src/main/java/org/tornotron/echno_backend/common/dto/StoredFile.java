package org.tornotron.echno_backend.common.dto;

/**
 * Record representing a file stored in object storage.
 * Contains metadata about the uploaded file.
 *
 * @param key         The unique key/path of the file in the storage bucket
 * @param url         The public URL to access the file
 * @param contentType The MIME type of the file
 * @param size        The size of the file in bytes
 */
public record StoredFile(
        String key,
        String url,
        String contentType,
        Long size) {
}
