package org.tornotron.echno_backend.billing.gateway;

/**
 * A checkout was asked for in an environment with no payment provider behind the gateway. The
 * web maps the 409 this becomes to its "billing not configured" state.
 */
public class BillingNotConfiguredException extends RuntimeException {

    public BillingNotConfiguredException() {
        super("No billing provider is configured (echno.billing.provider=none); "
                + "online checkout is unavailable and subscriptions are provisioned manually");
    }
}
