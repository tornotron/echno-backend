package org.tornotron.echno_backend.common.dto;

/**
 * A client's declaration of a file it intends to upload directly to storage.
 *
 * <p>The server presigns a URL for it and checks it against existing
 * attachments, without the file ever passing through the application.
 *
 * @param filename    the original name, used for the storage key and dedup
 * @param contentType the type the upload will declare; it is signed, so the
 *                    client must send exactly this on the PUT
 * @param fileSize    declared size in bytes, used for duplicate detection
 */
public record UploadRequest(
        String filename,
        String contentType,
        Long fileSize
) {
}
