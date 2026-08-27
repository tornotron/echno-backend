package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.inspection.InspectionTrade;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "An organization's checklist template for one trade, with its check points.")
public record ChecklistTemplateDto(
        UUID id,
        InspectionTrade trade,
        String name,
        String description,
        boolean active,
        int version,
        List<ChecklistTemplateItemDto> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
