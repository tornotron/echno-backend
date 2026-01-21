package org.tornotron.echno_backend.billing.dto;

import org.tornotron.echno_backend.billing.*;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class BillingMapper {

    private BillingMapper() {
    }

    public static PlanDto toPlanDto(Plan plan) {
        if (plan == null) return null;

        return PlanDto.builder()
                .id(plan.getId())
                .code(plan.getCode())
                .name(plan.getName())
                .description(plan.getDescription())
                .version(plan.getVersion())
                .monthlyPrice(plan.getMonthlyPrice())
                .annualPrice(plan.getAnnualPrice())
                .currency(plan.getCurrency())
                .isActive(plan.getIsActive())
                .isPublic(plan.getIsPublic())
                .trialDays(plan.getTrialDays())
                .maxUsers(plan.getMaxUsers())
                .sortOrder(plan.getSortOrder())
                .features(plan.getPlanFeatures() != null
                        ? plan.getPlanFeatures().stream()
                        .map(BillingMapper::toPlanFeatureDto)
                        .collect(Collectors.toList())
                        : Collections.emptyList())
                .build();
    }

    public static PlanDto toPlanDtoWithoutFeatures(Plan plan) {
        if (plan == null) return null;

        return PlanDto.builder()
                .id(plan.getId())
                .code(plan.getCode())
                .name(plan.getName())
                .description(plan.getDescription())
                .version(plan.getVersion())
                .monthlyPrice(plan.getMonthlyPrice())
                .annualPrice(plan.getAnnualPrice())
                .currency(plan.getCurrency())
                .isActive(plan.getIsActive())
                .isPublic(plan.getIsPublic())
                .trialDays(plan.getTrialDays())
                .maxUsers(plan.getMaxUsers())
                .sortOrder(plan.getSortOrder())
                .build();
    }

    public static PlanFeatureDto toPlanFeatureDto(PlanFeature planFeature) {
        if (planFeature == null) return null;

        Feature feature = planFeature.getFeature();
        return PlanFeatureDto.builder()
                .id(planFeature.getId())
                .featureCode(feature != null ? feature.getCode() : null)
                .featureName(feature != null ? feature.getName() : null)
                .featureType(feature != null ? feature.getFeatureType() : null)
                .enabled(planFeature.getEnabled())
                .quotaLimit(planFeature.getQuotaLimit())
                .quotaPeriod(planFeature.getQuotaPeriod())
                .build();
    }

    public static FeatureDto toFeatureDto(Feature feature) {
        if (feature == null) return null;

        return FeatureDto.builder()
                .id(feature.getId())
                .code(feature.getCode())
                .name(feature.getName())
                .description(feature.getDescription())
                .featureType(feature.getFeatureType())
                .category(feature.getCategory())
                .isActive(feature.getIsActive())
                .build();
    }

    public static SubscriptionDto toSubscriptionDto(Subscription subscription) {
        if (subscription == null) return null;

        return SubscriptionDto.builder()
                .id(subscription.getId())
                .userId(subscription.getUserId())
                .plan(toPlanDto(subscription.getPlan()))
                .status(subscription.getStatus())
                .currentPeriodStart(subscription.getCurrentPeriodStart())
                .currentPeriodEnd(subscription.getCurrentPeriodEnd())
                .trialStart(subscription.getTrialStart())
                .trialEnd(subscription.getTrialEnd())
                .cancelAtPeriodEnd(subscription.getCancelAtPeriodEnd())
                .canceledAt(subscription.getCanceledAt())
                .createdAt(subscription.getCreatedAt())
                .cancellationReason(subscription.getCancellationReason())
                .active(subscription.isActive())
                .inTrial(subscription.isInTrial())
                .expired(subscription.isExpired())
                .build();
    }

    public static List<PlanDto> toPlanDtoList(List<Plan> plans) {
        return plans.stream()
                .map(BillingMapper::toPlanDto)
                .collect(Collectors.toList());
    }

    public static List<FeatureDto> toFeatureDtoList(List<Feature> features) {
        return features.stream()
                .map(BillingMapper::toFeatureDto)
                .collect(Collectors.toList());
    }

    public static List<SubscriptionDto> toSubscriptionDtoList(List<Subscription> subscriptions) {
        return subscriptions.stream()
                .map(BillingMapper::toSubscriptionDto)
                .collect(Collectors.toList());
    }
}
