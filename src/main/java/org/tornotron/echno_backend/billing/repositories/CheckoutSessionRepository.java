package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.billing.checkout.CheckoutSession;
import org.tornotron.echno_backend.billing.checkout.CheckoutSessionStatus;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.ProviderId;

import java.time.Instant;
import java.util.Optional;

public interface CheckoutSessionRepository extends JpaRepository<CheckoutSession, Long> {

    Optional<CheckoutSession> findFirstByProviderAndProviderSubscriptionId(ProviderId provider, String providerSubscriptionId);

    Optional<CheckoutSession> findFirstByProviderAndProviderOrderId(ProviderId provider, String providerOrderId);

    /** The organization's newest still-open checkout for a plan and cycle, so a retry reuses it. */
    Optional<CheckoutSession> findFirstByOrganization_IdAndPlanCodeAndBillingPeriodAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
            Long organizationId, String planCode, BillingPeriod billingPeriod, CheckoutSessionStatus status, Instant now);
}
