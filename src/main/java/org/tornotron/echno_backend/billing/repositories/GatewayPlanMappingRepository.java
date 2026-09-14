package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.GatewayPlanMapping;
import org.tornotron.echno_backend.billing.gateway.ProviderId;

import java.util.Optional;

public interface GatewayPlanMappingRepository extends JpaRepository<GatewayPlanMapping, Long> {

    Optional<GatewayPlanMapping> findByProviderAndPlanCodeAndBillingIntervalAndIsCurrentTrue(
            ProviderId provider, String planCode, BillingPeriod billingInterval);

    Optional<GatewayPlanMapping> findFirstByProviderAndProviderPlanId(ProviderId provider, String providerPlanId);

    /**
     * Retires the current mapping and records the fresh one in one transaction, so a reader
     * between the two writes sees either the old plan or the new, never none or both. The
     * provider plan behind {@code fresh} must already exist; a failure here leaves the
     * provider with an unreferenced plan and the mapping table as it was.
     */
    @Transactional
    default GatewayPlanMapping swapCurrent(GatewayPlanMapping stale, GatewayPlanMapping fresh) {
        if (stale != null) {
            stale.setIsCurrent(false);
            saveAndFlush(stale);
        }
        return save(fresh);
    }
}
