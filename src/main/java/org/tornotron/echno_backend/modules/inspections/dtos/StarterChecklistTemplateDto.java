package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "A product-supplied starter checklist for one trade, which an organization "
        + "adopts to create its own editable template.")
public record StarterChecklistTemplateDto(
        UUID id,
        @Schema(description = "Catalogue code of the trade the starter is for.", example = "reinforcement")
        String trade,
        String name,
        @Schema(description = "What the starter checklist covers. Null where the shipped seed row "
                + "carried none.", nullable = true)
        String description,
        List<ChecklistTemplateItemDto> items
) {}
