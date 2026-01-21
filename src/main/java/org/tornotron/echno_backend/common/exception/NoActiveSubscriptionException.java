package org.tornotron.echno_backend.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class NoActiveSubscriptionException extends RuntimeException {
    public NoActiveSubscriptionException(String message) {
        super(message);
    }
}
