package org.tornotron.echno_backend.modules.bim.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;
import org.tornotron.echno_backend.modules.bim.BimImportJobStatus;

@Schema(description = "A worker job for one model version.")
public record BimImportJobDto(
        UUID id,
        UUID modelId,
        UUID versionId,
        BimImportJobStatus status,
        int attempt,
        int maxAttempts,
        String workerId,
        String workerVersion,
        Integer elementCount,
        Integer storeyCount,
        String error,
        LocalDateTime queuedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime ingestedAt
) {}
