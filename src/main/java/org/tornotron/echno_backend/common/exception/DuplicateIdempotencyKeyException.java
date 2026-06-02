package org.tornotron.echno_backend.common.exception;

public class DuplicateIdempotencyKeyException extends RuntimeException {
    private final String existingResourceId;
    public DuplicateIdempotencyKeyException(String key, String existingResourceId) {
        super("Idempotency key already used: "+ key);
        this.existingResourceId = existingResourceId;
    }
    public String getExistingResourceId() { return existingResourceId; }
}
