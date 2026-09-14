package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.reconcile.ProviderCompensation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProviderCompensationRepository extends JpaRepository<ProviderCompensation, Long> {

    Optional<ProviderCompensation> findByProviderAndProviderSubscriptionId(ProviderId provider, String providerSubscriptionId);

    /** Unresolved rows whose next attempt is due and that have attempts left, oldest first. */
    @Query("SELECT c FROM ProviderCompensation c WHERE c.resolvedAt IS NULL AND c.nextAttemptAt <= :now "
            + "AND c.attemptCount < :maxAttempts ORDER BY c.nextAttemptAt ASC")
    List<ProviderCompensation> findDue(@Param("now") Instant now, @Param("maxAttempts") int maxAttempts, Pageable page);
}
