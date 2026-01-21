package org.tornotron.echno_backend.billing.dto;

import lombok.Builder;
import lombok.Value;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;

import java.time.Instant;

@Value
@Builder
public class SubscriptionDto {
    Long id;
    Long userId;
    PlanDto plan;
    SubscriptionStatus status;
    Instant currentPeriodStart;
    Instant currentPeriodEnd;
    Instant trialStart;
    Instant trialEnd;
    Boolean cancelAtPeriodEnd;
    Instant canceledAt;
    Instant createdAt;
    String cancellationReason;
    boolean active;
    boolean inTrial;
    boolean expired;
}
