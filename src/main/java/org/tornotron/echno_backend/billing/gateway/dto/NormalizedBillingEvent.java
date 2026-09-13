package org.tornotron.echno_backend.billing.gateway.dto;

import org.tornotron.echno_backend.billing.gateway.NormalizedEventType;
import org.tornotron.echno_backend.billing.gateway.NormalizedMandateStatus;
import org.tornotron.echno_backend.billing.gateway.MandateMethod;
import org.tornotron.echno_backend.billing.gateway.ProviderId;

import java.time.Instant;

/**
 * One provider webhook event in the projector's vocabulary.
 *
 * @param provider Which provider sent it.
 * @param providerEventId The provider's event id, or a digest of the body where the provider
 *        carries the id in a header instead; the inbox key.
 * @param type What happened.
 * @param occurredAt When the provider says it happened; drives order tolerance.
 * @param organizationId The organization, when the payload names it (Razorpay {@code notes});
 *        null when it must be resolved from the customer mapping.
 * @param providerSubscriptionId The provider subscription the event is about, when any.
 * @param providerCustomerId The provider customer, when the payload carries one.
 * @param providerPlanId The provider plan the subscription is on, when the payload carries one.
 * @param planCode The internal plan code when the payload names it in notes.
 * @param subscription The provider's snapshot of the subscription carried on the event, when any.
 * @param mandateReference The mandate/token reference for mandate events, when any.
 * @param mandateMethod How the mandate was registered, for mandate events.
 * @param mandateStatus The mandate state for mandate events.
 * @param mandateMaxAmountPaise The mandate ceiling for mandate events, when the payload carries one.
 */
public record NormalizedBillingEvent(
        ProviderId provider,
        String providerEventId,
        NormalizedEventType type,
        Instant occurredAt,
        Long organizationId,
        String providerSubscriptionId,
        String providerCustomerId,
        String providerPlanId,
        String planCode,
        GatewaySubscription subscription,
        String mandateReference,
        MandateMethod mandateMethod,
        NormalizedMandateStatus mandateStatus,
        Long mandateMaxAmountPaise) {
}
