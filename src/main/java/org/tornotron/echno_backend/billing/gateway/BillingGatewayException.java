package org.tornotron.echno_backend.billing.gateway;

/** A provider call or a provider payload could not be completed or understood. */
public class BillingGatewayException extends RuntimeException {

    public BillingGatewayException(String message) {
        super(message);
    }

    public BillingGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
