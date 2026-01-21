package org.tornotron.echno_backend.billing;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "subscription_item")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubscriptionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature_id")
    private Feature feature;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_feature_id")
    private PlanFeature planFeature;

    @Builder.Default
    private Integer quantity = 1;

    @Column(precision = 10, scale = 2)
    private BigDecimal unitPrice;

    // Override plan limits
    private Long overrideQuotaLimit;
    private Boolean overrideEnabled;
}
