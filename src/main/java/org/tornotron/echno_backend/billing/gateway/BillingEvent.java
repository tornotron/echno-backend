package org.tornotron.echno_backend.billing.gateway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The webhook inbox: one row per provider event, written after the signature verifies and
 * before anything is projected. The unique key on {@code (provider, provider_event_id)} is the
 * idempotency guard: a redelivered or replayed event is a duplicate insert, acknowledged and
 * dropped, so it cannot double-extend a period or double-grant.
 *
 * <p>Global by nature: the row exists before the organization is known, and the organization
 * is resolved from the payload by the projector, never from a session. It is therefore not a
 * {@code TenantScopedEntity}, and the code that reads it runs under a {@code @WithoutTenant}
 * declaration that says so.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "billing_event",
        uniqueConstraints = @UniqueConstraint(name = "uk_billing_event_provider_event",
                columnNames = {"provider", "provider_event_id"}),
        indexes = {
                @Index(name = "idx_billing_event_status", columnList = "status"),
                @Index(name = "idx_billing_event_subscription", columnList = "provider, providerSubscriptionId")
        })
public class BillingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderId provider;

    @Column(name = "provider_event_id", nullable = false, length = 128)
    private String providerEventId;

    @Column(length = 64)
    private String eventType;

    /** Null until the projector resolves it from the payload. */
    private Long organizationId;

    @Column(length = 100)
    private String providerSubscriptionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    @Builder.Default
    private Boolean signatureVerified = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BillingEventStatus status = BillingEventStatus.RECEIVED;

    @Column(nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(nullable = false, updatable = false)
    private Instant receivedAt;

    /** When the provider says the event happened. */
    private Instant occurredAt;

    private Instant processedAt;

    /** The {@link #occurredAt} of the event as applied; the order-tolerance watermark. */
    private Instant lastAppliedAt;

    @Column(columnDefinition = "TEXT")
    private String lastError;
}
