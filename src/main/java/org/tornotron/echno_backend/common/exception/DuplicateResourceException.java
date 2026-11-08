package org.tornotron.echno_backend.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateResourceException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "Data already exists";

    /**
     * The request field the duplicate is about, or null when the conflict is not
     * tied to one field. When set, the handler reports it under {@code errors} so a
     * form can mark that field.
     */
    private final String field;

    public DuplicateResourceException() {
        super(DEFAULT_MESSAGE);
        this.field = null;
    }

    public DuplicateResourceException(String message) {
        super(message);
        this.field = null;
    }

    public DuplicateResourceException(String message, String field) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
