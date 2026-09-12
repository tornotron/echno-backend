package org.tornotron.echno_backend.common.exception;

/**
 * The request was well formed but names something the tenant does not have, so it cannot be
 * acted on: an inspection trade slug with no row in the organisation, for example. Mapped to
 * 422, which separates it from the 400 a malformed payload gets and the 404 a missing path
 * resource gets.
 */
public class UnprocessableRequestException extends RuntimeException {

    public UnprocessableRequestException(String message) {
        super(message);
    }
}
