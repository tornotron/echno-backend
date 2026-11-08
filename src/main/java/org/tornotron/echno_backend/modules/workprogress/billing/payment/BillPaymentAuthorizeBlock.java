package org.tornotron.echno_backend.modules.workprogress.billing.payment;

/**
 * Why the reader cannot authorize a requested payment, so the page can say so in place of the button.
 */
public enum BillPaymentAuthorizeBlock {
    /** The project has no payment authorizer, or the one named is no longer active. */
    NO_AUTHORIZER,
    /** Someone else is the project's payment authorizer. */
    NOT_THE_AUTHORIZER,
    /** The reader is the authorizer and raised this payment, so someone else has to sign it. */
    OWN_REQUEST,
    /** The project's only authorizer raised this payment; it waits until another is named. */
    RAISED_BY_THE_AUTHORIZER
}
