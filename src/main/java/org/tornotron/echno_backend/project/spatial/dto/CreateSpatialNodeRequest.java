package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.project.spatial.SpatialLevel;

import java.util.UUID;

@Schema(description = "Adds a node under a parent of the level above it; a building has no parent.")
public record CreateSpatialNodeRequest(
        @Schema(description = "Parent node id. Null for a building.") UUID parentId,
        @NotNull SpatialLevel level,
        @Schema(description = "Short label, unique among siblings.", example = "L03")
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 200) String name,
        Integer sortOrder,
        @Schema(description = "Floors only. Negative for basements, 0 for ground.") Integer levelIndex,
        @Schema(description = "Elements only. Free slug.", example = "column") @Size(max = 50) String elementType,
        @Schema(description = "IFC GlobalId, unique within the project.") @Size(max = 100) String bimElementGuid,
        @Schema(description = "Drawing or grid reference.") @Size(max = 200) String externalRef
) {}
