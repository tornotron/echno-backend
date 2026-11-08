package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One payment for a provider to send.
 *
 * @param paymentId Sent as the idempotency key and the reference, so the provider's answers name it.
 * @param narration What the contractor sees on the statement.
 */
public record PayoutCommand(
        UUID paymentId,
        BigDecimal amount,
        String narration,
        String beneficiaryName,
        String contactEmail,
        String contactPhone,
        String accountNumber,
        String ifsc
) {
    /** Keeps the bank account out of anything that logs this record. */
    @Override
    public String toString() {
        return "PayoutCommand[paymentId=" + paymentId + ", amount=" + amount + "]";
    }
}
