package org.tornotron.echno_backend.common.identity.workos;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Honours WorkOS's rate limit: on a {@code 429}, waits as long as {@code Retry-After} says and asks
 * again, a few times. A wait longer than {@code maxWait} is not waited out here; the {@code 429} goes
 * back to the caller, and a call made from the identity task queue is retried later with its backoff.
 */
final class RetryAfterInterceptor implements ClientHttpRequestInterceptor {

    /** How the wait happens, so a test need not sleep. */
    interface Pause {
        void pause(Duration duration) throws InterruptedException;
    }

    private static final int TOO_MANY_REQUESTS = 429;
    private static final Duration WHEN_UNSAID = Duration.ofSeconds(1);

    private final int retries;
    private final Duration maxWait;
    private final Pause pause;

    RetryAfterInterceptor(int retries, Duration maxWait, Pause pause) {
        this.retries = retries;
        this.maxWait = maxWait;
        this.pause = pause;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        for (int attempt = 0; ; attempt++) {
            ClientHttpResponse response = execution.execute(request, body);
            if (response.getStatusCode().value() != TOO_MANY_REQUESTS || attempt >= retries) {
                return response;
            }
            Duration wait = retryAfter(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            if (wait.compareTo(maxWait) > 0) {
                return response;
            }
            response.close();
            try {
                pause.pause(wait);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting out a WorkOS rate limit", interrupted);
            }
        }
    }

    /** Seconds, or an HTTP date. Anything unreadable waits a second. */
    static Duration retryAfter(String value) {
        if (value == null || value.isBlank()) {
            return WHEN_UNSAID;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException notSeconds) {
            try {
                Duration until = Duration.between(ZonedDateTime.now(),
                        ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME));
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException unreadable) {
                return WHEN_UNSAID;
            }
        }
    }
}
