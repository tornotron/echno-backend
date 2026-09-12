package org.tornotron.echno_backend.modules.bim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;

@Schema(description = "One IfcProduct of a model, stable across versions by its IFC GlobalId.")
public record BimElementDto(
        UUID id,
        UUID modelId,
        String globalId,
        String ifcType,
        String name,
        String storeyGlobalId,
        String spaceGlobalId,
        Map<String, Object> bbox,
        Map<String, Object> properties,
        @Schema(description = "The ELEMENT-level spatial node this product became, if any.") UUID spatialNodeId,
        UUID firstSeenVersionId,
        UUID lastSeenVersionId,
        boolean retired,
        UUID mergedIntoId
) {}
