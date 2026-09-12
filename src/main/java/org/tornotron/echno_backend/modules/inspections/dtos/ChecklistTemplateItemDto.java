package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "A check point within a checklist template, as returned by the API.")
public record ChecklistTemplateItemDto(
        UUID id,
        String category,
        String checkPoint,
        @Schema(description = "Reference specification for the check point. Null where none was "
                + "recorded.", nullable = true)
        String specification,
        @Schema(description = "Target value the check point is measured against. Null on "
                + "qualitative check points.", nullable = true)
        String expectedValue,
        @Schema(description = "What makes the check point pass. Null where none was recorded.",
                nullable = true)
        String acceptanceCriterion,
        @Schema(description = "Permitted band around the expected value. Null where none was "
                + "recorded.", nullable = true)
        String tolerance,
        boolean photosRequired,
        @Schema(description = "Priority of the check point. The service substitutes \"medium\" "
                + "wherever a payload or a starter checklist omits it, but the column permits "
                + "null, so a row loaded outside the application can carry none.", nullable = true)
        String priority,
        int lineOrder
) {}
