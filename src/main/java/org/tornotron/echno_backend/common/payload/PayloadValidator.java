package org.tornotron.echno_backend.common.payload;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Runs bean validation over a payload that Spring did not bind, and refuses one that fails.
 *
 * <p>Spring validates a {@code @RequestBody} because it binds the bean itself. A payload that
 * arrives as the JSON string part of a multipart request is deserialized by the application, so
 * nothing in the framework ever sees a bean and no constraint on it fires. Every such payload has
 * to be handed to this class instead, which is what {@link JsonPartBinder} does for the request
 * path and what a service does for any caller that reaches it another way.
 *
 * <p>One implementation rather than a private copy per service. The copies were identical, and a
 * rule that is re-typed for each new payload is a rule the twelfth payload will be written without.
 *
 * <p>The {@link ConstraintViolationException} it raises is mapped by
 * {@code GlobalExceptionHandler} to the same 400 and the same {@code errors} map that a bound
 * payload's failure produces, so the two paths answer a caller identically.
 */
@Component
public class PayloadValidator {

    private final Validator validator;

    public PayloadValidator(Validator validator) {
        this.validator = validator;
    }

    /**
     * Checks every constraint declared on the payload.
     *
     * @param payload The payload to check.
     * @param <T> The payload type.
     * @return The same payload, so the call can be chained onto the one that produced it.
     * @throws ConstraintViolationException if any constraint on the payload fails.
     */
    public <T> T requireValid(T payload) {
        Set<ConstraintViolation<T>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return payload;
    }
}
