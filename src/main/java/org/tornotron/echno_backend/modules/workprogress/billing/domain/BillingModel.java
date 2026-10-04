package org.tornotron.echno_backend.modules.workprogress.billing.domain;

/**
 * How a contract is billed. The first bill on a contract fixes it; a bill of the other model is refused.
 */
public enum BillingModel {
    /** Periodic bills of quantities measured against the contract BOQ. */
    RUNNING_ACCOUNT,
    /** Bills against one contract milestone, as a percent of its value. */
    MILESTONE
}
