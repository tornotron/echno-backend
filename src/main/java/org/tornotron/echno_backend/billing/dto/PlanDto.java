package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "A billing plan, including its price and assigned features, as returned by the API.")
@Value
@Builder
public class PlanDto {
    @Schema(description = "Numeric id of the plan.", example = "3")
    Long id;
    @Schema(description = "Unique code identifying the plan.", example = "professional-monthly")
    String code;
    @Schema(description = "Display name of the plan.", example = "Professional")
    String name;
    @Schema(description = "Longer description shown on the pricing page.", example = "For growing teams managing multiple active sites.")
    String description;
    @Schema(description = "Optimistic locking version of the plan record.", example = "4")
    Integer version;
    @Schema(description = "Price charged per month, in the plan currency.", example = "4999.00")
    BigDecimal monthlyPrice;
    @Schema(description = "Price charged per year, in the plan currency, when billed annually.", example = "49990.00")
    BigDecimal annualPrice;
    @Schema(description = "ISO 4217 currency code for the plan prices.", example = "INR")
    String currency;
    @Schema(description = "Whether the plan currently accepts new subscriptions.", example = "true")
    Boolean isActive;
    @Schema(description = "Whether the plan is shown on the public pricing page.", example = "true")
    Boolean isPublic;
    @Schema(description = "Number of trial days granted on first subscription.", example = "14")
    Integer trialDays;
    @Schema(description = "Maximum number of users allowed under this plan, or null for unlimited.", example = "25")
    Integer maxUsers;
    @Schema(description = "Display order relative to other plans, ascending.", example = "2")
    Integer sortOrder;
    @Schema(description = "Features currently assigned to this plan.")
    List<PlanFeatureDto> features;
}
