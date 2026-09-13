package org.tornotron.echno_backend.billing.gateway;

/**
 * Which payment provider backs a subscription. {@link #MANUAL} is the value every row carried
 * before a gateway existed, and what the no-op gateway reports when no provider is configured:
 * the subscription was provisioned by an admin or a trial and has no provider-side twin.
 */
public enum ProviderId {
    MANUAL,
    RAZORPAY
}
