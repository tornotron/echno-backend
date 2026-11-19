package org.tornotron.echno_backend.modules.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request to ingest an uploaded attachment into the assistant vector index.")
public record IngestRequest(
        @NotNull(message = "attachmentId is required")
        @Schema(description = "The ID of the attachment to ingest", example = "101")
        Long attachmentId,

        @Schema(nullable = true, description = "The project ID this document belongs to, or null if organization-wide", example = "42")
        Long projectId
) {
}
