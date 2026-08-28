package org.tornotron.echno_backend.common.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Raised when the compliance model call did not produce a usable answer: the endpoint
 * could not be reached, it answered with an error, or it answered with something that is
 * not a complete set of decisions (a response cut short by the token cap, a JSON array
 * that does not close, or a reply that leaves some candidate rules unassessed).
 *
 * <p>It exists so that "the model produced nothing usable" cannot be confused with "the
 * model assessed every rule and none of them applied". Both used to arrive as an empty
 * list, which meant a truncated response was reported to the user as a clean, successful
 * run that happened to create no compliances.
 *
 * <p>Answered as {@code 502 Bad Gateway}: the request was well formed and the caller has
 * nothing to correct, an upstream service is at fault, and the same request is worth
 * retrying once it is behaving.
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
public class ComplianceAiException extends RuntimeException {

    public ComplianceAiException(String message) {
        super(message);
    }

    public ComplianceAiException(String message, Throwable cause) {
        super(message, cause);
    }
}
