package org.tornotron.echno_backend.common.dto;

/**
 * A short-lived, write-only URL for uploading a single object directly to
 * storage, along with the key the object will occupy.
 *
 * <p>Uploading direct to storage keeps large files off the application and out
 * of the CDN in front of it, which caps request bodies at 100 MB and times out
 * at 100 seconds.
 *
 * @param key         where the object will live; pass this back when registering it
 * @param url         pre-signed PUT target, valid only for this key
 * @param contentType the type the upload must declare, since it is signed
 * @param expiresInSeconds lifetime of the URL
 */
public record PresignedUpload(
        String key,
        String url,
        String contentType,
        long expiresInSeconds
) {
}
