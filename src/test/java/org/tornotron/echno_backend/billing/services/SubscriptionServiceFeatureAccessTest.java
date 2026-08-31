package org.tornotron.echno_backend.billing.services;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.tornotron.echno_backend.billing.Feature;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.PlanFeature;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.UsageRecord;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.BillingMapper;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.dto.SubscriptionDto;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.repositories.UsageRecordRepository;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the entitlement-gating logic in SubscriptionService.checkFeatureAccess,
 * the revenue path that decides whether a user's plan grants a feature and whether a
 * metered feature is within its quota. Repositories and the subscription cache are
 * mocked; the plan/feature graph is built in memory, so no Spring context or database
 * is needed.
 */
class SubscriptionServiceFeatureAccessTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final UsageRecordRepository usageRecordRepository = mock(UsageRecordRepository.class);
    private final SubscriptionCache subscriptionCache = mock(SubscriptionCache.class);
    private final SubscriptionService svc = new SubscriptionService(
            subscriptionRepository, planRepository, usageRecordRepository, subscriptionCache);

    private static final Long USER = 1L;
    private static final Long FEATURE_ID = 9L;
    private static final String CODE = "advanced-reports";

    private void activeSubscriptionWith(PlanFeature... planFeatures) {
        cacheSubscription(Instant.now().plus(30, ChronoUnit.DAYS), planFeatures);
    }

    private void cacheSubscription(Instant periodEnd, PlanFeature... planFeatures) {
        Plan plan = Plan.builder().id(1L).planFeatures(Set.of(planFeatures)).build();
        Subscription sub = Subscription.builder()
                .id(1L)
                .userId(USER)
                .plan(plan)
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(periodEnd.minus(30, ChronoUnit.DAYS))
                .currentPeriodEnd(periodEnd)
                .build();
        when(subscriptionCache.get(USER)).thenReturn(BillingMapper.toSubscriptionSnapshot(sub));
    }

    private PlanFeature booleanFeature(boolean enabled) {
        Feature f = Feature.builder().id(FEATURE_ID).code(CODE).featureType(FeatureType.BOOLEAN).build();
        return PlanFeature.builder().feature(f).enabled(enabled).build();
    }

    private PlanFeature quotaFeature(long limit) {
        Feature f = Feature.builder().id(FEATURE_ID).code(CODE).featureType(FeatureType.QUOTA).build();
        return PlanFeature.builder().feature(f).enabled(true)
                .quotaLimit(limit).quotaPeriod(QuotaPeriod.MONTHLY).build();
    }

    @Test
    void noActiveSubscription_isDenied() {
        when(subscriptionCache.get(USER)).thenReturn(null);
        when(subscriptionRepository.findActiveSubscriptionByUserId(USER)).thenReturn(Optional.empty());

        FeatureAccessResultDto r = svc.checkFeatureAccess(USER, CODE);

        assertThat(r.isAllowed()).isFalse();
        assertThat(r.getReason()).isEqualTo("No active subscription");
    }

    @Test
    void featureNotInPlan_isDenied() {
        Feature other = Feature.builder().id(1L).code("something-else").featureType(FeatureType.BOOLEAN).build();
        activeSubscriptionWith(PlanFeature.builder().feature(other).enabled(true).build());

        FeatureAccessResultDto r = svc.checkFeatureAccess(USER, CODE);

        assertThat(r.isAllowed()).isFalse();
        assertThat(r.getReason()).contains("not included");
    }

    @Test
    void booleanFeatureEnabled_isAllowed() {
        activeSubscriptionWith(booleanFeature(true));

        assertThat(svc.checkFeatureAccess(USER, CODE).isAllowed()).isTrue();
    }

    @Test
    void booleanFeatureDisabled_isDenied() {
        activeSubscriptionWith(booleanFeature(false));

        FeatureAccessResultDto r = svc.checkFeatureAccess(USER, CODE);

        assertThat(r.isAllowed()).isFalse();
        assertThat(r.getReason()).isEqualTo("Feature is disabled");
    }

    @Test
    void quotaWithinLimit_isAllowedAndReportsUsage() {
        activeSubscriptionWith(quotaFeature(100L));
        when(usageRecordRepository.sumUsageForPeriod(eq(USER), eq(FEATURE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(30L);

        FeatureAccessResultDto r = svc.checkFeatureAccess(USER, CODE);

        assertThat(r.isAllowed()).isTrue();
        assertThat(r.getCurrentUsage()).isEqualTo(30L);
        assertThat(r.getQuotaLimit()).isEqualTo(100L);
    }

    @Test
    void quotaAtOrOverLimit_isDenied() {
        activeSubscriptionWith(quotaFeature(100L));
        when(usageRecordRepository.sumUsageForPeriod(eq(USER), eq(FEATURE_ID), any(Instant.class), any(Instant.class)))
                .thenReturn(100L);

        FeatureAccessResultDto r = svc.checkFeatureAccess(USER, CODE);

        assertThat(r.isAllowed()).isFalse();
        assertThat(r.getReason()).isEqualTo("Quota exceeded");
        assertThat(r.getCurrentUsage()).isEqualTo(100L);
    }

    /**
     * The reason the cached value carries a feature id that no response DTO does. Usage rows are
     * keyed on {@code UsageRecord.featureId}, a plain column rather than a relation, so without
     * it on the cached value every metered request would have to go back to the database for it.
     */
    @Test
    void recordUsage_keysTheUsageRowOnTheFeatureIdFromTheCachedValue() {
        activeSubscriptionWith(quotaFeature(100L));

        svc.recordUsage(USER, CODE, 3L);

        ArgumentCaptor<UsageRecord> saved = ArgumentCaptor.forClass(UsageRecord.class);
        verify(usageRecordRepository).save(saved.capture());
        assertThat(saved.getValue().getFeatureId()).isEqualTo(FEATURE_ID);
        assertThat(saved.getValue().getSubscriptionId()).isEqualTo(1L);
        assertThat(saved.getValue().getUsageAmount()).isEqualTo(3L);
        verifyNoInteractions(subscriptionRepository);
    }

    /**
     * Recording usage used to evict the user's entry. Nothing it writes is part of what the
     * cache holds, since a quota check sums the usage table on every call, so the eviction
     * bought no freshness and threw the entry away on the one path that runs on every metered
     * request.
     */
    @Test
    void recordUsage_doesNotEvictTheCachedSubscription() {
        activeSubscriptionWith(quotaFeature(100L));

        svc.recordUsage(USER, CODE, 1L);

        verify(subscriptionCache, never()).evict(USER);
        verify(subscriptionCache, never()).evictOnWrite(USER);
    }

    /**
     * A plan may grant a metered feature without setting a quota period. Resolving it used to
     * run the plan features through {@code map(...).findFirst()}, which throws on a null rather
     * than falling through to the intended monthly default.
     */
    @Test
    void recordUsage_fallsBackToAMonthlyPeriodWhenThePlanSetsNone() {
        Feature f = Feature.builder().id(FEATURE_ID).code(CODE).featureType(FeatureType.QUOTA).build();
        activeSubscriptionWith(PlanFeature.builder().feature(f).enabled(true).quotaLimit(10L).build());

        svc.recordUsage(USER, CODE, 1L);

        ArgumentCaptor<UsageRecord> saved = ArgumentCaptor.forClass(UsageRecord.class);
        verify(usageRecordRepository).save(saved.capture());
        assertThat(saved.getValue().getPeriodStart()).isBefore(saved.getValue().getPeriodEnd());
    }

    /**
     * Quota windows are UTC, not the container's timezone.
     *
     * <p>These bounds are written onto every usage row and are also the window existing usage is
     * summed over, so the two only agree while every process computing them agrees on where a day
     * begins. Derived from {@code ZoneId.systemDefault()}, as they were, a deployment in a zone
     * ahead of UTC starts its day five and a half hours early here, which moves a user between
     * quota periods and can allow or refuse them against a window that does not match the rows
     * being counted. Forcing a non-UTC default is what makes this fail against that version; under
     * UTC the default is irrelevant, which is the whole point. See #644.
     */
    @Test
    void quotaPeriodBoundsAreComputedInUtcWhateverTheContainerTimezone() {
        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        try {
            Feature f = Feature.builder().id(FEATURE_ID).code(CODE).featureType(FeatureType.QUOTA).build();
            activeSubscriptionWith(PlanFeature.builder().feature(f).enabled(true)
                    .quotaLimit(10L).quotaPeriod(QuotaPeriod.DAILY).build());

            svc.recordUsage(USER, CODE, 1L);

            ArgumentCaptor<UsageRecord> saved = ArgumentCaptor.forClass(UsageRecord.class);
            verify(usageRecordRepository).save(saved.capture());

            Instant utcDayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
            assertThat(saved.getValue().getPeriodStart())
                    .as("the daily window starts at UTC midnight, not at midnight in Asia/Kolkata")
                    .isEqualTo(utcDayStart);
            assertThat(saved.getValue().getPeriodEnd())
                    .isEqualTo(utcDayStart.plus(1, ChronoUnit.DAYS));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    /**
     * The reason the cache does not hold {@code SubscriptionDto}. {@code expired} is a
     * comparison against the current instant, so a DTO cached at the start of the window would
     * still report a lapsed subscription as unexpired at the end of it. Reading it off a cached
     * value whose period has already ended must answer for now.
     */
    @Test
    void aCachedSubscriptionWhosePeriodHasEnded_readsAsExpired() {
        cacheSubscription(Instant.now().minus(1, ChronoUnit.HOURS), booleanFeature(true));

        SubscriptionDto dto = svc.getActiveSubscription(USER).orElseThrow();

        assertThat(dto.isExpired()).isTrue();
        verifyNoInteractions(subscriptionRepository);
    }
}
