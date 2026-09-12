package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "A node of the site structure with its children nested.")
public record SpatialTreeNodeDto(
        UUID id,
        UUID parentId,
        SpatialLevel level,
        String code,
        String name,
        int sortOrder,
        Integer levelIndex,
        String elementType,
        String bimElementGuid,
        String externalRef,
        Instant archivedAt,
        List<SpatialTreeNodeDto> children
) {}
