package org.tornotron.echno_backend.billing.checkout;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.MandateMethod;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedEntity;
import org.tornotron.echno_backend.organization.Organization;

import java.time.Instant;

/**
 * One hosted checkout an organization opened for a plan: which provider subscription (or
 * order) was created for it, what the cycle costs, the mandate terms the buyer was shown, and
 * whether the buyer's result has been verified. The entitlement itself is never written here;
 * it lives on the {@code Subscription} projection, which the verify step and the webhook
 * projector both converge on through the provider subscription id.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "checkout_session",
        indexes = {
                @Index(name = "idx_checkout_session_org", columnList = "organization_id"),
                @Index(name = "idx_checkout_session_provider_sub", columnList = "provider, providerSubscriptionId"),
                @Index(name = "idx_checkout_session_provider_order", columnList = "provider, providerOrderId")
        })
@Filter(name = "orgFilter", condition = "organization_id = :organizationId")
public class CheckoutSession implements TenantScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderId provider;

    @Column(nullable = false, length = 50)
    private String planCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingPeriod billingPeriod;

    /** Set for a recurring checkout. */
    @Column(length = 100)
    private String providerSubscriptionId;

    /** Set for a one-off checkout. */
    @Column(length = 100)
    private String providerOrderId;

    /** The payment the buyer's verified result named. */
    @Column(length = 100)
    private String providerPaymentId;

    @Column(nullable = false)
    private Long amountPaise;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    @Builder.Default
    private Boolean recurring = true;

    /** The cycle is above the RBI cap, so every charge needs the buyer to authenticate. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean perChargeApproval = false;

    /** The buyer accepted per-charge authentication, either on the session or on the mandate step. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean perChargeApprovalAccepted = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MandateMethod mandateMethod = MandateMethod.UNKNOWN;

    /** The ceiling the mandate is registered for: the cycle amount. */
    private Long mandateAmountCapPaise;

    @Column(columnDefinition = "TEXT")
    private String authUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CheckoutSessionStatus status = CheckoutSessionStatus.OPEN;

    /** The projection row this checkout activated, once verified. */
    private Long subscriptionId;

    private Long createdByUserId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant verifiedAt;
}
