package org.tornotron.echno_backend.modules.workprogress.billing.domain;

/** Where a project stands on billing, for the home page. */
public enum ProjectBillingState {
    /** The project has no sub-contract to bill. */
    NO_CONTRACTS,
    /** It has contracts, and no bill has been opened on them. */
    NOT_STARTED,
    /** A bill is in progress and nothing approved is waiting to be paid. */
    IN_PROGRESS,
    /** An approved bill still has an amount due. */
    PAYMENT_DUE,
    /** Every approved bill is paid and no bill is open. */
    SETTLED
}
