package org.tornotron.echno_backend.modules.documentingestion.dto;

/** A short-lived presigned read, issued only after the document was found in the caller's tenant. */
public record DownloadUrlDto(String url, long expiresInSeconds) {
}
