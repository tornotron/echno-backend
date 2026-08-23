package org.tornotron.echno_backend.billing.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.*;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.repositories.UsageRecordRepository;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.NoActiveSubscriptionException;
import org.tornotron.echno_backend.common.exception.PlanNotFoundException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Subscription lifecycle and feature-access checks for the SaaS billing model.
 *
 * <p>Resolves a user's active subscription (cached per user), decides whether a user may use
 * a feature given their plan and any per-feature quota, and records metered usage. Quota
 * windows are computed per {@link QuotaPeriod} (hourly through annual, plus an all-time
 * bucket). Creating, changing, and canceling a subscription each evict the user's cache
 * entry so the next access re-reads the current state.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final SubscriptionCache subscriptionCache;

    /**
     * Returns the user's active subscription, serving it from the cache when present.
     *
     * <p>On a cache miss it queries the repository and, if found, populates the cache.
     *
     * @param userId The ID of the user whose subscription to resolve.
     * @return The active subscription, or empty if the user has none.
     */
    public Optional<Subscription> getActiveSubscription(Long userId) {

        Subscription cached = subscriptionCache.get(userId);
        if(cached != null) {
            return Optional.of(cached);
        }

        Optional<Subscription> subscription = subscriptionRepository
                .findActiveSubscriptionByUserId(userId);

        subscription.ifPresent(sub -> subscriptionCache.put(userId, sub));

        return subscription;
    }

    /**
     * Decides whether a user may use a feature under their active plan.
     *
     * <p>Resolves the user's subscription and the matching plan feature, then evaluates access:
     * a boolean feature is allowed when enabled, a quota feature is checked against usage in the
     * current period, and a feature with neither constraint is allowed outright. Absence of a
     * subscription or of the feature in the plan yields the corresponding denial result.
     *
     * @param userId The ID of the user requesting access.
     * @param featureCode The code of the feature to check.
     * @return The access result, describing whether access is allowed and, for quota features, the current usage against the limit.
     */
    public FeatureAccessResultDto checkFeatureAccess(Long userId, String featureCode) {
        Optional<Subscription> subscriptionOptional = getActiveSubscription(userId);

        if(subscriptionOptional.isEmpty()) {
            return FeatureAccessResultDto.noSubscription();
        }

        Subscription subscription = subscriptionOptional.get();
        Plan plan = subscription.getPlan();

        Optional<PlanFeature> planFeatureOptional = plan.getPlanFeatures().stream()
                .filter(pf -> pf.getFeature().getCode().equals(featureCode))
                .findFirst();

        if(planFeatureOptional.isEmpty()) {
            return FeatureAccessResultDto.featureNotInPlan();
        }

        PlanFeature planFeature = planFeatureOptional.get();

        if(planFeature.getFeature().getFeatureType() == FeatureType.BOOLEAN) {
            return planFeature.isFeatureEnabled()
                    ? FeatureAccessResultDto.allowed()
                    : FeatureAccessResultDto.featureDisabled();
        }

        if(planFeature.hasQuota()) {
            return checkQuotaAccess(userId, planFeature);
        }

        return FeatureAccessResultDto.allowed();
    }

    private FeatureAccessResultDto checkQuotaAccess(Long userId, PlanFeature planFeature) {
        Long quotaLimit = planFeature.getQuotaLimit();
        QuotaPeriod period = planFeature.getQuotaPeriod();

        Instant[] periodBounds = calculatePeriodBounds(period);

        Long currentUsage = usageRecordRepository.sumUsageForPeriod(
                userId,
                planFeature.getFeature().getId(),
                periodBounds[0],
                periodBounds[1]
        );

        if(currentUsage >= quotaLimit) {
            return FeatureAccessResultDto.quotaExceeded(currentUsage, quotaLimit);
        }

        return FeatureAccessResultDto.allowed(currentUsage, quotaLimit);
    }

    private Instant[] calculatePeriodBounds(QuotaPeriod period) {
        Instant now = Instant.now();
        ZonedDateTime zdt = now.atZone(ZoneId.systemDefault());

        switch (period) {
            case HOURLY:
                ZonedDateTime startOfHour = zdt.truncatedTo(ChronoUnit.HOURS);
                return new Instant[]{
                        startOfHour.toInstant(),
                        startOfHour.plusHours(1).toInstant()
                };
            case DAILY:
                ZonedDateTime startOfDay = zdt.truncatedTo(ChronoUnit.DAYS);
                return new Instant[]{
                        startOfDay.toInstant(),
                        startOfDay.plusDays(1).toInstant()
                };

            case MONTHLY:
                ZonedDateTime startOfMonth = zdt.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
                return new Instant[]{
                        startOfMonth.toInstant(),
                        startOfMonth.plusMonths(1).toInstant()
                };

            case ANNUAL:
                ZonedDateTime startOfYear = zdt.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS);
                return new Instant[]{
                        startOfYear.toInstant(),
                        startOfYear.plusYears(1).toInstant()
                };

            case TOTAL:
                return new Instant[]{
                        Instant.EPOCH,
                        now.plus(36500, ChronoUnit.DAYS) // 100 years approx, safe for DB
                };

            default:
                throw new IllegalArgumentException("Unsupported quota period '" + period + "'");
        }

    }

    /**
     * Records a usage amount against a metered feature for the current quota period.
     *
     * <p>If the user has no active subscription or the feature is not in their plan, the call is
     * logged and ignored rather than raising. On success it writes a usage record for the
     * feature's current period and evicts the user's cached subscription.
     *
     * @param userId The ID of the user consuming the feature.
     * @param featureCode The code of the feature being consumed.
     * @param amount The quantity to record for this usage event.
     */
    @Transactional
    public void recordUsage(Long userId, String featureCode, Long amount) {
        Optional<Subscription> subscriptionOptional = getActiveSubscription(userId);
        if(subscriptionOptional.isEmpty()) {
            log.warn("Attempted to record usage for user without active subscription: {}", userId);
            return;
        }

        Subscription subscription = subscriptionOptional.get();

        Feature feature = subscription.getPlan().getPlanFeatures().stream()
                .map(PlanFeature::getFeature)
                .filter(f -> f.getCode().equals(featureCode))
                .findFirst()
                .orElse(null);

        if(feature == null) {
            log.warn("Feature {} not found in user's plan", featureCode);
            return;
        }
        Instant now = Instant.now();
        QuotaPeriod period = subscription.getPlan().getPlanFeatures().stream()
                .filter(pf -> pf.getFeature().getCode().equals(featureCode))
                .map(PlanFeature::getQuotaPeriod)
                .findFirst()
                .orElse(QuotaPeriod.MONTHLY);

        Instant[] periodBounds = calculatePeriodBounds(period);

        UsageRecord usageRecord = UsageRecord.builder()
                .userId(userId)
                .subscriptionId(subscription.getId())
                .featureId(feature.getId())
                .usageAmount(amount)
                .periodStart(periodBounds[0])
                .periodEnd(periodBounds[1])
                .build();

        usageRecordRepository.save(usageRecord);

        subscriptionCache.evict(userId);

    }

    /**
     * Subscribes a user to a plan, starting a trial when the plan offers trial days.
     *
     * <p>The current period runs 365 days for an annual billing period and 30 days otherwise.
     * A plan with trial days starts the subscription in the trialing state with the trial window
     * set; otherwise it starts active. The user's cached subscription is evicted.
     *
     * @param userId The ID of the user to subscribe.
     * @param planCode The code of the plan to subscribe to.
     * @param billingPeriod The billing period that sets the current period length.
     * @return The created subscription.
     * @throws DuplicateResourceException if the user already has an active subscription.
     * @throws PlanNotFoundException if no plan with the given code exists.
     */
    @Transactional
    public Subscription createSubscription(Long userId, String planCode, BillingPeriod billingPeriod) {
        if (getActiveSubscription(userId).isPresent()) {
            throw new DuplicateResourceException(
                    "User " + userId + " already has an active subscription; use change-plan to switch plans instead");
        }

        Plan plan = planRepository.findByCodeWithFeatures(planCode)
                .orElseThrow(() -> new PlanNotFoundException("Plan with code '" + planCode + "' was not found"));

        Instant now = Instant.now();
        Instant periodEnd;
        if (billingPeriod == BillingPeriod.ANNUAL) {
            periodEnd = now.plus(365, ChronoUnit.DAYS);
        } else {
            periodEnd = now.plus(30, ChronoUnit.DAYS);
        }

        Subscription subscription = Subscription.builder()
                .userId(userId)
                .plan(plan)
                .status(plan.getTrialDays() > 0 ? SubscriptionStatus.TRIALING : SubscriptionStatus.ACTIVE)
                .currentPeriodStart(now)
                .currentPeriodEnd(periodEnd)
                .trialStart(plan.getTrialDays() > 0 ? now : null)
                .trialEnd(plan.getTrialDays() > 0 ? now.plus(plan.getTrialDays(), ChronoUnit.DAYS) : null)
                .build();

        subscription = subscriptionRepository.save(subscription);

        subscriptionCache.evict(userId);

        log.info("Created subscription {} for user {} on plan {} ({})", 
                subscription.getId(), userId, planCode, billingPeriod);

        return subscription;
    }

    /**
     * Switches a user's active subscription to a different plan.
     *
     * <p>The subscription keeps its period and status; only the plan changes. The user's cached
     * subscription is evicted.
     *
     * @param userId The ID of the user whose plan to change.
     * @param newPlanCode The code of the new plan; it must be active.
     * @return The updated subscription.
     * @throws NoActiveSubscriptionException if the user has no active subscription.
     * @throws PlanNotFoundException if no active plan with the given code exists.
     */
    @Transactional
    public Subscription changeSubscription(Long userId,String newPlanCode) {
        Subscription currentSubscription = getActiveSubscription(userId)
                .orElseThrow(() -> new NoActiveSubscriptionException("User " + userId + " has no active subscription"));

        Plan newPlan = planRepository.findByCodeAndIsActiveTrue(newPlanCode)
                .orElseThrow(() -> new PlanNotFoundException("Active plan with code '" + newPlanCode + "' was not found"));

        currentSubscription.setPlan(newPlan);
        currentSubscription = subscriptionRepository.save(currentSubscription);

        subscriptionCache.evict(userId);

        log.info("Changed subscription {} for user {} to plan {}",
                currentSubscription.getId(), userId, newPlanCode);

        return currentSubscription;

    }

    /**
     * Cancels a user's active subscription, now or at the end of the current period.
     *
     * <p>An immediate cancellation sets the status to canceled and stamps the cancellation time;
     * a deferred one flags the subscription to end at period end but leaves it active until then.
     * The user's cached subscription is evicted.
     *
     * @param userId The ID of the user whose subscription to cancel.
     * @param immediate {@code true} to cancel at once, {@code false} to cancel at period end.
     * @throws NoActiveSubscriptionException if the user has no active subscription.
     */
    @Transactional
    public void cancelSubscription(Long userId, boolean immediate) {

       Subscription subscription = getActiveSubscription(userId)
               .orElseThrow(() -> new NoActiveSubscriptionException("User " + userId + " has no active subscription"));

       if(immediate) {
           subscription.setStatus(SubscriptionStatus.CANCELED);
           subscription.setCanceledAt(Instant.now());
       } else {
           subscription.setCancelAtPeriodEnd(true);
       }

       subscriptionRepository.save(subscription);
       subscriptionCache.evict(userId);

       log.info("Canceled subscription {} for user {} (immediate: {})",
               subscription.getId(), userId, immediate);
    }



}
