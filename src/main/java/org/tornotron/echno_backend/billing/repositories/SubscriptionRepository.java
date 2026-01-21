package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    @Query("SELECT s FROM Subscription s " +
           "LEFT JOIN FETCH s.plan p " +
           "LEFT JOIN FETCH p.planFeatures pf " +
           "LEFT JOIN FETCH pf.feature " +
           "WHERE s.userId = :userId AND s.status IN :activeStatuses")
    Optional<Subscription> findActiveSubscriptionByUserId(
            @Param("userId")Long userId,
            @Param("activeStatuses")List<SubscriptionStatus> activeStatuses
            );

    default Optional<Subscription> findActiveSubscriptionByUserId(Long userId) {
        return findActiveSubscriptionByUserId(userId,
                Arrays.asList(SubscriptionStatus.ACTIVE,SubscriptionStatus.TRIALING));
    }

    @Query("SELECT s FROM Subscription s WHERE s.currentPeriodEnd < :now " +
           "AND s.status IN ('ACTIVE', 'TRIALING', 'PAST_DUE')")
    List<Subscription> findExpiredSubscriptions(@Param("now")Instant now);

    List<Subscription> findByUserIdOrderByCreatedAtDesc(Long userId);
}
