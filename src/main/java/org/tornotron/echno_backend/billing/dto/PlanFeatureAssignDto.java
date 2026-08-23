package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;

@Schema(description = "Payload to assign a feature to a plan, optionally with a quota.")
@Data
public class PlanFeatureAssignDto {

    @Schema(description = "Code of the feature to assign.", example = "report-export")
    @NotBlank(message = "Feature code is required")
    private String featureCode;

    @Schema(description = "Whether the feature is enabled once assigned.", example = "true")
    private Boolean enabled = true;

    @Schema(description = "Maximum usage allowed per quota period, for quota or metered features.", example = "500")
    private Long quotaLimit;

    @Schema(description = "Period over which the quota resets.", example = "MONTHLY")
    private QuotaPeriod quotaPeriod;
}
