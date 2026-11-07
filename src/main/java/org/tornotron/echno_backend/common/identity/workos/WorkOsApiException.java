package org.tornotron.echno_backend.common.identity.workos;

import org.springframework.lang.Nullable;

/**
 * A refusal or failure from the WorkOS API. It carries the HTTP status and WorkOS's error code and
 * message only, never a request or response body, so nothing it holds can be a bearer value.
 */
public class WorkOsApiException extends RuntimeException {

    private final int status;
    @Nullable
    private final String code;

    WorkOsApiException(int status, @Nullable String code, @Nullable String message) {
        super("WorkOS answered " + status + (code == null ? "" : " " + code) + (message == null ? "" : ": " + message));
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    @Nullable
    public String code() {
        return code;
    }

    boolean is(String expected) {
        return expected.equals(code);
    }

    boolean notFound() {
        return status == 404;
    }

    /** {@code 408}, {@code 429} and {@code 5xx} may pass on their own; the identity task queue retries them. */
    public boolean transientFailure() {
        return status == 408 || status == 429 || status >= 500;
    }
}
