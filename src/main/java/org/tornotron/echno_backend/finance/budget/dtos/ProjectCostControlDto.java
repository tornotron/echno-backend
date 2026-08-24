package org.tornotron.echno_backend.finance.budget.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Project cost-control view: the per-head allocated, committed, spent and remaining "
        + "amounts, plus a project total row.")
public record ProjectCostControlDto(
        @Schema(description = "Project the cost-control view is for.", example = "42")
        Long projectId,

        @Schema(description = "Per budget head roll-up. Includes any head with an allocation or tagged spend.")
        List<ProjectCostControlLineDto> categories,

        @Schema(description = "Project total row summing each column across the heads.")
        ProjectCostControlLineDto totals
) {}
