package org.tornotron.echno_backend.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.tornotron.echno_backend.billing.enums.BillingPeriod;

@Data
public class SubscriptionCreateDto {

    @NotBlank(message = "Plan code is required")
    private String planCode;

    private BillingPeriod billingPeriod = BillingPeriod.MONTHLY;
}
