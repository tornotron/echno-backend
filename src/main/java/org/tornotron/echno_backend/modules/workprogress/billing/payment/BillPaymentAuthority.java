package org.tornotron.echno_backend.modules.workprogress.billing.payment;

/**
 * The capacity an authorization was given in, kept on the payment. A payment authorized before this
 * was recorded has none.
 */
public enum BillPaymentAuthority {
    /** The employee the project names as its payment authorizer. */
    PROJECT_AUTHORIZER
}
