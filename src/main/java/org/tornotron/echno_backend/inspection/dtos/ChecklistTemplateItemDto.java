package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "A check point within a checklist template, as returned by the API.")
public record ChecklistTemplateItemDto(
        UUID id,
        String category,
        String checkPoint,
        String specification,
        String expectedValue,
        String acceptanceCriterion,
        String tolerance,
        boolean photosRequired,
        String priority,
        int lineOrder
) {}
