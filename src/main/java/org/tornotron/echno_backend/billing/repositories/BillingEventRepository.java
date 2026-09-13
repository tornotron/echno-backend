package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.billing.gateway.BillingEvent;
import org.tornotron.echno_backend.billing.gateway.BillingEventStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BillingEventRepository extends JpaRepository<BillingEvent, Long>, JpaSpecificationExecutor<BillingEvent> {

    Optional<BillingEvent> findByProviderAndProviderEventId(ProviderId provider, String providerEventId);

    /**
     * The projector's claim on a row: locked for the projecting transaction, so two callers
     * (the async listener, the sweep's retry, an administrator's retry) serialize on it and
     * the second sees the first's outcome instead of projecting the same event twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM BillingEvent e WHERE e.id = :id")
    Optional<BillingEvent> findByIdForUpdate(@Param("id") Long id);

    /** The watermark: the latest provider timestamp already applied for this provider subscription. */
    @Query("SELECT MAX(e.lastAppliedAt) FROM BillingEvent e WHERE e.provider = :provider "
            + "AND e.providerSubscriptionId = :subscriptionId AND e.status = :status")
    Optional<Instant> findLastAppliedAt(@Param("provider") ProviderId provider,
                                        @Param("subscriptionId") String providerSubscriptionId,
                                        @Param("status") BillingEventStatus status);

    default Optional<Instant> findLastAppliedAt(ProviderId provider, String providerSubscriptionId) {
        return findLastAppliedAt(provider, providerSubscriptionId, BillingEventStatus.PROCESSED);
    }

    List<BillingEvent> findByStatusInOrderByReceivedAtAsc(List<BillingEventStatus> statuses, Pageable pageable);
}
