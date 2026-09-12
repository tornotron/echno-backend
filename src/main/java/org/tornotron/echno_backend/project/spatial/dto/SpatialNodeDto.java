package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A node of a project's site structure with the path from its building down to it.")
public record SpatialNodeDto(
        UUID id,
        Long projectId,
        UUID parentId,
        SpatialLevel level,
        String code,
        String name,
        int sortOrder,
        int depth,
        Integer levelIndex,
        String elementType,
        String bimElementGuid,
        String externalRef,
        Instant archivedAt,
        @Schema(description = "Ordered ancestors from the building down to and including this node.")
        List<SpatialPathSegment> spatialPath
) {}
