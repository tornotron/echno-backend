package org.tornotron.echno_backend.common.dto;

/**
 * A client's confirmation that an object has been uploaded to storage, so the
 * application can record it as an attachment.
 *
 * <p>The key must be one the server issued from a prior presign call. The
 * server does not trust the client's word that the object exists: it verifies
 * the object is present in storage before recording it.
 *
 * @param key         the storage key returned by the presign step
 * @param filename    original filename, for display
 * @param contentType the object's content type
 * @param fileSize    size in bytes
 */
public record RegisterUploadRequest(
        String key,
        String filename,
        String contentType,
        Long fileSize
) {
}
