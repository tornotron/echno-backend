package org.tornotron.echno_backend.modules.bim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.tornotron.echno_backend.modules.bim.BimVersionStatus;

@Schema(description = "One uploaded IFC of a model and how far its import got.")
public record BimModelVersionDto(
        UUID id,
        UUID modelId,
        int versionNumber,
        BimVersionStatus status,
        String sourceFilename,
        Long sourceSizeBytes,
        String ifcSchema,
        Integer elementCount,
        Integer storeyCount,
        @Schema(description = "model-meta.json as the worker wrote it: units, site placement, true north, storeys.")
        Map<String, Object> meta,
        boolean hierarchyProposed,
        LocalDateTime hierarchyConfirmedAt,
        String error,
        LocalDateTime importedAt,
        LocalDateTime createdAt
) {}
