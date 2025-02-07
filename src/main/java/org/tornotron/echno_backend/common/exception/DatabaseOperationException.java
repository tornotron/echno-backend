package org.tornotron.echno_backend.common.exception;

public class DatabaseOperationException extends RuntimeException {
    public DatabaseOperationException(String message) {
        super(message);
    }
}
