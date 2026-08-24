package org.tornotron.echno_backend.finance.budget.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "One budget-head allocation on a project: the amount set aside for a cost category.")
public record BudgetAllocationDto(
        @Schema(description = "Unique allocation id.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID id,

        @Schema(description = "Project the allocation belongs to.", example = "42")
        Long projectId,

        @Schema(description = "Cost category (budget head) the amount is allocated to.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
        UUID costCategoryId,

        @Schema(description = "Cost category name.", example = "Materials")
        String costCategoryName,

        @Schema(description = "Amount allocated to this head.", example = "500000.00")
        BigDecimal allocatedAmount
) {}
