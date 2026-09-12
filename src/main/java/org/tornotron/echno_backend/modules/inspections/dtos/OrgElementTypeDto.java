package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "An element type as the organization has it: a catalogue copy or an org-defined type.")
public record OrgElementTypeDto(
        UUID id,
        @Schema(description = "Slug unique within the organization; the value a spatial node's elementType carries. Fixed once created.", example = "column")
        String code,
        String name,
        @Schema(example = "structure") String groupCode,
        @Schema(nullable = true) String description,
        int sortOrder,
        @Schema(description = "Inactive types disappear from pickers and stay valid on the elements that carry them.")
        boolean active,
        @Schema(description = "Catalogue code the row was copied from; null for an org-defined type.", nullable = true)
        String catalogueCode
) {}
