package org.tornotron.echno_backend.modules.workprogress.billing.domain;

/**
 * What happened to a bill, for its timeline.
 */
public enum BillEventType {
    CREATED,
    CLAIM_UPDATED,
    SUBMITTED,
    MEASUREMENT_SAVED,
    VERIFIED,
    ADJUSTMENTS_UPDATED,
    CERTIFIED,
    APPROVED,
    RETURNED,
    CANCELLED,
    DOCUMENT_ADDED,
    DOCUMENT_REMOVED,
    NOTE
}
