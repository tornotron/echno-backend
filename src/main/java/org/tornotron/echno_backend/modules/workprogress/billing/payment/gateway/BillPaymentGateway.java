package org.tornotron.echno_backend.modules.workprogress.billing.payment.gateway;

import org.tornotron.echno_backend.modules.workprogress.billing.payment.BillPaymentProvider;

/**
 * The port to whatever moves a bill payment from the organization to its contractor. This is the
 * opposite direction from {@code BillingGateway}, which collects a subscription into the platform's
 * own account, and it uses separate keys for that reason. Calls may fail or time out with the outcome
 * unknown, so {@link #send} has to be safe to repeat with the same payment id.
 */
public interface BillPaymentGateway {

    BillPaymentProvider provider();

    /**
     * Hands a payment to the provider.
     *
     * @throws BillPaymentGatewayException if there was no usable answer; the payment may or may not
     *         have been accepted, so the caller keeps it and may send it again
     */
    PayoutResult send(PayoutCommand command);

    /** Asks the provider where a payment stands. */
    PayoutResult fetch(PayoutLookup lookup);
}
