package org.tornotron.echno_backend.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SubscriptionCreateDto {

    @NotBlank(message = "Plan code is required")
    private String planCode;
}
