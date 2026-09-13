package org.tornotron.echno_backend.billing.gateway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
 * A recurring-payment mandate an organization has registered with a provider: the method, the
 * ceiling it was authorized for, and where it is in its lifecycle. Written by the webhook
 * projector from mandate events; the AFA cap decision in {@link MandatePolicy} is checked
 * against {@link #maxAmountPaise}.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "payment_mandate",
        indexes = {
                @Index(name = "idx_payment_mandate_org", columnList = "organization_id")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_payment_mandate_ref", columnNames = {"provider", "provider_mandate_ref"}))
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class PaymentMandate implements TenantScopedEntity {

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
    private String providerMandateRef;

    /** The provider subscription this mandate was registered for, when known. */
    @Column(length = 100)
    private String providerSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MandateMethod method = MandateMethod.UNKNOWN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private NormalizedMandateStatus status = NormalizedMandateStatus.CREATED;

    private Long maxAmountPaise;

    private Instant authorizedAt;

    private Instant revokedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
