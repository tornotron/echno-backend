package org.tornotron.echno_backend.finance.settings.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Organization-level finance settings.")
public record FinanceSettingsDto(

        @Schema(description = "Auto-approval threshold. Null means every construction invoice needs "
                + "manual approval; a value T auto-approves invoices whose total is below T.",
                example = "50000.0000")
        BigDecimal approvalThreshold
) {}
