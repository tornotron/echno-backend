package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.inspection.CheckItemStatus;

import java.util.List;
import java.util.UUID;

@Schema(description = "A checklist item within an inspection, as returned by the API.")
public record InspectionCheckItemDto(
        UUID id,
        String category,
        String checkPoint,
        String specification,
        CheckItemStatus status,
        String remarks,
        boolean photosRequired,
        List<String> photos,
        String measurement,
        String expectedValue,
        String priority
) {}
