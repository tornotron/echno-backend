package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Schema(description = "Payload to move a subscription to a different plan.")
@Data
public class SubscriptionChangeDto {

    @Schema(description = "Code of the plan to move the subscription to.", example = "enterprise-monthly")
    @NotBlank(message = "New plan code is required")
    private String newPlanCode;
}
