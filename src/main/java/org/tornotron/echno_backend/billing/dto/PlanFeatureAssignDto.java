package org.tornotron.echno_backend.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.tornotron.echno_backend.billing.enums.QuotaPeriod;

@Data
public class PlanFeatureAssignDto {

    @NotBlank(message = "Feature code is required")
    private String featureCode;

    private Boolean enabled = true;

    private Long quotaLimit;

    private QuotaPeriod quotaPeriod;
}
