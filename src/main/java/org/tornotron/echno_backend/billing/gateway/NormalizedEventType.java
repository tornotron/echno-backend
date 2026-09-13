package org.tornotron.echno_backend.billing.gateway;

/** A provider webhook event, named without provider vocabulary. */
public enum NormalizedEventType {
    SUBSCRIPTION_AUTHENTICATED,
    SUBSCRIPTION_ACTIVATED,
    SUBSCRIPTION_CHARGED,
    SUBSCRIPTION_PENDING,
    SUBSCRIPTION_HALTED,
    SUBSCRIPTION_PAUSED,
    SUBSCRIPTION_RESUMED,
    SUBSCRIPTION_CANCELLED,
    SUBSCRIPTION_COMPLETED,
    MANDATE_AUTHORIZED,
    MANDATE_REVOKED,
    PAYMENT_FAILED,
    INVOICE_PAID,
    /** A provider event the adapter recognises the envelope of but has no projection for. */
    IGNORED
}
