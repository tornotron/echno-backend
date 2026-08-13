package org.tornotron.echno_backend.inspection.dtos;

import org.tornotron.echno_backend.inspection.CheckItemStatus;

import java.util.List;
import java.util.UUID;

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
