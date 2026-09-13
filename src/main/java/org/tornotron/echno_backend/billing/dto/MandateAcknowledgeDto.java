package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;
import org.tornotron.echno_backend.billing.gateway.MandateMethod;

@Schema(description = "The buyer's acknowledgement of the e-mandate terms for a plan and cycle.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MandateAcknowledgeDto {

    @Schema(description = "Plan the mandate is for.", example = "PRO")
    @NotBlank(message = "Plan code is required")
    private String planCode;

    @Schema(description = "Billing cycle; MONTHLY when omitted.", example = "MONTHLY")
    private BillingPeriod billingPeriod;

    @Schema(description = "The buyer accepted per-charge authentication for a cycle above the RBI cap.", example = "true")
    private boolean acceptPerChargeAfa;

    @Schema(description = "The instrument the buyer intends to register on, when known.", example = "UPI_AUTOPAY")
    private MandateMethod method;
}
