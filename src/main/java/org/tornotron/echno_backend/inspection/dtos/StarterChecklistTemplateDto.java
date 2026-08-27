package org.tornotron.echno_backend.inspection.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.inspection.InspectionTrade;

import java.util.List;
import java.util.UUID;

@Schema(description = "A product-supplied starter checklist for one trade, which an organization "
        + "adopts to create its own editable template.")
public record StarterChecklistTemplateDto(
        UUID id,
        InspectionTrade trade,
        String name,
        String description,
        List<ChecklistTemplateItemDto> items
) {}
