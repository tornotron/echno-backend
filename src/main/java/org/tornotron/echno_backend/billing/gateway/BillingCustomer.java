package org.tornotron.echno_backend.billing.gateway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.Instant;

/**
 * The provider customer that stands for an organization, one row per organization per
 * provider. Created once by {@code ensureCustomer} and reused; the webhook projector also reads
 * it in reverse, from the provider customer id on an event back to the organization. A provider
 * customer belongs to exactly one organization, which the second unique key enforces: Razorpay
 * would otherwise hand two organizations sharing an email and phone the same customer.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "billing_customer",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_billing_customer_org_provider", columnNames = {"organization_id", "provider"}),
                @UniqueConstraint(name = "uk_billing_customer_provider_customer", columnNames = {"provider", "provider_customer_id"})
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class BillingCustomer implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderId provider;

    @Column(nullable = false, length = 100)
    private String providerCustomerId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
