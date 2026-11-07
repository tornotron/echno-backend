package org.tornotron.echno_backend.modules.workprogress.billing.payment;

import java.util.Set;

/** Where one attempt to pay a bill stands. See section 5 of the billing payments design note. */
public enum BillPaymentStatus {
    /** Raised and waiting for the second signature. */
    REQUESTED,
    /** Signed. Waiting for the bank reference when manual; being sent, or to be sent again, by a provider. */
    AUTHORIZED,
    /** The provider has it and has not said how it ended. */
    PROCESSING,
    /** The money went out and is on the payable. */
    SETTLED,
    /** The provider could not pay it. The payable was never touched. */
    FAILED,
    /** Declined before any money moved. */
    REJECTED,
    /** The bank returned a settled payment, and the payable was given it back. */
    REVERSED;

    /** The statuses that still hold a bill's one payment slot. */
    public static final Set<BillPaymentStatus> IN_FLIGHT = Set.of(REQUESTED, AUTHORIZED, PROCESSING);

    public boolean isInFlight() {
        return IN_FLIGHT.contains(this);
    }
}
