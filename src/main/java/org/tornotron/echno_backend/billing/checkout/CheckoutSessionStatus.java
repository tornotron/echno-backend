package org.tornotron.echno_backend.billing.checkout;

/** Where a hosted checkout is in its short life. */
public enum CheckoutSessionStatus {
    /** Opened with the provider; the buyer has not come back yet. */
    OPEN,
    /** The buyer's result verified and the entitlement projected. */
    VERIFIED,
    /** The buyer never came back before the session's expiry. */
    EXPIRED
}
