package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One element type of the product-shipped catalogue. Read only.")
public record ElementTypeCatalogueDto(
        @Schema(description = "Stable slug.", example = "column") String code,
        String name,
        @Schema(description = "Group: structure, openings, finishes, services or fire.", example = "structure") String groupCode,
        @Schema(nullable = true) String description,
        int sortOrder,
        boolean active
) {}
