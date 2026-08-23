package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Schema(description = "Result of checking whether a user's subscription grants access to a feature.")
@Value
@Builder
public class FeatureAccessResultDto {
    @Schema(description = "Whether the feature may be used.", example = "true")
    boolean allowed;
    @Schema(description = "User-facing message, such as an upgrade prompt, when access is denied.", example = "Upgrade to higher tier plan")
    String message;
    @Schema(description = "Machine-readable reason for the result, such as quota-exceeded.", example = "Quota exceeded")
    String reason;
    @Schema(description = "Amount of the metered feature used in the current period, when quota-limited.", example = "480")
    Long currentUsage;
    @Schema(description = "Maximum amount allowed in the current period, when quota-limited.", example = "500")
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
                .message("Upgrade to higher tier plan")
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
