package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.GatewayPlanMapping;
import org.tornotron.echno_backend.billing.gateway.ProviderId;

import java.util.Optional;

public interface GatewayPlanMappingRepository extends JpaRepository<GatewayPlanMapping, Long> {

    Optional<GatewayPlanMapping> findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(
            ProviderId provider, String planCode, BillingPeriod billingInterval);

    Optional<GatewayPlanMapping> findFirstByProviderAndProviderPlanId(ProviderId provider, String providerPlanId);
}
