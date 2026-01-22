package org.tornotron.echno_backend.common.exception;

/**
 * Exception thrown when a file upload operation fails.
 */
public class FileUploadException extends RuntimeException {

    public FileUploadException(String message) {
        super(message);
    }

    public FileUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
