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
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.repositories.UsageRecordRepository;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.billing.snapshot.SubscriptionSnapshot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A manual trial ends on its trial end date, not at the end of its billing period
 * (ClickUp 14zdkkvzx5e): the gate stops honouring it at {@code trialEnd} and answers from the
 * Free plan until the expiry sweep writes the move. A provider-backed trial is untouched.
 */
class ManualTrialEndTest {

    private static final Long ORG = 6L;
    private static final String PREMIUM = "MODULE_PREMIUM";
    private static final String BASIC = "MODULE_BASIC";

    private final PastDueGracePolicy policy = new PastDueGracePolicy(7);

    private static Plan plan(String code, String... features) {
        Plan plan = Plan.builder().id((long) code.hashCode()).code(code).name(code).build();
        long id = 1;
        for (String feature : features) {
            // A PlanFeature's equality is its id, so each needs one of its own to sit in the set.
            plan.getPlanFeatures().add(PlanFeature.builder().id(id).enabled(true)
                    .feature(Feature.builder().id(id).code(feature).featureType(FeatureType.BOOLEAN).build())
                    .build());
            id++;
        }
        return plan;
    }

    /** A trial as createSubscription writes it: N trial days on a period of the given length. */
    private static Subscription trial(Instant takenAt, int trialDays, int periodDays) {
        return Subscription.builder()
                .id(11L).organizationId(ORG).plan(plan("PRO", BASIC, PREMIUM))
                .status(SubscriptionStatus.TRIALING)
                .currentPeriodStart(takenAt)
                .currentPeriodEnd(takenAt.plus(periodDays, ChronoUnit.DAYS))
                .trialStart(takenAt)
                .trialEnd(takenAt.plus(trialDays, ChronoUnit.DAYS))
                .build();
    }

    @Test
    void yearlyExample_aThirtyDayTrialTakenOnNovember2EndsOnDecember2() {
        Instant takenAt = Instant.parse("2026-11-02T10:00:00Z");
        SubscriptionSnapshot snapshot = BillingMapper.toSubscriptionSnapshot(trial(takenAt, 30, 365));

        assertThat(LocalDate.ofInstant(snapshot.trialEnd(), ZoneOffset.UTC)).isEqualTo(LocalDate.parse("2026-12-02"));
        assertThat(snapshot.currentPeriodEnd()).isEqualTo(Instant.parse("2027-11-02T10:00:00Z"));

        assertThat(policy.hasLapsed(snapshot, Instant.parse("2026-12-02T09:59:59Z"))).isFalse();
        assertThat(policy.hasLapsed(snapshot, Instant.parse("2026-12-02T10:00:00Z"))).isTrue();
        // The billing period still runs; it no longer decides anything.
        assertThat(policy.hasLapsed(snapshot, Instant.parse("2027-06-01T00:00:00Z"))).isTrue();
    }

    @Test
    void theEndIsJudgedAsAnInstant_soTheUtcDateDecidesAndTheServerZoneDoesNot() {
        // Taken at 01:30 on 3 Nov in India, which is still 2 Nov in UTC.
        Instant takenAt = OffsetDateTime.parse("2026-11-03T01:30:00+05:30").toInstant();
        SubscriptionSnapshot snapshot = BillingMapper.toSubscriptionSnapshot(trial(takenAt, 30, 365));
        Instant end = Instant.parse("2026-12-02T20:00:00Z");

        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : List.of("UTC", "Asia/Kolkata", "America/Los_Angeles")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                assertThat(snapshot.trialEnd()).as(zone).isEqualTo(end);
                assertThat(policy.hasLapsed(snapshot, end.minusSeconds(1))).as(zone).isFalse();
                assertThat(policy.hasLapsed(snapshot, end)).as(zone).isTrue();
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void aProviderBackedTrial_isLeftToTheProvider() {
        Instant takenAt = Instant.parse("2026-11-02T10:00:00Z");
        Subscription row = trial(takenAt, 30, 365);
        row.setProvider(ProviderId.RAZORPAY);
        row.setExternalSubscriptionId("sub_trial");
        SubscriptionSnapshot snapshot = BillingMapper.toSubscriptionSnapshot(row);

        assertThat(policy.hasLapsed(snapshot, Instant.parse("2026-12-02T10:00:00Z"))).isFalse();
    }

    @Test
    void anActiveRowThatOnceHadATrial_isNotEnded() {
        Instant takenAt = Instant.parse("2026-11-02T10:00:00Z");
        Subscription row = trial(takenAt, 30, 365);
        row.setStatus(SubscriptionStatus.ACTIVE);
        SubscriptionSnapshot snapshot = BillingMapper.toSubscriptionSnapshot(row);

        assertThat(policy.hasLapsed(snapshot, Instant.parse("2026-12-02T10:00:00Z"))).isFalse();
    }

    // The gate, between the trial end and the sweep's next pass -------------------------------

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final PlanRepository planRepository = mock(PlanRepository.class);
    private final SubscriptionCache cache = mock(SubscriptionCache.class);
    private final SubscriptionService svc = new SubscriptionService(
            subscriptionRepository, planRepository, mock(UsageRecordRepository.class), cache, policy);

    private Subscription endedTrialWithFreeFallback() {
        Subscription ended = trial(Instant.now().minus(31, ChronoUnit.DAYS), 30, 365);
        when(cache.get(ORG)).thenReturn(BillingMapper.toSubscriptionSnapshot(ended));
        when(subscriptionRepository.findActiveSubscription(ORG)).thenReturn(Optional.empty());
        when(subscriptionRepository.findEndedManualTrials(org.mockito.ArgumentMatchers.eq(ORG),
                org.mockito.ArgumentMatchers.any(Instant.class))).thenReturn(List.of(ended));
        return ended;
    }

    @Test
    void duringTheTrial_everyFeatureOfTheTrialPlanIsAllowed() {
        Subscription live = trial(Instant.now().minus(5, ChronoUnit.DAYS), 30, 365);
        when(cache.get(ORG)).thenReturn(BillingMapper.toSubscriptionSnapshot(live));

        assertThat(svc.checkFeatureAccess(ORG, PREMIUM).isAllowed()).isTrue();
    }

    @Test
    void afterTheTrial_theCachedTrialIsDroppedAndTheFreePlanAnswers() {
        Subscription ended = endedTrialWithFreeFallback();
        when(planRepository.findByCodeWithFeatures(ManualTrial.FALLBACK_PLAN_CODE))
                .thenReturn(Optional.of(plan(ManualTrial.FALLBACK_PLAN_CODE, BASIC)));

        FeatureAccessResultDto basic = svc.checkFeatureAccess(ORG, BASIC);
        FeatureAccessResultDto premium = svc.checkFeatureAccess(ORG, PREMIUM);

        verify(cache, org.mockito.Mockito.atLeastOnce()).evict(ORG);
        assertThat(basic.isAllowed()).isTrue();
        assertThat(premium.isAllowed()).isFalse();
        assertThat(premium.getReason()).isEqualTo("Trial ended at " + ended.getTrialEnd()
                + "; feature not included in the Free plan");
        assertThat(premium.getMessage()).contains("Purchase a plan");
    }

    @Test
    void afterTheTrial_withNoFreePlan_theOrganizationHasNoSubscription() {
        endedTrialWithFreeFallback();
        when(planRepository.findByCodeWithFeatures(ManualTrial.FALLBACK_PLAN_CODE)).thenReturn(Optional.empty());

        FeatureAccessResultDto r = svc.checkFeatureAccess(ORG, BASIC);

        assertThat(r.isAllowed()).isFalse();
        assertThat(r.getReason()).isEqualTo("No active subscription");
    }

    @Test
    void inAdvisoryMode_theRefusalIsLoggedAndLetThrough_inEnforceItIsRefused() {
        FeatureAccessResultDto refused = FeatureAccessResultDto.trialEnded(Instant.parse("2026-12-02T10:00:00Z"));

        assertThat(new EntitlementPolicy("advisory").permits(ORG, PREMIUM, refused)).isTrue();
        assertThat(new EntitlementPolicy("enforce").permits(ORG, PREMIUM, refused)).isFalse();
    }

    @Test
    void manualReading_matchesTheDirectWritePaths() {
        assertThat(ManualTrial.isManual(ProviderId.MANUAL, null)).isTrue();
        assertThat(ManualTrial.isManual(ProviderId.MANUAL, "anything")).isTrue();
        assertThat(ManualTrial.isManual(ProviderId.RAZORPAY, null)).isTrue();
        assertThat(ManualTrial.isManual(ProviderId.RAZORPAY, "sub_1")).isFalse();
        assertThat(ManualTrial.FALLBACK_PLAN_CODE).isEqualTo("FREE");
    }
}
