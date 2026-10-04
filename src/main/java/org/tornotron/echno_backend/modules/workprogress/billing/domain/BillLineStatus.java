package org.tornotron.echno_backend.modules.workprogress.billing.domain;

/**
 * Where one line of a running account bill stands, derived from its claimed, measured and accepted quantities; never stored.
 */
public enum BillLineStatus {
    /** Nothing claimed on this item in this bill. */
    NOT_CLAIMED,
    /** Claimed and not yet measured. */
    UNDER_REVIEW,
    /** Accepted as claimed. */
    VERIFIED,
    /** Accepted below the claim. */
    PART_ACCEPTED,
    /** Measured and nothing accepted. */
    REJECTED
}
