package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;

@Schema(description = "Opens a hosted checkout for the caller's organization on a plan.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutSessionCreateDto {

    @Schema(description = "Code of the plan to buy.", example = "PRO")
    @NotBlank(message = "Plan code is required")
    private String planCode;

    @Schema(description = "Billing cycle; MONTHLY when omitted.", example = "MONTHLY")
    private BillingPeriod billingPeriod;

    @Schema(description = "The buyer accepted that a cycle above the RBI cap needs authentication on every charge.", example = "false")
    private boolean acceptPerChargeAfa;
}
