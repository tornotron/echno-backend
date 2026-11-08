package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

/**
 * What a provider says about a payment.
 *
 * @param providerRef The provider's id for it.
 * @param bankReference The bank's reference, once there is one.
 * @param failureReason Why it failed, when it did.
 * @param amountPaise The amount the provider has, when it says; checked against the payment.
 */
public record PayoutResult(
        Outcome outcome,
        String providerRef,
        String bankReference,
        String failureReason,
        Long amountPaise
) {

    public enum Outcome {
        PROCESSING,
        SETTLED,
        FAILED,
        REVERSED
    }
}
