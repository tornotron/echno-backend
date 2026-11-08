package org.tornotron.echno_backend.billing.reconcile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.retry.TransactionalWorkRunner;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The move to the Free plan when a manual trial ends (ClickUp 14zdkkvzx5e), across the trial's
 * end instant: before it nothing changes, at it the trial is expired and a Free row starts at the
 * trial end, and any later pass changes nothing more.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ManualTrialExpirySweepTest {

    private static final Long ORG = 31L;
    /** The yearly-billing example: a 30-day trial taken on 2 Nov 2026 on a 365-day period. */
    private static final Instant TAKEN_AT = Instant.parse("2026-11-02T10:00:00Z");
    private static final Instant TRIAL_END = Instant.parse("2026-12-02T10:00:00Z");

    @Mock
    private SubscriptionRepository subscriptions;
    @Mock
    private PlanRepository plans;
    @Mock
    private SubscriptionCache cache;

    private ManualTrialExpirySweep sweep;
    private final List<Subscription> saved = new ArrayList<>();
    private final Plan free = Plan.builder().id(1L).code("FREE").name("Free Plan").build();

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        sweep = new ManualTrialExpirySweep(subscriptions, plans, cache,
                new TenantScopedJobRunner(new TransactionalWorkRunner()), 500);
        when(subscriptions.save(any(Subscription.class))).thenAnswer(invocation -> {
            Subscription row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(900L + saved.size());
            }
            saved.add(row);
            return row;
        });
        when(plans.findByCodeAndIsActiveTrue("FREE")).thenReturn(Optional.of(free));
    }

    private Subscription manualTrial() {
        return Subscription.builder()
                .id(7L).organizationId(ORG).userId(70L)
                .plan(Plan.builder().id(2L).code("PRO").name("Professional Plan").build())
                .status(SubscriptionStatus.TRIALING)
                .currentPeriodStart(TAKEN_AT)
                .currentPeriodEnd(TAKEN_AT.plus(365, ChronoUnit.DAYS))
                .trialStart(TAKEN_AT)
                .trialEnd(TAKEN_AT.plus(30, ChronoUnit.DAYS))
                .build();
    }

    /** The repository hands the row over; the sweep re-reads and re-judges it in its own transaction. */
    private void found(Subscription... rows) {
        when(subscriptions.findEndedManualTrials(any(Instant.class), any())).thenReturn(List.of(rows));
        for (Subscription row : rows) {
            when(subscriptions.findById(row.getId())).thenReturn(Optional.of(row));
        }
        when(subscriptions.findByOrganizationIdAndStatusIn(eq(ORG), anyList())).thenAnswer(invocation ->
                java.util.Arrays.stream(rows).filter(r -> r.getStatus() != SubscriptionStatus.EXPIRED).toList());
    }

    @Test
    void atTheTrialEnd_theTrialExpiresAndAFreeRowStartsAtTheTrialEnd() {
        Subscription trial = manualTrial();
        assertThat(trial.getTrialEnd()).isEqualTo(TRIAL_END);
        found(trial);

        ManualTrialExpirySweep.Summary summary = sweep.runPass(TRIAL_END);

        assertThat(summary.movedToFree()).isEqualTo(1);
        assertThat(trial.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(trial.getCurrentPeriodEnd()).isEqualTo(TRIAL_END);
        assertThat(trial.getCancellationReason()).isEqualTo("Trial ended on 2026-12-02; moved to the Free Plan");

        Subscription freeRow = saved.stream().filter(r -> r.getPlan() == free).findFirst().orElseThrow();
        assertThat(freeRow.getOrganizationId()).isEqualTo(ORG);
        assertThat(freeRow.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(freeRow.getProvider()).isEqualTo(ProviderId.MANUAL);
        assertThat(freeRow.getTrialEnd()).isNull();
        assertThat(freeRow.getCurrentPeriodStart()).isEqualTo(TRIAL_END);
        assertThat(freeRow.getCurrentPeriodEnd())
                .isEqualTo(TRIAL_END.plus(ManualTrialExpirySweep.FREE_PLAN_HORIZON_DAYS, ChronoUnit.DAYS));
        verify(cache).evictOnWrite(ORG);
        assertThat(TenantContext.getCurrentOrgId()).isNull();
    }

    @Test
    void oneSecondBeforeTheTrialEnd_nothingChanges() {
        Subscription trial = manualTrial();
        found(trial);

        ManualTrialExpirySweep.Summary summary = sweep.runPass(TRIAL_END.minusSeconds(1));

        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(trial.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(saved).isEmpty();
    }

    @Test
    void aSecondPass_changesNothing() {
        Subscription trial = manualTrial();
        found(trial);

        sweep.runPass(TRIAL_END.plus(15, ChronoUnit.MINUTES));
        int writes = saved.size();
        ManualTrialExpirySweep.Summary again = sweep.runPass(TRIAL_END.plus(30, ChronoUnit.MINUTES));

        assertThat(again.movedToFree()).isZero();
        assertThat(again.skipped()).isEqualTo(1);
        assertThat(saved).hasSize(writes);
    }

    @Test
    void anOrganizationWithAnotherLiveSubscription_onlyHasItsTrialExpired() {
        Subscription trial = manualTrial();
        Subscription granted = Subscription.builder().id(8L).organizationId(ORG)
                .plan(Plan.builder().id(3L).code("ENTERPRISE").build())
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(TRIAL_END).currentPeriodEnd(TRIAL_END.plus(365, ChronoUnit.DAYS)).build();
        when(subscriptions.findEndedManualTrials(any(Instant.class), any())).thenReturn(List.of(trial));
        when(subscriptions.findById(7L)).thenReturn(Optional.of(trial));
        when(subscriptions.findByOrganizationIdAndStatusIn(eq(ORG), anyList())).thenReturn(List.of(trial, granted));

        ManualTrialExpirySweep.Summary summary = sweep.runPass(TRIAL_END);

        assertThat(summary.expiredOnly()).isEqualTo(1);
        assertThat(trial.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(trial.getCancellationReason()).isEqualTo("Trial ended on 2026-12-02");
        assertThat(saved).containsExactly(trial);
    }

    @Test
    void withNoActiveFreePlan_theTrialStillExpires() {
        Subscription trial = manualTrial();
        found(trial);
        when(plans.findByCodeAndIsActiveTrue("FREE")).thenReturn(Optional.empty());

        ManualTrialExpirySweep.Summary summary = sweep.runPass(TRIAL_END);

        assertThat(summary.expiredOnly()).isEqualTo(1);
        assertThat(trial.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(saved).containsExactly(trial);
    }

    @Test
    void aProviderBackedTrial_isNeverTouched() {
        Subscription trial = manualTrial();
        trial.setProvider(ProviderId.RAZORPAY);
        trial.setExternalSubscriptionId("sub_trial");
        found(trial);

        ManualTrialExpirySweep.Summary summary = sweep.runPass(TRIAL_END);

        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(trial.getStatus()).isEqualTo(SubscriptionStatus.TRIALING);
        assertThat(saved).isEmpty();
    }

    @Test
    void oneFailingRow_costsTheOthersNothing() {
        Subscription broken = manualTrial();
        broken.setId(5L);
        Subscription fine = manualTrial();
        when(subscriptions.findEndedManualTrials(any(Instant.class), any())).thenReturn(List.of(broken, fine));
        when(subscriptions.findById(5L)).thenThrow(new IllegalStateException("boom"));
        when(subscriptions.findById(7L)).thenReturn(Optional.of(fine));
        when(subscriptions.findByOrganizationIdAndStatusIn(eq(ORG), anyList())).thenReturn(List.of(fine));

        ManualTrialExpirySweep.Summary summary = sweep.runPass(TRIAL_END);

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.movedToFree()).isEqualTo(1);
    }

    @Test
    void nothingEnded_writesNothing() {
        when(subscriptions.findEndedManualTrials(any(Instant.class), any())).thenReturn(List.of());

        assertThat(sweep.runPass(TRIAL_END).found()).isZero();
        verify(subscriptions, never()).save(any());
    }

    @Test
    void thePassAsksForTrialsEndedByTheInstantItRunsAt() {
        when(subscriptions.findEndedManualTrials(any(Instant.class), any())).thenReturn(List.of());
        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);

        sweep.runPass(TRIAL_END);

        verify(subscriptions).findEndedManualTrials(at.capture(), any());
        assertThat(at.getValue()).isEqualTo(TRIAL_END);
    }
}
