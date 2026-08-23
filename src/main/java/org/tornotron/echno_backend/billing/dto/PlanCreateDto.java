package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Schema(description = "Payload to create or update a billing plan.")
@Data
public class PlanCreateDto {

    @Schema(description = "Unique code identifying the plan.", example = "professional-monthly")
    @NotBlank(message = "Plan code is required")
    @Size(max = 50, message = "Plan code must not exceed 50 characters")
    private String code;

    @Schema(description = "Display name of the plan.", example = "Professional")
    @NotBlank(message = "Plan name is required")
    private String name;

    @Schema(description = "Longer description shown on the pricing page.", example = "For growing teams managing multiple active sites.")
    private String description;

    @Schema(description = "Price charged per month, in the plan currency.", example = "4999.00")
    @NotNull(message = "Monthly price is required")
    private BigDecimal monthlyPrice;

    @Schema(description = "Price charged per year, in the plan currency, when billed annually.", example = "49990.00")
    private BigDecimal annualPrice;

    @Schema(description = "ISO 4217 currency code for the plan prices.", example = "INR")
    @Size(max = 3, message = "Currency code must be 3 characters")
    private String currency = "INR";

    @Schema(description = "Whether the plan is shown on the public pricing page.", example = "true")
    private Boolean isPublic = true;

    @Schema(description = "Number of trial days granted on first subscription.", example = "14")
    private Integer trialDays = 0;

    @Schema(description = "Maximum number of users allowed under this plan, or null for unlimited.", example = "25")
    private Integer maxUsers;

    @Schema(description = "Display order relative to other plans, ascending.", example = "2")
    private Integer sortOrder = 0;
}
