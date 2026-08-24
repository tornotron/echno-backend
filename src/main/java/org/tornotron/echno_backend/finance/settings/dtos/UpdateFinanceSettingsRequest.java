package org.tornotron.echno_backend.finance.settings.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

@Schema(description = "Payload to update the organization-level finance settings.")
public record UpdateFinanceSettingsRequest(

        @Schema(description = "Auto-approval threshold. Send null to require manual approval on every "
                + "construction invoice; send a value T to auto-approve invoices whose total is below T.",
                example = "50000.00")
        @PositiveOrZero(message = "approvalThreshold must not be negative")
        BigDecimal approvalThreshold
) {}
