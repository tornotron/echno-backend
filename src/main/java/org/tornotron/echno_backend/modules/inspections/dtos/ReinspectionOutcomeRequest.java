package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.modules.inspections.ReinspectionOutcome;

@Schema(description = "What the reinspection found.")
public record ReinspectionOutcomeRequest(
        @Schema(description = "passed when the work now conforms, failed otherwise.", example = "passed")
        @NotNull ReinspectionOutcome outcome,
        @Schema(description = "What was seen on the re-check.", example = "Cover re-measured at 42 mm at all three points.")
        @Size(max = 2000) String remarks
) {}
