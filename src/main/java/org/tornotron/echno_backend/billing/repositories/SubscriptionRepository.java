package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /**
     * The organization's subscription that grants access right now: one in an active status
     * whose current period has not ended. The period check is what stops a row that nothing
     * ever transitioned out of TRIALING from reading as live months after it lapsed.
     */
    @Query("SELECT s FROM Subscription s " +
           "LEFT JOIN FETCH s.plan p " +
           "LEFT JOIN FETCH p.planFeatures pf " +
           "LEFT JOIN FETCH pf.feature " +
           "WHERE s.organizationId = :organizationId AND s.status IN :activeStatuses " +
           "AND s.currentPeriodEnd > :now")
    Optional<Subscription> findActiveSubscription(
            @Param("organizationId") Long organizationId,
            @Param("activeStatuses") List<SubscriptionStatus> activeStatuses,
            @Param("now") Instant now
            );

    default Optional<Subscription> findActiveSubscription(Long organizationId) {
        return findActiveSubscription(organizationId,
                Arrays.asList(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING),
                Instant.now());
    }

    @Query("SELECT s FROM Subscription s WHERE s.currentPeriodEnd < :now " +
           "AND s.status IN ('ACTIVE', 'TRIALING', 'PAST_DUE')")
    List<Subscription> findExpiredSubscriptions(@Param("now")Instant now);

    List<Subscription> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    /** The projection row a provider subscription id maps to, with its plan graph, for the webhook projector. */
    @Query("SELECT s FROM Subscription s " +
           "LEFT JOIN FETCH s.plan p " +
           "LEFT JOIN FETCH p.planFeatures pf " +
           "LEFT JOIN FETCH pf.feature " +
           "WHERE s.provider = :provider AND s.externalSubscriptionId = :externalSubscriptionId")
    Optional<Subscription> findByProviderAndExternalSubscriptionId(
            @Param("provider") ProviderId provider,
            @Param("externalSubscriptionId") String externalSubscriptionId);

    /** Every row of the organization in one of the given statuses; what a gateway activation supersedes. */
    List<Subscription> findByOrganizationIdAndStatusIn(Long organizationId, List<SubscriptionStatus> statuses);
}
