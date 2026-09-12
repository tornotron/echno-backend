package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One trade of the product-shipped catalogue. Read only; organizations work "
        + "against their own copy.")
public record TradeCatalogueDto(
        @Schema(description = "Stable slug, the same string the trade has always carried on the wire.", example = "reinforcement")
        String code,
        String name,
        @Schema(description = "Group the trade sits under: structural, masonry, finishes, openings, mep, fire or general.", example = "structural")
        String groupCode,
        @Schema(nullable = true) String description,
        int sortOrder,
        boolean active
) {}
