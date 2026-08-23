package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;

@Schema(description = "Payload to create a subscription to a plan.")
@Data
public class SubscriptionCreateDto {

    @Schema(description = "Code of the plan to subscribe to.", example = "professional-monthly")
    @NotBlank(message = "Plan code is required")
    private String planCode;

    @Schema(description = "Billing period for the subscription.", example = "MONTHLY")
    private BillingPeriod billingPeriod = BillingPeriod.MONTHLY;
}
