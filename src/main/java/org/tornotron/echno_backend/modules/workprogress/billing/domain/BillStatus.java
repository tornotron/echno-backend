package org.tornotron.echno_backend.modules.workprogress.billing.domain;

/**
 * Where a bill stands. Draft and returned bills are edited by the preparer; a submitted bill is measured; a verified bill is certified; a certified bill is approved, which hands it to finance.
 */
public enum BillStatus {
    DRAFT,
    SUBMITTED,
    VERIFIED,
    CERTIFIED,
    APPROVED,
    RETURNED,
    CANCELLED;

    /** Whether the claim (quantities or claimed percent) may still be edited. */
    public boolean isEditable() {
        return this == DRAFT || this == RETURNED;
    }

    /** Whether the bill is still open, so no other bill may be opened on the same contract. */
    public boolean isOpen() {
        return this != APPROVED && this != CANCELLED;
    }

    /** Whether the bill's figures count as certified for the running account. */
    public boolean isCertified() {
        return this == CERTIFIED || this == APPROVED;
    }
}
