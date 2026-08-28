package org.tornotron.echno_backend.billing.dto;

import org.tornotron.echno_backend.billing.*;
import org.tornotron.echno_backend.billing.snapshot.PlanFeatureSnapshot;
import org.tornotron.echno_backend.billing.snapshot.PlanSnapshot;
import org.tornotron.echno_backend.billing.snapshot.SubscriptionSnapshot;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the billing response DTOs, and the snapshots the subscription cache holds.
 *
 * <p>Every entity-to-DTO conversion here goes through a snapshot first, so the mapping from
 * persistent state to response state is written once and the cached value and the response are
 * built from the same reading of the row. The entity overloads therefore have to be called
 * inside the transaction that loaded the entity, exactly as before: they touch the lazy
 * {@code Subscription.plan} and {@code Plan.planFeatures}. The snapshot overloads have no such
 * requirement, which is the point of taking a snapshot.
 */
public class BillingMapper {

    private BillingMapper() {
    }

    /**
     * Copies a plan and its features out of the persistence context.
     *
     * @param plan The plan entity to copy, with its features initialized.
     * @return An immutable copy of the plan, or null if the plan was null.
     */
    public static PlanSnapshot toPlanSnapshot(Plan plan) {
        if (plan == null) return null;

        return new PlanSnapshot(
                plan.getId(),
                plan.getCode(),
                plan.getName(),
                plan.getDescription(),
                plan.getVersion(),
                plan.getMonthlyPrice(),
                plan.getAnnualPrice(),
                plan.getCurrency(),
                plan.getIsActive(),
                plan.getIsPublic(),
                plan.getTrialDays(),
                plan.getMaxUsers(),
                plan.getSortOrder(),
                plan.getPlanFeatures() != null
                        ? plan.getPlanFeatures().stream()
                        .map(BillingMapper::toPlanFeatureSnapshot)
                        .collect(Collectors.toList())
                        : Collections.emptyList());
    }

    /**
     * Copies one of a plan's feature grants out of the persistence context, feature id included.
     *
     * @param planFeature The plan-feature entity to copy, with its feature initialized.
     * @return An immutable copy of the grant, or null if the grant was null.
     */
    public static PlanFeatureSnapshot toPlanFeatureSnapshot(PlanFeature planFeature) {
        if (planFeature == null) return null;

        Feature feature = planFeature.getFeature();
        return new PlanFeatureSnapshot(
                planFeature.getId(),
                feature != null ? feature.getId() : null,
                feature != null ? feature.getCode() : null,
                feature != null ? feature.getName() : null,
                feature != null ? feature.getFeatureType() : null,
                planFeature.getEnabled(),
                planFeature.getQuotaLimit(),
                planFeature.getQuotaPeriod());
    }

    /**
     * Copies a subscription and the plan behind it out of the persistence context.
     *
     * @param subscription The subscription entity to copy, with its plan graph initialized.
     * @return An immutable copy of the subscription, or null if the subscription was null.
     */
    public static SubscriptionSnapshot toSubscriptionSnapshot(Subscription subscription) {
        if (subscription == null) return null;

        return new SubscriptionSnapshot(
                subscription.getId(),
                subscription.getUserId(),
                subscription.getStatus(),
                subscription.getCurrentPeriodStart(),
                subscription.getCurrentPeriodEnd(),
                subscription.getTrialStart(),
                subscription.getTrialEnd(),
                subscription.getCancelAtPeriodEnd(),
                subscription.getCanceledAt(),
                subscription.getCreatedAt(),
                subscription.getCancellationReason(),
                toPlanSnapshot(subscription.getPlan()));
    }

    public static PlanDto toPlanDto(Plan plan) {
        return toPlanDto(toPlanSnapshot(plan));
    }

    public static PlanDto toPlanDto(PlanSnapshot plan) {
        if (plan == null) return null;

        return PlanDto.builder()
                .id(plan.id())
                .code(plan.code())
                .name(plan.name())
                .description(plan.description())
                .version(plan.version())
                .monthlyPrice(plan.monthlyPrice())
                .annualPrice(plan.annualPrice())
                .currency(plan.currency())
                .isActive(plan.isActive())
                .isPublic(plan.isPublic())
                .trialDays(plan.trialDays())
                .maxUsers(plan.maxUsers())
                .sortOrder(plan.sortOrder())
                .features(plan.features() != null
                        ? plan.features().stream()
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
        return toPlanFeatureDto(toPlanFeatureSnapshot(planFeature));
    }

    public static PlanFeatureDto toPlanFeatureDto(PlanFeatureSnapshot planFeature) {
        if (planFeature == null) return null;

        return PlanFeatureDto.builder()
                .id(planFeature.id())
                .featureCode(planFeature.featureCode())
                .featureName(planFeature.featureName())
                .featureType(planFeature.featureType())
                .enabled(planFeature.enabled())
                .quotaLimit(planFeature.quotaLimit())
                .quotaPeriod(planFeature.quotaPeriod())
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
        return toSubscriptionDto(toSubscriptionSnapshot(subscription));
    }

    /**
     * Builds the response DTO from a snapshot, resolving the time-derived entitlement flags
     * against the current instant.
     *
     * <p>{@code expired} and {@code inTrial} are computed here rather than carried on the
     * snapshot, so a snapshot that has been sitting in the cache reports them for now and not
     * for the moment it was taken.
     *
     * @param subscription The snapshot to render.
     * @return The response DTO, or null if the snapshot was null.
     */
    public static SubscriptionDto toSubscriptionDto(SubscriptionSnapshot subscription) {
        if (subscription == null) return null;

        Instant now = Instant.now();
        return SubscriptionDto.builder()
                .id(subscription.id())
                .userId(subscription.userId())
                .plan(toPlanDto(subscription.plan()))
                .status(subscription.status())
                .currentPeriodStart(subscription.currentPeriodStart())
                .currentPeriodEnd(subscription.currentPeriodEnd())
                .trialStart(subscription.trialStart())
                .trialEnd(subscription.trialEnd())
                .cancelAtPeriodEnd(subscription.cancelAtPeriodEnd())
                .canceledAt(subscription.canceledAt())
                .createdAt(subscription.createdAt())
                .cancellationReason(subscription.cancellationReason())
                .active(subscription.isActive())
                .inTrial(subscription.isInTrial(now))
                .expired(subscription.isExpired(now))
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
