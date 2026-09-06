package org.tornotron.echno_backend.common.exception;

import java.time.Duration;

/**
 * Raised when a caller has used up the attempts allowed for an operation within its window.
 *
 * <p>Carries how long the caller should wait, which becomes the {@code Retry-After} header. The
 * message deliberately says only that the allowance is spent, never how many attempts remain or
 * which of the allowances refused, because that would tell whoever provoked it how to pace the
 * next run.
 */
public class TooManyAttemptsException extends RuntimeException {

    private final transient Duration retryAfter;

    public TooManyAttemptsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** How long until the allowance has refilled enough for one more attempt. */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
