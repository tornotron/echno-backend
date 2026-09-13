package org.tornotron.echno_backend.billing.gateway;

import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;

/**
 * A provider's subscription state in the vocabulary the projector understands. Each value maps
 * onto exactly one {@link SubscriptionStatus}, which is what the entitlement gate reads, so no
 * provider term ever reaches the gate. The mapping is the table in section 3.2 of the payment
 * integration design.
 */
public enum NormalizedSubscriptionStatus {
    CREATED(SubscriptionStatus.INCOMPLETE),
    PENDING_AUTH(SubscriptionStatus.INCOMPLETE),
    AUTH_FAILED(SubscriptionStatus.INCOMPLETE_EXPIRED),
    EXPIRED_BEFORE_AUTH(SubscriptionStatus.INCOMPLETE_EXPIRED),
    TRIAL(SubscriptionStatus.TRIALING),
    AUTHENTICATED(SubscriptionStatus.ACTIVE),
    ACTIVE(SubscriptionStatus.ACTIVE),
    PAYMENT_FAILED_RETRYING(SubscriptionStatus.PAST_DUE),
    HALTED(SubscriptionStatus.UNPAID),
    DUNNING_EXHAUSTED(SubscriptionStatus.UNPAID),
    PAUSED(SubscriptionStatus.PAUSED),
    CANCELLED(SubscriptionStatus.CANCELED),
    COMPLETED(SubscriptionStatus.CANCELED);

    private final SubscriptionStatus projected;

    NormalizedSubscriptionStatus(SubscriptionStatus projected) {
        this.projected = projected;
    }

    /** The entitlement status this provider state projects to. */
    public SubscriptionStatus toSubscriptionStatus() {
        return projected;
    }
}
