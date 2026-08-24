package org.tornotron.echno_backend.finance.budget.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

@Schema(description = "Payload to set the amount allocated to a budget head on a project. The project and "
        + "cost category come from the path; upserting replaces any existing allocation for the pair.")
public record UpsertBudgetAllocationRequest(
        @Schema(description = "Amount to allocate to this head.", example = "500000.00")
        @NotNull @DecimalMin(value = "0.0") BigDecimal allocatedAmount
) {}
