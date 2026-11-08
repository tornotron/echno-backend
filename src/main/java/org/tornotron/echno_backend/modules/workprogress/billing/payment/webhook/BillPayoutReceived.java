package org.tornotron.echno_backend.modules.workprogress.billing.payment.webhook;

import java.util.UUID;
import org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway.PayoutResult;

/** A verified provider event about one bill payment, on its way to the payment. */
public record BillPayoutReceived(UUID paymentId, PayoutResult result) {}
