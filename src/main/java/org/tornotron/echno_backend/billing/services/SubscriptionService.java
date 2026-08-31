package org.tornotron.echno_backend.billing.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.billing.*;
import org.tornotron.echno_backend.billing.components.SubscriptionCache;
import org.tornotron.echno_backend.billing.dto.BillingMapper;
import org.tornotron.echno_backend.billing.dto.FeatureAccessResultDto;
import org.tornotron.echno_backend.billing.dto.SubscriptionDto;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;
import org.tornotron.echno_backend.billing.enums.SubscriptionStatus;
import org.tornotron.echno_backend.billing.repositories.PlanRepository;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.repositories.UsageRecordRepository;
import org.tornotron.echno_backend.billing.snapshot.PlanFeatureSnapshot;
import org.tornotron.echno_backend.billing.snapshot.SubscriptionSnapshot;
import org.tornotron.echno_backend.common.exception.DuplicateResourceException;
import org.tornotron.echno_backend.common.exception.NoActiveSubscriptionException;
import org.tornotron.echno_backend.common.exception.PlanNotFoundException;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Subscription lifecycle and feature-access checks for the SaaS billing model.
 *
 * <p>Resolves a user's active subscription (cached per user), decides whether a user may use
 * a feature given their plan and any per-feature quota, and records metered usage. Quota
 * windows are computed per {@link QuotaPeriod} (hourly through annual, plus an all-time
 * bucket). Creating, changing, and canceling a subscription each evict the user's cache
 * entry so the next access re-reads the current state.
 *
 * <p>Entities never leave this class, and never reach the cache either. Every method that a
 * caller can reach returns a DTO built while the persistence context is still open, because
 * {@code spring.jpa.open-in-view} is off and {@link Subscription#getPlan()} and
 * {@link Plan#getPlanFeatures()} are both lazy: mapping a subscription anywhere outside a
 * transaction throws {@code LazyInitializationException}. Reads that go through the cache work
 * on a {@link SubscriptionSnapshot}, an immutable copy taken inside the loading transaction,
 * which is the only form of the row that is safe to hold once that transaction is gone.
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
     * Returns the user's active subscription as a DTO.
     *
     * @param userId The ID of the user whose subscription to resolve.
     * @return The active subscription, or empty if the user has none.
     */
    @Transactional(readOnly = true)
    public Optional<SubscriptionDto> getActiveSubscription(Long userId) {
        return loadActiveSubscription(userId).map(BillingMapper::toSubscriptionDto);
    }

    /**
     * Returns every subscription the user has ever held, most recently created first.
     *
     * @param userId The ID of the user whose subscriptions to list.
     * @return The user's subscription history, newest first, empty if they have none.
     */
    @Transactional(readOnly = true)
    public List<SubscriptionDto> getSubscriptionHistory(Long userId) {
        return BillingMapper.toSubscriptionDtoList(
                subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId));
    }

    /**
     * Loads a snapshot of the user's active subscription, serving it from the cache when present.
     *
     * <p>On a cache miss it queries the repository and, if a subscription is found, copies it and
     * its plan into a snapshot before the transaction that loaded it ends, then caches that. The
     * entity itself is never handed out and never cached, so nothing downstream can be holding a
     * detached instance whose uninitialized parts throw {@code LazyInitializationException} in a
     * later transaction that cannot reattach it.
     *
     * <p>Taking the copy is what the fetch joins on {@code findActiveSubscriptionByUserId} are
     * for now. Without them the copy still comes out complete, because it is built inside the
     * transaction and the lazy reads resolve there, but it costs a query per plan feature on
     * every miss instead of one query for the graph. Weakening them is a performance regression
     * rather than a correctness one, which is a change of kind: it used to be the thing standing
     * between the cache and a 500.
     *
     * @param userId The ID of the user whose subscription to resolve.
     * @return A snapshot of the active subscription, or empty if the user has none.
     */
    private Optional<SubscriptionSnapshot> loadActiveSubscription(Long userId) {

        SubscriptionSnapshot cached = subscriptionCache.get(userId);
        if(cached != null) {
            return Optional.of(cached);
        }

        Optional<SubscriptionSnapshot> subscription = subscriptionRepository
                .findActiveSubscriptionByUserId(userId)
                .map(BillingMapper::toSubscriptionSnapshot);

        subscription.ifPresent(snapshot -> subscriptionCache.put(userId, snapshot));

        return subscription;
    }

    /**
     * Loads the user's active subscription entity for a caller that is about to change it,
     * always from the database and never from the cache.
     *
     * <p>{@link #loadActiveSubscription(Long)} answers from the cache, with a snapshot that is
     * immutable and detached from any persistence context. Nothing a write path did to it would
     * reach the database, and the shape of the change a write needs to make is a change to a
     * managed entity. Reading fresh gives the caller one of its own, so the change is confined
     * to its transaction and reaches other callers only through the eviction on
     * {@link SubscriptionCache#evictOnWrite(Long)}. That the snapshot cannot be mutated in the
     * first place is what closed the older hazard here, where the write path resolved its
     * subscription through the cache and set fields straight onto the instance every concurrent
     * reader of that user was sharing.
     *
     * <p>The read deliberately does not populate the cache either. The row is about to change,
     * so caching what it looks like now would only have to be undone.
     *
     * @param userId The ID of the user whose subscription is about to be changed.
     * @return The active subscription as a managed entity, or empty if the user has none.
     */
    private Optional<Subscription> loadActiveSubscriptionForWrite(Long userId) {
        return subscriptionRepository.findActiveSubscriptionByUserId(userId);
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
    @Transactional(readOnly = true)
    public FeatureAccessResultDto checkFeatureAccess(Long userId, String featureCode) {
        Optional<SubscriptionSnapshot> subscriptionOptional = loadActiveSubscription(userId);

        if(subscriptionOptional.isEmpty()) {
            return FeatureAccessResultDto.noSubscription();
        }

        Optional<PlanFeatureSnapshot> planFeatureOptional =
                subscriptionOptional.get().feature(featureCode);

        if(planFeatureOptional.isEmpty()) {
            return FeatureAccessResultDto.featureNotInPlan();
        }

        PlanFeatureSnapshot planFeature = planFeatureOptional.get();

        if(planFeature.featureType() == FeatureType.BOOLEAN) {
            return planFeature.isFeatureEnabled()
                    ? FeatureAccessResultDto.allowed()
                    : FeatureAccessResultDto.featureDisabled();
        }

        if(planFeature.hasQuota()) {
            return checkQuotaAccess(userId, planFeature);
        }

        return FeatureAccessResultDto.allowed();
    }

    /**
     * Counts what the user has already used of a metered feature in the current period and
     * compares it against the plan's limit.
     *
     * <p>The count is always a query. Usage moves on every metered request and is the one input
     * to an entitlement decision that could not be cached behind the subscription without being
     * wrong almost immediately, so it is deliberately left out of the snapshot. The snapshot
     * supplies the limit, the period and the feature id the usage rows are keyed on.
     */
    private FeatureAccessResultDto checkQuotaAccess(Long userId, PlanFeatureSnapshot planFeature) {
        Long quotaLimit = planFeature.quotaLimit();
        QuotaPeriod period = planFeature.quotaPeriod();

        Instant[] periodBounds = calculatePeriodBounds(period);

        Long currentUsage = usageRecordRepository.sumUsageForPeriod(
                userId,
                planFeature.featureId(),
                periodBounds[0],
                periodBounds[1]
        );

        if(currentUsage >= quotaLimit) {
            return FeatureAccessResultDto.quotaExceeded(currentUsage, quotaLimit);
        }

        return FeatureAccessResultDto.allowed(currentUsage, quotaLimit);
    }

    /**
     * The start and end of the quota window a usage record falls in, for a given period.
     *
     * <p>Computed in UTC, deliberately. These bounds are both the window
     * {@link #checkQuotaAccess} sums existing usage over and the {@code periodStart} and
     * {@code periodEnd} {@link #recordUsage} stamps on every {@link UsageRecord} it writes, so
     * the two only line up while every process computing them agrees on where a period begins.
     * They were derived from {@code ZoneId.systemDefault()}, which made that agreement a
     * property of the container's timezone: a month or year boundary moves by a whole day
     * between two zones, which moves a user between quota periods and can allow or refuse them
     * against a window that does not match the rows being counted.
     *
     * @param period The quota period to compute the current window for.
     * @return The window start and end, in that order.
     */
    private Instant[] calculatePeriodBounds(QuotaPeriod period) {
        Instant now = Instant.now();
        ZonedDateTime zdt = now.atZone(ZoneOffset.UTC);

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
     * feature's current period.
     *
     * <p>It does not evict the user's cached subscription, and used to. Nothing about the
     * subscription or its plan changes here, and the usage this writes is not part of what the
     * cache holds: {@link #checkQuotaAccess} sums it from the usage table on every check. The
     * eviction bought no freshness and cost the entry, on the one path that runs on every
     * metered request, which is where the cache is meant to earn its keep.
     *
     * @param userId The ID of the user consuming the feature.
     * @param featureCode The code of the feature being consumed.
     * @param amount The quantity to record for this usage event.
     */
    @Transactional
    public void recordUsage(Long userId, String featureCode, Long amount) {
        Optional<SubscriptionSnapshot> subscriptionOptional = loadActiveSubscription(userId);
        if(subscriptionOptional.isEmpty()) {
            log.warn("Attempted to record usage for user without active subscription: {}", userId);
            return;
        }

        SubscriptionSnapshot subscription = subscriptionOptional.get();

        PlanFeatureSnapshot planFeature = subscription.feature(featureCode).orElse(null);

        if(planFeature == null) {
            log.warn("Feature {} not found in user's plan", featureCode);
            return;
        }

        QuotaPeriod period = planFeature.quotaPeriod() != null
                ? planFeature.quotaPeriod()
                : QuotaPeriod.MONTHLY;

        Instant[] periodBounds = calculatePeriodBounds(period);

        UsageRecord usageRecord = UsageRecord.builder()
                .userId(userId)
                .subscriptionId(subscription.id())
                .featureId(planFeature.featureId())
                .usageAmount(amount)
                .periodStart(periodBounds[0])
                .periodEnd(periodBounds[1])
                .build();

        usageRecordRepository.save(usageRecord);
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
     * @return The created subscription, as a DTO.
     * @throws DuplicateResourceException if the user already has an active subscription.
     * @throws PlanNotFoundException if no plan with the given code exists.
     */
    @Transactional
    public SubscriptionDto createSubscription(Long userId, String planCode, BillingPeriod billingPeriod) {
        if (loadActiveSubscriptionForWrite(userId).isPresent()) {
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

        subscriptionCache.evictOnWrite(userId);

        log.info("Created subscription {} for user {} on plan {} ({})", 
                subscription.getId(), userId, planCode, billingPeriod);

        return BillingMapper.toSubscriptionDto(subscription);
    }

    /**
     * Switches a user's active subscription to a different plan.
     *
     * <p>The subscription keeps its period and status; only the plan changes. The user's cached
     * subscription is evicted.
     *
     * @param userId The ID of the user whose plan to change.
     * @param newPlanCode The code of the new plan; it must be active.
     * @return The updated subscription, as a DTO.
     * @throws NoActiveSubscriptionException if the user has no active subscription.
     * @throws PlanNotFoundException if no active plan with the given code exists.
     */
    @Transactional
    public SubscriptionDto changeSubscription(Long userId,String newPlanCode) {
        Subscription currentSubscription = loadActiveSubscriptionForWrite(userId)
                .orElseThrow(() -> new NoActiveSubscriptionException("User " + userId + " has no active subscription"));

        Plan newPlan = planRepository.findByCodeAndIsActiveTrue(newPlanCode)
                .orElseThrow(() -> new PlanNotFoundException("Active plan with code '" + newPlanCode + "' was not found"));

        currentSubscription.setPlan(newPlan);
        currentSubscription = subscriptionRepository.save(currentSubscription);

        subscriptionCache.evictOnWrite(userId);

        log.info("Changed subscription {} for user {} to plan {}",
                currentSubscription.getId(), userId, newPlanCode);

        return BillingMapper.toSubscriptionDto(currentSubscription);

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

       Subscription subscription = loadActiveSubscriptionForWrite(userId)
               .orElseThrow(() -> new NoActiveSubscriptionException("User " + userId + " has no active subscription"));

       if(immediate) {
           subscription.setStatus(SubscriptionStatus.CANCELED);
           subscription.setCanceledAt(Instant.now());
       } else {
           subscription.setCancelAtPeriodEnd(true);
       }

       subscriptionRepository.save(subscription);
       subscriptionCache.evictOnWrite(userId);

       log.info("Canceled subscription {} for user {} (immediate: {})",
               subscription.getId(), userId, immediate);
    }



}
