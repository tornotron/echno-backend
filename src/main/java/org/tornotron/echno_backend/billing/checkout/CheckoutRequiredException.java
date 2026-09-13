package org.tornotron.echno_backend.billing.checkout;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The self-service subscription endpoints were asked to put a paid plan on record directly
 * while a payment provider is wired. A paid plan only becomes an entitlement through the
 * provider: the checkout creates the provider subscription and the projection follows the
 * provider's answer, so the caller is sent there.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class CheckoutRequiredException extends RuntimeException {

    public CheckoutRequiredException(String message) {
        super(message);
    }
}
