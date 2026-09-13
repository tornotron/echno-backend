package org.tornotron.echno_backend.billing.gateway.dto;

/**
 * The client-side result of a hosted checkout, as the provider's browser widget hands it back:
 * the payment id, the subscription or order it paid, and the provider's signature over the
 * pair. Exactly one of {@code subscriptionId} and {@code orderId} is set.
 */
public record CheckoutSignature(String paymentId, String subscriptionId, String orderId, String signature) {

    public boolean isForSubscription() {
        return subscriptionId != null && !subscriptionId.isBlank();
    }
}
