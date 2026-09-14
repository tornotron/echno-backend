package org.tornotron.echno_backend.billing.checkout;

/** Where a hosted checkout is in its short life. */
public enum CheckoutSessionStatus {
    /**
     * Reserved for the organization before the provider is called; at most one per
     * organization at a time (uk_checkout_session_pending). Becomes OPEN once the provider
     * subscription exists, and is deleted when the provider call or the local write fails.
     */
    PENDING,
    /** Opened with the provider; the buyer has not come back yet. */
    OPEN,
    /** The buyer's result verified and the entitlement projected. */
    VERIFIED,
    /** The buyer never came back before the session's expiry. */
    EXPIRED
}
