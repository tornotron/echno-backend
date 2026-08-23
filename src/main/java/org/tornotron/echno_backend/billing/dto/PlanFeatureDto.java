package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;
import org.tornotron.echno_backend.billing.enums.FeatureType;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;

@Schema(description = "A feature as assigned to a plan, including its quota configuration on that plan.")
@Value
@Builder
public class PlanFeatureDto {
    @Schema(description = "Numeric id of the plan-feature assignment.", example = "7")
    Long id;
    @Schema(description = "Code of the assigned feature.", example = "report-export")
    String featureCode;
    @Schema(description = "Display name of the assigned feature.", example = "PDF Report Export")
    String featureName;
    @Schema(description = "How the feature is measured.", example = "QUOTA")
    FeatureType featureType;
    @Schema(description = "Whether the feature is enabled on this plan.", example = "true")
    Boolean enabled;
    @Schema(description = "Maximum usage allowed per quota period, for quota or metered features.", example = "500")
    Long quotaLimit;
    @Schema(description = "Period over which the quota resets.", example = "MONTHLY")
    QuotaPeriod quotaPeriod;
}
