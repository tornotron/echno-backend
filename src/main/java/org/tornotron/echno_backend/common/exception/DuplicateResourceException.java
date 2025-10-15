package org.tornotron.echno_backend.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateResourceException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "Data already exists";

    public DuplicateResourceException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateResourceException(String message) {
        super(message);
    }
}