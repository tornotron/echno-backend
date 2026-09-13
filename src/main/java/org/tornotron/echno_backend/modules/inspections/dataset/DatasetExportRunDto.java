package org.tornotron.echno_backend.modules.inspections.dataset;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/** One export run as the admin screen reads it. */
@Schema(description = "A consented evidence export run for the current organization")
public record DatasetExportRunDto(
        UUID id,
        Long organizationId,
        @Schema(description = "The export/<runKey>/ segment in the dataset bucket") String runKey,
        DatasetExportRunStatus status,
        String triggeredBy,
        int exportedCount,
        int skippedCount,
        int failedCount,
        @Schema(description = "Key of manifest.jsonl in the dataset bucket, once written") String manifestKey,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    static DatasetExportRunDto from(DatasetExportRun run) {
        return new DatasetExportRunDto(run.getId(), run.getOrganization().getId(), run.getRunKey(),
                run.getStatus(), run.getTriggeredBy(), run.getExportedCount(), run.getSkippedCount(),
                run.getFailedCount(), run.getManifestKey(), run.getErrorMessage(), run.getStartedAt(),
                run.getFinishedAt());
    }
}
