package org.tornotron.echno_backend.billing.gateway;

/** Where a webhook event is in the inbox. */
public enum BillingEventStatus {
    /** Verified and stored; not yet projected. */
    RECEIVED,
    /** Projected onto the entitlement. */
    PROCESSED,
    /** Projection threw; retried up to the bound, then left for inspection. */
    FAILED,
    /** Deliberately not projected: stale (an earlier event already moved the projection past it), unresolvable, or no-op. */
    SKIPPED
}
