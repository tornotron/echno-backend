package org.tornotron.echno_backend.common.llm;

/**
 * How one chat completion ended. The client makes one attempt per call and never retries; a
 * caller decides what to do with each outcome, and {@link #isRetryable()} says which ones are
 * worth another attempt after a wait.
 */
public enum ChatCompletionOutcome {

    /** A complete answer: the envelope parsed, it carried message text, and it was not cut short. */
    OK,

    /** Switched off ({@code enabled = false}) or no API key. Nothing was sent. */
    NOT_CONFIGURED,

    /** HTTP 429. */
    RATE_LIMITED,

    /** HTTP 5xx. */
    SERVER_ERROR,

    /** No response within the connect or read timeout. */
    TIMEOUT,

    /** The connection failed for a reason other than a timeout, such as a refused connection. */
    UNREACHABLE,

    /**
     * Any other HTTP 4xx, a status that is neither success nor error (such as a redirect, which is
     * not followed for a POST), or a request that could not be sent at all. Retrying will not help.
     */
    CLIENT_ERROR,

    /** A success status, but the body was empty, not JSON, or held no (or only blank) message text. */
    MALFORMED_ENVELOPE,

    /** The endpoint stopped at the token cap ({@code finish_reason} of {@code length}). Never complete. */
    TRUNCATED;

    /** Whether the same request may succeed if sent again after a wait. */
    public boolean isRetryable() {
        return this == RATE_LIMITED || this == SERVER_ERROR || this == TIMEOUT || this == UNREACHABLE;
    }

    /**
     * Whether the endpoint answered with a success status, so there is a response body to read
     * (possibly malformed or truncated).
     */
    public boolean isAnswered() {
        return this == OK || this == MALFORMED_ENVELOPE || this == TRUNCATED;
    }
}
