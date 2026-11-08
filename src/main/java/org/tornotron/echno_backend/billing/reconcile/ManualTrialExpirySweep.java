package org.tornotron.echno_backend.billing.reconcile;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.entitlement.ManualTrial;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.gateway.ProviderId;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.common.multitenancy.TenantScopedJobRunner;
import org.tornotron.echno_backend.common.multitenancy.WithoutTenant;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Moves an organization whose manual trial has ended onto the Free plan (ClickUp 14zdkkvzx5e).
 *
 * <p>The gate already stops honouring such a trial at its trial end (see {@link ManualTrial}),
 * and answers from the Free plan until this pass runs. What this writes is the record: the trial
 * row goes to EXPIRED with its period cut back to the trial end, so it no longer reads as a trial
 * anywhere, and a Free plan row starts at the trial end. Nothing else about the organization
 * changes. Data saved under a module the Free plan lacks stays where it is; the module's
 * {@code @RequireSubscription} gate and the module registry refuse it from then on, the same way
 * they refuse any module that is not in the plan.
 *
 * <p>The Free plan bills nothing, so its row has no billing period to end. It is given the same
 * hundred-year horizon a TOTAL quota uses, and stays until a paid subscription supersedes it or
 * an admin cancels it. A Free row with a 30-day period would lapse a month later and leave the
 * organization with no plan at all, which in enforce mode refuses even the Free modules.
 *
 * <p>Only the drop is skipped when the organization already holds another live subscription
 * (an admin granted one after the trial ended, say): the trial is still expired, and nothing is
 * added beside the subscription it has.
 *
 * <p>Idempotent. Each row is re-read and re-judged inside its own transaction pinned to its
 * organization, so a row a previous pass, another replica or an admin already moved is left
 * alone, and a repeated pass finds nothing to do. Two replicas racing on one row serialise on
 * it under CockroachDB's serializable isolation; the loser's retry re-reads it as EXPIRED.
 * Provider-backed trials are never selected: the provider ends those.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "echno.billing.trial-expiry.enabled", havingValue = "true", matchIfMissing = true)
public class ManualTrialExpirySweep {

    /** How long the Free plan row runs: open-ended in practice, as for a TOTAL quota. */
    static final long FREE_PLAN_HORIZON_DAYS = 36500;

    private static final List<SubscriptionStatus> LIVE =
            List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.TRIALING, SubscriptionStatus.PAST_DUE);

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final SubscriptionCache cache;
    private final TenantScopedJobRunner tenantRunner;
    private final int maxPerRun;

    public ManualTrialExpirySweep(SubscriptionRepository subscriptions, PlanRepository plans, SubscriptionCache cache,
                                  TenantScopedJobRunner tenantRunner,
                                  @Value("${echno.billing.trial-expiry.max-per-run:500}") int maxPerRun) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.cache = cache;
        this.tenantRunner = tenantRunner;
        this.maxPerRun = Math.max(1, maxPerRun);
    }

    @Scheduled(cron = "${echno.billing.trial-expiry.cron:0 */15 * * * *}", zone = "UTC")
    @WithoutTenant("The sweep belongs to no organization; each trial is expired pinned to its own")
    public void sweep() {
        try {
            runPass(Instant.now());
        } catch (Exception e) {
            log.error("Manual trial expiry pass failed: {}", e.getMessage(), e);
        }
    }

    /** What one row came to. */
    enum Outcome {
        /** Trial expired and a Free plan row started at its trial end. */
        MOVED_TO_FREE,
        /** Trial expired; the organization already holds another live subscription. */
        EXPIRED_ONLY,
        /** Trial expired; no active Free plan exists to move to. */
        EXPIRED_NO_FREE_PLAN,
        /** Already moved, or no longer an ended manual trial when re-read. */
        SKIPPED
    }

    /** What one pass did; package-private so a test can run it against a chosen instant. */
    record Summary(int found, int movedToFree, int expiredOnly, int skipped, int failed) {
    }

    Summary runPass(Instant now) {
        List<Subscription> ended = subscriptions.findEndedManualTrials(now, PageRequest.of(0, maxPerRun));
        if (ended.isEmpty()) {
            return new Summary(0, 0, 0, 0, 0);
        }
        if (ended.size() == maxPerRun) {
            log.info("Manual trial expiry took its per-pass cap of {}; the rest wait for the next pass", maxPerRun);
        }
        int moved = 0;
        int expiredOnly = 0;
        int skipped = 0;
        int failed = 0;
        for (Subscription row : ended) {
            Long subscriptionId = row.getId();
            Long organizationId = row.getOrganizationId();
            try {
                Outcome outcome = tenantRunner.callForTenantInTransaction(organizationId, () -> expire(subscriptionId, now));
                switch (outcome) {
                    case MOVED_TO_FREE -> moved++;
                    case EXPIRED_ONLY, EXPIRED_NO_FREE_PLAN -> expiredOnly++;
                    case SKIPPED -> skipped++;
                }
            } catch (Exception e) {
                log.error("Manual trial expiry could not move subscription {} of organization {}: {}",
                        subscriptionId, organizationId, e.getMessage(), e);
                failed++;
            }
        }
        Summary summary = new Summary(ended.size(), moved, expiredOnly, skipped, failed);
        log.info("Manual trial expiry found {} ended trial(s): moved {} to the Free plan, expired {} without a move, "
                + "skipped {}, failed {}", summary.found(), moved, expiredOnly, skipped, failed);
        return summary;
    }

    /** Expires one ended manual trial and, where nothing else entitles the organization, starts its Free plan row. */
    Outcome expire(Long subscriptionId, Instant now) {
        Optional<Subscription> loaded = subscriptions.findById(subscriptionId);
        if (loaded.isEmpty() || !ManualTrial.hasEnded(loaded.get(), now)) {
            return Outcome.SKIPPED;
        }
        Subscription trial = loaded.get();
        Long organizationId = trial.getOrganizationId();
        Instant trialEnd = trial.getTrialEnd();
        String endedOn = LocalDate.ofInstant(trialEnd, ZoneOffset.UTC).toString();

        trial.setStatus(SubscriptionStatus.EXPIRED);
        if (trial.getCurrentPeriodEnd() == null || trial.getCurrentPeriodEnd().isAfter(trialEnd)) {
            trial.setCurrentPeriodEnd(trialEnd);
        }
        cache.evictOnWrite(organizationId);

        if (holdsAnotherLiveSubscription(organizationId, subscriptionId, now)) {
            trial.setCancellationReason("Trial ended on " + endedOn);
            subscriptions.save(trial);
            log.info("Manual trial {} of organization {} ended on {}; the organization holds another live subscription, "
                    + "so it stays on that", subscriptionId, organizationId, endedOn);
            return Outcome.EXPIRED_ONLY;
        }

        Optional<Plan> free = plans.findByCodeAndIsActiveTrue(ManualTrial.FALLBACK_PLAN_CODE);
        if (free.isEmpty()) {
            trial.setCancellationReason("Trial ended on " + endedOn);
            subscriptions.save(trial);
            log.warn("Manual trial {} of organization {} ended on {}, but no active {} plan exists to move it to; "
                    + "the organization now has no plan", subscriptionId, organizationId, endedOn, ManualTrial.FALLBACK_PLAN_CODE);
            return Outcome.EXPIRED_NO_FREE_PLAN;
        }

        trial.setCancellationReason("Trial ended on " + endedOn + "; moved to the " + free.get().getName());
        subscriptions.save(trial);
        Subscription freeRow = subscriptions.save(Subscription.builder()
                .organizationId(organizationId)
                .userId(trial.getUserId())
                .plan(free.get())
                .status(SubscriptionStatus.ACTIVE)
                .currentPeriodStart(trialEnd)
                .currentPeriodEnd(trialEnd.plus(FREE_PLAN_HORIZON_DAYS, ChronoUnit.DAYS))
                .provider(ProviderId.MANUAL)
                .build());
        log.info("Manual trial {} of organization {} ended on {}; moved to plan {} as subscription {}",
                subscriptionId, organizationId, endedOn, ManualTrial.FALLBACK_PLAN_CODE, freeRow.getId());
        return Outcome.MOVED_TO_FREE;
    }

    /**
     * Whether some other row still entitles the organization: a past-due row (the grace policy
     * decides for how long), or an active or trialing row whose period runs on and which is not
     * itself an ended manual trial.
     */
    private boolean holdsAnotherLiveSubscription(Long organizationId, Long exceptId, Instant now) {
        return subscriptions.findByOrganizationIdAndStatusIn(organizationId, LIVE).stream()
                .filter(other -> !other.getId().equals(exceptId))
                .anyMatch(other -> other.getStatus() == SubscriptionStatus.PAST_DUE
                        || (other.getCurrentPeriodEnd() != null && other.getCurrentPeriodEnd().isAfter(now)
                            && !ManualTrial.hasEnded(other, now)));
    }
}
