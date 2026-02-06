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

@Service
@Slf4j
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final SubscriptionCache subscriptionCache;

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
                throw new IllegalArgumentException("Unsupported quota period: " + period);
        }

    }

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

    @Transactional
    public Subscription createSubscription(Long userId, String planCode, BillingPeriod billingPeriod) {
        if (getActiveSubscription(userId).isPresent()) {
            throw new DuplicateResourceException("User already has an active subscription. Please upgrade/change plan instead.");
        }

        Plan plan = planRepository.findByCodeWithFeatures(planCode)
                .orElseThrow(() -> new PlanNotFoundException("Plan not found: " + planCode));

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

    @Transactional
    public Subscription changeSubscription(Long userId,String newPlanCode) {
        Subscription currentSubscription = getActiveSubscription(userId)
                .orElseThrow(() -> new NoActiveSubscriptionException("No active subscription for the user"));

        Plan newPlan = planRepository.findByCodeAndIsActiveTrue(newPlanCode)
                .orElseThrow(() -> new PlanNotFoundException("Plan not Found: {}" + newPlanCode));

        currentSubscription.setPlan(newPlan);
        currentSubscription = subscriptionRepository.save(currentSubscription);

        subscriptionCache.evict(userId);

        log.info("Changed subscription {} for user {} to plan {}",
                currentSubscription.getId(), userId, newPlanCode);

        return currentSubscription;

    }

    @Transactional
    public void cancelSubscription(Long userId, boolean immediate) {

       Subscription subscription = getActiveSubscription(userId)
               .orElseThrow(() -> new NoActiveSubscriptionException("No active subscription for the user"));

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
