package org.tornotron.echno_backend.billing.entitlement;

/**
 * How a refused entitlement is treated, set per environment by {@code echno.entitlement.mode}.
 */
public enum EntitlementMode {
    /** Log the would-be denial and allow the call. The default until payments exist. */
    ADVISORY,
    /** Refuse the call with the standard 402 and its structured reason. */
    ENFORCE
}
