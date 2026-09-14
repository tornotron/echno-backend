package org.tornotron.echno_backend.billing.reconcile;

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
import org.tornotron.echno_backend.billing.gateway.ProviderId;

import java.time.Instant;

/**
 * A provider object that must be cancelled because the local write after its creation
 * failed. Written before the first cancel is attempted, so a cancel that fails (or a process
 * that dies mid-way) leaves a row the reconciliation sweep retries with backoff; resolved once
 * the provider confirms. Global like the webhook inbox: it names an organization but is not
 * a tenant row, since the sweep that retries it belongs to none.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "billing_compensation",
        uniqueConstraints = @UniqueConstraint(name = "uk_billing_compensation_subscription",
                columnNames = {"provider", "provider_subscription_id"}),
        indexes = @Index(name = "idx_billing_compensation_due", columnList = "resolvedAt, nextAttemptAt"))
public class ProviderCompensation {

    /** Attempts after which the row is left for an operator instead of retried again. */
    public static final int MAX_ATTEMPTS = 12;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderId provider;

    @Column(name = "provider_subscription_id", nullable = false, length = 100)
    private String providerSubscriptionId;

    @Column(nullable = false)
    private Long organizationId;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private Instant resolvedAt;

    public boolean isResolved() {
        return resolvedAt != null;
    }
}
