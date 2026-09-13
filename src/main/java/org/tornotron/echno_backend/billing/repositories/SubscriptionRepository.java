package org.tornotron.echno_backend.billing.repositories;

import org.springframework.data.domain.Pageable;
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

    /**
     * Provider-backed rows the reconciliation sweep should ask the provider about: a live row
     * whose period has ended, any PAST_DUE row, and a row left INCOMPLETE since before the
     * given cut-off. Oldest period end first, so a capped pass takes the longest-stale rows;
     * the cap is the page size.
     */
    @Query("SELECT s FROM Subscription s LEFT JOIN FETCH s.plan " +
           "WHERE s.provider = :provider AND s.externalSubscriptionId IS NOT NULL AND (" +
           "(s.status IN :liveStatuses AND s.currentPeriodEnd < :now) " +
           "OR s.status = org.tornotron.echno_backend.billing.enums.SubscriptionStatus.PAST_DUE " +
           "OR (s.status = org.tornotron.echno_backend.billing.enums.SubscriptionStatus.INCOMPLETE " +
           "AND s.createdAt < :incompleteBefore)) " +
           "ORDER BY s.currentPeriodEnd ASC")
    List<Subscription> findStaleProviderSubscriptions(
            @Param("provider") ProviderId provider,
            @Param("liveStatuses") List<SubscriptionStatus> liveStatuses,
            @Param("now") Instant now,
            @Param("incompleteBefore") Instant incompleteBefore,
            Pageable pageable);

    /**
     * The organization's past-due subscriptions, the most recently paid-up first. Whether one
     * still grants access is the grace policy's call, made in the service against the clock.
     */
    @Query("SELECT s FROM Subscription s " +
           "LEFT JOIN FETCH s.plan p " +
           "LEFT JOIN FETCH p.planFeatures pf " +
           "LEFT JOIN FETCH pf.feature " +
           "WHERE s.organizationId = :organizationId AND s.status = :status " +
           "ORDER BY s.currentPeriodEnd DESC")
    List<Subscription> findByOrganizationIdAndStatusOrderByCurrentPeriodEndDesc(
            @Param("organizationId") Long organizationId,
            @Param("status") SubscriptionStatus status);

    default List<Subscription> findPastDueSubscriptions(Long organizationId) {
        return findByOrganizationIdAndStatusOrderByCurrentPeriodEndDesc(organizationId, SubscriptionStatus.PAST_DUE);
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
