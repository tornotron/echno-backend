package org.tornotron.echno_backend.modules.workprogress.billing.domain;

/**
 * How far an approved bill has been paid, worked out from its payable. Not a workflow state: the
 * bill stays {@link BillStatus#APPROVED}, so a part-paid bill does not hold up the next one.
 */
public enum BillPaymentState {
    /** The net came to nothing, so no payable was raised. */
    NOTHING_PAYABLE,
    UNPAID,
    PART_PAID,
    PAID
}
