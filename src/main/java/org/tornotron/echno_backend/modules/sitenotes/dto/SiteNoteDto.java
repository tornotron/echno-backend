package org.tornotron.echno_backend.modules.sitenotes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "One site note of the current tenant.")
public record SiteNoteDto(
        UUID id,
        Long projectId,
        LocalDate noteDate,
        Long authorEmployeeId,
        String note,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
