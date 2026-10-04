package org.tornotron.echno_backend.modules.workprogress.billing.domain;

/**
 * Where a milestone requirement stands. A milestone bill is certified only when every mandatory requirement is completed or not applicable.
 */
public enum RequirementStatus {
    PENDING,
    UNDER_REVIEW,
    COMPLETED,
    NOT_APPLICABLE;

    /** Whether the requirement no longer stands in the way of certification. */
    public boolean isSatisfied() {
        return this == COMPLETED || this == NOT_APPLICABLE;
    }
}
