package org.tornotron.echno_backend.billing.services;

import org.junit.jupiter.api.Test;
import org.tornotron.echno_backend.billing.Feature;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.PlanFeature;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.repositories.UsageRecordRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
        Plan plan = Plan.builder().id(1L).planFeatures(Set.of(planFeatures)).build();
        Subscription sub = Subscription.builder().id(1L).plan(plan).build();
        when(subscriptionCache.get(USER)).thenReturn(sub);
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
}
