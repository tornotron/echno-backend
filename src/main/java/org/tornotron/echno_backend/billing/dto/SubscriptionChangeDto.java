package org.tornotron.echno_backend.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SubscriptionChangeDto {

    @NotBlank(message = "New plan code is required")
    private String newPlanCode;
}
