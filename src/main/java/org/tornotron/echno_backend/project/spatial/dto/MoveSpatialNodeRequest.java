package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "Re-parents a node. The target must be an active node of the level above, in the same project.")
public record MoveSpatialNodeRequest(@NotNull UUID parentId) {}
