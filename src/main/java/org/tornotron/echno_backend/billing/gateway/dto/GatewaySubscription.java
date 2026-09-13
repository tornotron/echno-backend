package org.tornotron.echno_backend.billing.gateway.dto;

import org.tornotron.echno_backend.billing.gateway.NormalizedSubscriptionStatus;

import java.time.Instant;

/**
 * A provider subscription as the port reports it.
 *
 * @param providerSubscriptionId The provider's id.
 * @param providerPlanId The provider plan it is on.
 * @param providerCustomerId The provider customer it belongs to, when the provider reports one.
 * @param status The normalized state.
 * @param currentPeriodStart Start of the current paid period, when known.
 * @param currentPeriodEnd End of the current paid period, when known.
 * @param nextChargeAt When the provider will next debit, when known.
 * @param mandateReference The provider's mandate/token reference, when known.
 * @param authUrl The hosted authorization page while the subscription is pending authorization.
 */
public record GatewaySubscription(
        String providerSubscriptionId,
        String providerPlanId,
        String providerCustomerId,
        NormalizedSubscriptionStatus status,
        Instant currentPeriodStart,
        Instant currentPeriodEnd,
        Instant nextChargeAt,
        String mandateReference,
        String authUrl) {
}
