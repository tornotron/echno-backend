package org.tornotron.echno_backend.billing.gateway;

/** A subscription or mandate request breaks one of the RBI recurring-payment rules. */
public class MandatePolicyViolationException extends IllegalArgumentException {

    public MandatePolicyViolationException(String message) {
        super(message);
    }
}
