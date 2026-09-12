package org.tornotron.echno_backend.modules.bim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "A BIM model of a project with its versions, newest first.")
public record BimModelDto(
        UUID id,
        Long projectId,
        String name,
        String description,
        @Schema(description = "The latest READY version, which the viewer opens by default.") UUID currentVersionId,
        List<BimModelVersionDto> versions,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
