package org.tornotron.echno_backend.billing.entitlement;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.billing.Feature;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.PlanFeature;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.BillingMapper;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.repositories.UsageRecordRepository;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.billing.snapshot.SubscriptionSnapshot;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The PAST_DUE grace window of #801: a failed renewal keeps the organization entitled for
 * {@code echno.billing.past-due-grace-days} from the moment the row went past due, and the
 * gate refuses from then on, saying which of the two it did.
 */
class PastDueGraceTest {

    private static final Long ORG = 4L;
    private static final String CODE = "advanced-reports";
    private static final int GRACE_DAYS = 7;

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final SubscriptionCache cache = mock(SubscriptionCache.class);
    private final PastDueGracePolicy policy = new PastDueGracePolicy(GRACE_DAYS);
    private final SubscriptionService svc = new SubscriptionService(
            subscriptionRepository, mock(PlanRepository.class), mock(UsageRecordRepository.class), cache, policy);

    private Subscription pastDue(Instant periodEnd, Instant pastDueSince) {
        Feature feature = Feature.builder().id(9L).code(CODE).featureType(FeatureType.BOOLEAN).build();
        Plan plan = Plan.builder().id(1L).planFeatures(Set.of(
                PlanFeature.builder().feature(feature).enabled(true).build())).build();
        return Subscription.builder()
                .id(1L).organizationId(ORG).plan(plan)
                .status(SubscriptionStatus.PAST_DUE)
                .currentPeriodStart(periodEnd.minus(30, ChronoUnit.DAYS))
                .currentPeriodEnd(periodEnd)
                .pastDueSince(pastDueSince)
                .build();
    }

    private void onlyPastDueRow(Subscription row) {
        when(cache.get(ORG)).thenReturn(null);
        when(subscriptionRepository.findActiveSubscription(ORG)).thenReturn(Optional.empty());
        when(subscriptionRepository.findPastDueSubscriptions(ORG)).thenReturn(List.of(row));
    }

    @Test
    void pastDueInsideGrace_isEntitledAndSaysSo() {
        Instant since = Instant.now().minus(2, ChronoUnit.DAYS);
        onlyPastDueRow(pastDue(since, since));

        FeatureAccessResultDto r = svc.checkFeatureAccess(ORG, CODE);

        assertThat(r.isAllowed()).isTrue();
        assertThat(r.getReason()).contains("within the 7-day grace period");
        verify(cache).put(org.mockito.ArgumentMatchers.eq(ORG), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pastDueBeyondGrace_isRefusedAndSaysSo() {
        Instant since = Instant.now().minus(GRACE_DAYS + 1, ChronoUnit.DAYS);
        onlyPastDueRow(pastDue(since, since));

        FeatureAccessResultDto r = svc.checkFeatureAccess(ORG, CODE);

        assertThat(r.isAllowed()).isFalse();
        assertThat(r.getReason()).contains("grace period ended at " + since.plus(GRACE_DAYS, ChronoUnit.DAYS));
    }

    @Test
    void boundary_lastSecondEntitled_firstSecondAfterRefused() {
        Instant since = Instant.parse("2026-09-01T10:00:00Z");
        SubscriptionSnapshot snapshot = BillingMapper.toSubscriptionSnapshot(pastDue(since, since));
        Instant windowEnd = since.plus(GRACE_DAYS, ChronoUnit.DAYS);

        assertThat(policy.entitledUntil(snapshot)).isEqualTo(windowEnd);
        assertThat(policy.hasLapsed(snapshot, windowEnd.minusSeconds(1))).isFalse();
        assertThat(policy.hasLapsed(snapshot, windowEnd)).isTrue();
    }

    @Test
    void graceNeverEndsBeforeThePaidPeriod() {
        Instant since = Instant.parse("2026-09-01T10:00:00Z");
        Instant periodEnd = since.plus(20, ChronoUnit.DAYS);
        SubscriptionSnapshot snapshot = BillingMapper.toSubscriptionSnapshot(pastDue(periodEnd, since));

        assertThat(policy.entitledUntil(snapshot)).isEqualTo(periodEnd);
    }

    @Test
    void rowWithoutPastDueSince_countsFromPeriodEnd() {
        Instant periodEnd = Instant.parse("2026-09-01T10:00:00Z");
        SubscriptionSnapshot snapshot = BillingMapper.toSubscriptionSnapshot(pastDue(periodEnd, null));

        assertThat(policy.entitledUntil(snapshot)).isEqualTo(periodEnd.plus(GRACE_DAYS, ChronoUnit.DAYS));
    }

    @Test
    void cachedPastDueSnapshot_isEvictedOnceTheWindowLapses() {
        Instant since = Instant.now().minus(GRACE_DAYS + 1, ChronoUnit.DAYS);
        Subscription row = pastDue(since, since);
        when(cache.get(ORG)).thenReturn(BillingMapper.toSubscriptionSnapshot(row));
        when(subscriptionRepository.findActiveSubscription(ORG)).thenReturn(Optional.empty());
        when(subscriptionRepository.findPastDueSubscriptions(ORG)).thenReturn(List.of(row));

        FeatureAccessResultDto r = svc.checkFeatureAccess(ORG, CODE);

        verify(cache).evict(ORG);
        assertThat(r.isAllowed()).isFalse();
    }
}
