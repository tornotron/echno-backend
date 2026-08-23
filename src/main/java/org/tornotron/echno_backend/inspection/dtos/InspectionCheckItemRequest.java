package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.inspection.CheckItemStatus;

import java.util.List;

/**
 * Check-point payload shared by the create and update requests. The inspection's
 * passed, failed and total counts are derived server-side from the supplied
 * check items; the client does not set them.
 */
@Schema(description = "A single checklist item within an inspection.")
public record InspectionCheckItemRequest(
        @Schema(description = "Grouping the check point belongs to.", example = "Reinforcement")
        @NotBlank @Size(max = 200) String category,
        @Schema(description = "What is being checked.", example = "Rebar spacing matches drawing")
        @NotBlank @Size(max = 500) String checkPoint,
        @Schema(description = "Reference specification or tolerance for the check point.", example = "150mm c/c +/- 10mm")
        @Size(max = 1000) String specification,
        @Schema(description = "Result of the check.", example = "PASS")
        @NotNull CheckItemStatus status,
        @Schema(description = "Free-text remarks recorded against the check point.", example = "Minor deviation at grid line C4, within tolerance")
        @Size(max = 1000) String remarks,
        @Schema(description = "Whether supporting photos are required for this check point.", example = "true")
        boolean photosRequired,
        @Schema(description = "URLs or references of photos attached to this check point.")
        List<@Size(max = 500) String> photos,
        @Schema(description = "Measured value recorded on site.", example = "148mm")
        @Size(max = 200) String measurement,
        @Schema(description = "Value expected per specification.", example = "150mm")
        @Size(max = 200) String expectedValue,
        @Schema(description = "Priority of this check point.", example = "high")
        @Size(max = 20) String priority
) {}
