package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

@Schema(description = "One billing event as the organization's payment history shows it: a charge, an invoice, a failure.")
@Value
@Builder
public class BillingEventSummaryDto {

    @Schema(description = "Numeric id of the event row.", example = "512")
    Long id;

    @Schema(description = "The provider's event type.", example = "subscription.charged")
    String eventType;

    @Schema(description = "The provider's event id.", example = "evt_Nx8k5TuW3dBe4f")
    String providerEventId;

    @Schema(description = "When the provider says it happened.", example = "2026-09-01T00:00:12Z")
    Instant occurredAt;

    @Schema(description = "Amount the event carries, in paise, when it names one.", example = "999900")
    Long amountPaise;

    @Schema(description = "Currency of that amount.", example = "INR")
    String currency;

    @Schema(description = "Provider payment or invoice reference, when the event carries one.", example = "pay_Nx8k3RtU1bZc2d")
    String reference;

    @Schema(description = "What the inbox did with the event.", example = "PROCESSED")
    String status;

    @Schema(description = "Why a failed or skipped event was not applied, when it was not.")
    String description;
}
