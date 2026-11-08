package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

@Schema(description = "A new AI reading limit. A null value goes back to the configured default.")
public record LlmBudgetRequest(
        @Schema(description = "Estimated dollars", example = "10.00", nullable = true)
        @DecimalMin("0") @DecimalMax("100000") BigDecimal budgetUsd,
        @Schema(description = "Prompt and completion tokens together", example = "10000000", nullable = true)
        @Min(0) @Max(10_000_000_000L) Long budgetTokens) {
}
