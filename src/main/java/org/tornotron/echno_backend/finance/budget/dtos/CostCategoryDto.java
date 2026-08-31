package org.tornotron.echno_backend.finance.budget.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "A budget head (cost category) in the org-level master list, optionally aligned to "
        + "a ledger expense account.")
public record CostCategoryDto(
        @Schema(description = "Unique cost category id.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID id,

        @Schema(description = "Cost category name, unique within the tenant.", example = "Materials")
        String name,

        @Schema(description = "Optional short code for the head.", example = "MAT")
        String code,

        @Schema(description = "Ledger expense account this head maps to, or null.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10", nullable = true)
        UUID expenseAccountId,

        @Schema(description = "Code of the mapped expense account, or null.", example = "5100", nullable = true)
        String expenseAccountCode,

        @Schema(description = "Whether the head is active and available for tagging.", example = "true")
        boolean active
) {}
