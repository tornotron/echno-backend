package org.tornotron.echno_backend.billing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Payload to cancel a subscription.")
@Data
public class SubscriptionCancelDto {

    @Schema(description = "If true, cancel now; if false, cancel at the end of the current billing period.", example = "false")
    private boolean immediate = false;

    @Schema(description = "Optional free-text reason for the cancellation.", example = "Switching to annual billing")
    private String reason;
}
