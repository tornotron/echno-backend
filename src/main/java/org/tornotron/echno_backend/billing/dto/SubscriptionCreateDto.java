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

    @Schema(description = "For a paid plan that goes through the payment provider, with a cycle above the RBI per-debit "
            + "cap: the buyer accepts that each debit will need them to authenticate. Ignored for free plans and "
            + "without a provider.", example = "false")
    private boolean acceptPerChargeAfa = false;
}
