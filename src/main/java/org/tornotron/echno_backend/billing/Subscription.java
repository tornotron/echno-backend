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

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Table(name = "subscription", indexes = {
        @Index(name = "idx_subscription_user", columnList = "userId"),
        @Index(name = "idx_subscription_status", columnList = "status"),
        @Index(name = "idx_subscription_period_end", columnList = "currentPeriodEnd")
})
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
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

    @OneToMany(mappedBy = "subscription",cascade = CascadeType.ALL, orphanRemoval = true)
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
