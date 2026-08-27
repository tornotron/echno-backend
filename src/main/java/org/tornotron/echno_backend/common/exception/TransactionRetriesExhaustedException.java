package org.tornotron.echno_backend.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when a unit of work kept losing serialization conflicts and used up its retries.
 * Distinct from the raw database abort so the request can be answered as the concurrency
 * conflict it is instead of falling into the unknown-error bucket.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class TransactionRetriesExhaustedException extends RuntimeException {

    public TransactionRetriesExhaustedException(String message, Throwable cause) {
        super(message, cause);
    }
}
