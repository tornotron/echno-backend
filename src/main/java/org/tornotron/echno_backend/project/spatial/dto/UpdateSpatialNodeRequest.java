package org.tornotron.echno_backend.project.spatial.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Partial update; every field is optional and an omitted field is left as it is.")
public record UpdateSpatialNodeRequest(
        @Size(max = 50) String code,
        @Size(max = 200) String name,
        Integer sortOrder,
        Integer levelIndex,
        @Size(max = 50) String elementType,
        @Size(max = 100) String bimElementGuid,
        @Size(max = 200) String externalRef
) {}
