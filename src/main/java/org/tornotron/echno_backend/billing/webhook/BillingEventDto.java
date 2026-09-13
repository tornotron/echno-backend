package org.tornotron.echno_backend.billing.webhook;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingEventStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;

import java.time.Instant;

/**
 * One webhook inbox row as the system administrator sees it. The verified payload stays in
 * the database: what an operator needs to decide on a retry is the type, the organization,
 * the attempt count and the last error, and the payload carries customer detail that has no
 * business on a list screen.
 */
@Schema(description = "A webhook inbox row: what arrived, what became of it, and why.")
public record BillingEventDto(
        @Schema(description = "Inbox row id.", example = "42") Long id,
        @Schema(description = "Provider the event came from.") ProviderId provider,
        @Schema(description = "The provider's own event id.", example = "evt_ABC123") String providerEventId,
        @Schema(description = "Provider event type as delivered.", example = "subscription.charged") String eventType,
        @Schema(description = "Organization the event was resolved to, null until the projector resolved it.") Long organizationId,
        @Schema(description = "Provider subscription the event concerns, when known.") String providerSubscriptionId,
        @Schema(description = "Where the row is in the inbox.") BillingEventStatus status,
        @Schema(description = "Projection attempts so far.") Integer attemptCount,
        @Schema(description = "When the webhook was received.") Instant receivedAt,
        @Schema(description = "When the provider says the event happened.") Instant occurredAt,
        @Schema(description = "When the row was last processed or skipped.") Instant processedAt,
        @Schema(description = "The last projection error, or why the row was skipped.") String lastError) {

    public static BillingEventDto from(BillingEvent row) {
        return new BillingEventDto(row.getId(), row.getProvider(), row.getProviderEventId(), row.getEventType(),
                row.getOrganizationId(), row.getProviderSubscriptionId(), row.getStatus(), row.getAttemptCount(),
                row.getReceivedAt(), row.getOccurredAt(), row.getProcessedAt(), row.getLastError());
    }
}
