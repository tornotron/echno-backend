package org.tornotron.echno_backend.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PlanCreateDto {

    @NotBlank(message = "Plan code is required")
    @Size(max = 50, message = "Plan code must not exceed 50 characters")
    private String code;

    @NotBlank(message = "Plan name is required")
    private String name;

    private String description;

    @NotNull(message = "Monthly price is required")
    private BigDecimal monthlyPrice;

    private BigDecimal annualPrice;

    @Size(max = 3, message = "Currency code must be 3 characters")
    private String currency = "INR";

    private Boolean isPublic = true;

    private Integer trialDays = 0;

    private Integer maxUsers;

    private Integer sortOrder = 0;
}
