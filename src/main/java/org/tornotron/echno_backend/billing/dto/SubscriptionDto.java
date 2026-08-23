package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;

import java.time.Instant;

@Schema(description = "A user's subscription to a plan, as returned by the API.")
@Value
@Builder
public class SubscriptionDto {
    @Schema(description = "Numeric id of the subscription.", example = "101")
    Long id;
    @Schema(description = "Id of the subscribing user.", example = "27")
    Long userId;
    @Schema(description = "Plan the subscription is on.")
    PlanDto plan;
    @Schema(description = "Current lifecycle status of the subscription.", example = "ACTIVE")
    SubscriptionStatus status;
    @Schema(description = "Start of the current billing period.", example = "2026-08-01T00:00:00Z")
    Instant currentPeriodStart;
    @Schema(description = "End of the current billing period.", example = "2026-09-01T00:00:00Z")
    Instant currentPeriodEnd;
    @Schema(description = "Start of the trial period, if the subscription began with a trial.", example = "2026-07-18T00:00:00Z")
    Instant trialStart;
    @Schema(description = "End of the trial period, if the subscription began with a trial.", example = "2026-08-01T00:00:00Z")
    Instant trialEnd;
    @Schema(description = "Whether the subscription is set to cancel at the end of the current period.", example = "false")
    Boolean cancelAtPeriodEnd;
    @Schema(description = "When the subscription was canceled, if it has been.", example = "null")
    Instant canceledAt;
    @Schema(description = "When the subscription was first created.", example = "2026-01-15T09:30:00Z")
    Instant createdAt;
    @Schema(description = "Reason given for cancellation, if any.", example = "Switching to annual billing")
    String cancellationReason;
    @Schema(description = "Whether the subscription is currently active.", example = "true")
    boolean active;
    @Schema(description = "Whether the subscription is currently within its trial period.", example = "false")
    boolean inTrial;
    @Schema(description = "Whether the subscription has expired.", example = "false")
    boolean expired;
}
