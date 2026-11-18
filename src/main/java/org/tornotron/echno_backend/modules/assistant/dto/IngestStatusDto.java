package org.tornotron.echno_backend.modules.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Ingestion status and chunk statistics for an attachment.")
public record IngestStatusDto(
        @Schema(description = "The attachment ID", example = "101")
        Long attachmentId,

        @Schema(description = "Ingestion status: PENDING, COMPLETED, or FAILED", example = "COMPLETED")
        String status,

        @Schema(description = "Total chunks stored in the vector index for this document", example = "14")
        int chunkCount,

        @Schema(description = "Additional info or error message", example = "Document ingested successfully.")
        String message
) {
}
