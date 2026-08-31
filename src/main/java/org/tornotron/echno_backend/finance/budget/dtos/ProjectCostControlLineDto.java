package org.tornotron.echno_backend.finance.budget.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Cost-control roll-up for one budget head on a project: what was allocated against "
        + "what has been committed and spent, and what remains.")
public record ProjectCostControlLineDto(
        @Schema(description = "Cost category (budget head) id, or null for the project total row.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10", nullable = true)
        UUID costCategoryId,

        @Schema(description = "Cost category name, or 'Total' for the project total row.", example = "Materials")
        String costCategoryName,

        @Schema(description = "Amount allocated to this head.", example = "500000.00")
        BigDecimal allocated,

        @Schema(description = "Approved obligations not yet fully paid, tagged to this head.", example = "120000.00")
        BigDecimal committed,

        @Schema(description = "Amount already spent (on fully paid invoices), tagged to this head.",
                example = "300000.00")
        BigDecimal spent,

        @Schema(description = "Remaining budget: allocated minus committed minus spent.", example = "80000.00")
        BigDecimal remaining,

        @Schema(description = "Whether committed plus spent has exceeded the allocation.", example = "false")
        boolean overBudget
) {}
