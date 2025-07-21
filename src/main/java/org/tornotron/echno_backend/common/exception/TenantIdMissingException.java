package org.tornotron.echno_backend.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class TenantIdMissingException extends RuntimeException {
    public TenantIdMissingException(String message) {
        super(message);
    }
}
