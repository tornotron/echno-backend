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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;

import java.time.Instant;

/**
 * Which provider plan id currently stands for an internal plan code on an interval. Provider
 * plans are immutable once created, so a price change creates a new provider plan and a new
 * row here, and the previous row is marked not current rather than deleted, because
 * subscriptions already on the old provider plan keep referring to it. Global, like the plan
 * catalog it maps.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "gateway_plan_ref",
        indexes = {
                @Index(name = "idx_gateway_plan_ref_lookup", columnList = "provider, planCode, billingInterval"),
                @Index(name = "idx_gateway_plan_ref_provider_plan", columnList = "provider, providerPlanId")
        })
public class GatewayPlanMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String planCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderId provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_interval", nullable = false, length = 20)
    private BillingPeriod billingInterval;

    @Column(nullable = false, length = 100)
    private String providerPlanId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isCurrent = true;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
