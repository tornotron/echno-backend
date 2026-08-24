package org.tornotron.echno_backend.finance.budget.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Payload to create a budget head (cost category).")
public record CreateCostCategoryRequest(
        @Schema(description = "Cost category name, unique within the tenant.", example = "Materials")
        @NotBlank @Size(max = 200) String name,

        @Schema(description = "Optional short code for the head.", example = "MAT")
        @Size(max = 20) String code,

        @Schema(description = "Optional ledger expense account to map this head to.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
        UUID expenseAccountId
) {}
