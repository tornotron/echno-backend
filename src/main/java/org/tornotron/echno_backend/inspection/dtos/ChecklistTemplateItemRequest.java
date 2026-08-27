package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Check-point payload shared by the create and update template requests. The line
 * order is the position in the submitted list and is not accepted from the client.
 */
@Schema(description = "A single check point within a checklist template.")
public record ChecklistTemplateItemRequest(
        @Schema(description = "Grouping the check point belongs to.", example = "Reinforcement")
        @NotBlank @Size(max = 200) String category,
        @Schema(description = "What is being checked.", example = "Main bar spacing matches the bar bending schedule")
        @NotBlank @Size(max = 500) String checkPoint,
        @Schema(description = "Reference specification or code clause for the check point.", example = "IS 456:2000 cl. 26.3")
        @Size(max = 1000) String specification,
        @Schema(description = "Value expected per specification.", example = "150 mm")
        @Size(max = 200) String expectedValue,
        @Schema(description = "What makes this check point pass.", example = "Spacing measured at three locations per bay")
        @Size(max = 1000) String acceptanceCriterion,
        @Schema(description = "Permitted band around the expected value.", example = "+/- 10 mm")
        @Size(max = 100) String tolerance,
        @Schema(description = "Whether supporting photos are required for this check point.", example = "true")
        boolean photosRequired,
        @Schema(description = "Priority of this check point. Defaults to medium when omitted.", example = "high")
        @Size(max = 20) String priority
) {}
