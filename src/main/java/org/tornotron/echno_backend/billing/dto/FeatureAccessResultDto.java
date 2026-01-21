package org.tornotron.echno_backend.billing.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class FeatureAccessResultDto {
    boolean allowed;
    String reason;
    Long currentUsage;
    Long quotaLimit;

    public static FeatureAccessResultDto allowed() {
        return FeatureAccessResultDto.builder()
                .allowed(true)
                .build();
    }

    public static FeatureAccessResultDto allowed(Long currentUsage, Long quotaLimit) {
        return FeatureAccessResultDto.builder()
                .allowed(true)
                .currentUsage(currentUsage)
                .quotaLimit(quotaLimit)
                .build();
    }

    public static FeatureAccessResultDto noSubscription() {
        return FeatureAccessResultDto.builder()
                .allowed(false)
                .reason("No active subscription")
                .build();
    }

    public static FeatureAccessResultDto featureNotInPlan() {
        return FeatureAccessResultDto.builder()
                .allowed(false)
                .reason("Feature not included in current plan")
                .build();
    }

    public static FeatureAccessResultDto featureDisabled() {
        return FeatureAccessResultDto.builder()
                .allowed(false)
                .reason("Feature is disabled")
                .build();
    }

    public static FeatureAccessResultDto quotaExceeded(Long currentUsage, Long quotaLimit) {
        return FeatureAccessResultDto.builder()
                .allowed(false)
                .reason("Quota exceeded")
                .currentUsage(currentUsage)
                .quotaLimit(quotaLimit)
                .build();
    }
}
