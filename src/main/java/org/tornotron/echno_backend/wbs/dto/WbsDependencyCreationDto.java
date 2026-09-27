package org.tornotron.echno_backend.wbs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.tornotron.echno_backend.wbs.enums.WbsDependencyType;

@Schema(description = "Links two activities of the same project. The link may not close a loop.")
public record WbsDependencyCreationDto(
        @Schema(description = "Id of the activity that comes first.", example = "41")
        @NotNull Long predecessorId,
        @Schema(description = "Id of the activity that depends on it.", example = "42")
        @NotNull Long successorId,
        @Schema(description = "Defaults to FS (finish-to-start).", example = "FS", nullable = true)
        WbsDependencyType type,
        @Schema(description = "Lag in days, negative for a lead. Defaults to 0.", example = "0", nullable = true)
        @Min(-365) @Max(365) Integer lagDays
) {}
