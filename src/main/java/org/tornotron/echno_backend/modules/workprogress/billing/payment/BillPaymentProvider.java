package org.tornotron.echno_backend.modules.workprogress.billing.payment;

/** What carries a bill payment. */
public enum BillPaymentProvider {
    /** A person pays from the bank and records the reference. */
    MANUAL,
    /** An in-process stand-in for testing the flow. No money moves. */
    FAKE,
    /** Razorpay's payout product, on the paying organization's own account. */
    RAZORPAY
}
