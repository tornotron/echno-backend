package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;

import java.util.UUID;

@Schema(description = "One ancestor on the way from the building down to a node.")
public record SpatialPathSegment(UUID id, SpatialLevel level, String code, String name) {}
