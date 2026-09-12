package org.tornotron.echno_backend.modules.inspections.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "An inspection trade as the organization has it: a catalogue copy or an org-defined trade.")
public record OrgTradeDto(
        UUID id,
        @Schema(description = "Slug unique within the organization. Fixed once created.", example = "reinforcement")
        String code,
        String name,
        @Schema(description = "Group heading the trade is listed under.", example = "structural")
        String groupCode,
        @Schema(nullable = true) String description,
        int sortOrder,
        @Schema(description = "Inactive trades disappear from pickers and stay valid on the rows that reference them.")
        boolean active,
        @Schema(description = "Catalogue code the row was copied from; null for an org-defined trade.", nullable = true)
        String catalogueCode
) {}
