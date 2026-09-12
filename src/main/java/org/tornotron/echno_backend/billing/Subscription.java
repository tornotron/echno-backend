package org.tornotron.echno_backend.billing;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * An organization's entitlement to a {@link Plan}, the core record of the SaaS billing model.
 *
 * <p>Keyed on the organization, because a plan describes a tier an organization buys and its
 * caps ({@code maxUsers}) only mean anything against one. The user who bought it is kept as
 * {@link #userId} for the record; it plays no part in any entitlement decision.
 *
 * <p>Holds the current billing period bounds, optional trial window, and the lifecycle
 * status (active, trialing, canceled). Cancellation can take effect immediately or at
 * period end. The status helpers ({@link #isActive()}, {@link #isInTrial()},
 * {@link #isExpired()}) derive state from the status and the period/trial timestamps.
 */
@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Table(name = "subscription", indexes = {
        @Index(name = "idx_subscription_organization", columnList = "organizationId"),
        @Index(name = "idx_subscription_status", columnList = "status"),
        @Index(name = "idx_subscription_period_end", columnList = "currentPeriodEnd")
})
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    /** The user who bought the subscription. Informational; nothing is keyed on it. */
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    @Column(nullable = false)
    private Instant currentPeriodStart;

    @Column(nullable = false)
    private Instant currentPeriodEnd;

    private Instant trialStart;
    private Instant trialEnd;

    @Builder.Default
    private Boolean cancelAtPeriodEnd = false;

    private Instant canceledAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(columnDefinition = "TEXT")
    private String cancellationReason;

    private String externalSubscriptionId;

    @OneToMany(mappedBy = "subscription", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private Set<SubscriptionItem> subscriptionItems = new HashSet<>();

    public boolean isActive() {
        return status == SubscriptionStatus.ACTIVE ||
                status == SubscriptionStatus.TRIALING;
    }

    public boolean isInTrial() {
        return status == SubscriptionStatus.TRIALING &&
                trialEnd != null &&
                Instant.now().isBefore(trialEnd);
    }


    public boolean isExpired() {
        return currentPeriodEnd != null &&
                Instant.now().isAfter(currentPeriodEnd);
    }
}
