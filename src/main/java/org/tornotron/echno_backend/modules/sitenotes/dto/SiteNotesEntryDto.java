package org.tornotron.echno_backend.modules.sitenotes.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "One Site Notes entry of the current tenant.")
public record SiteNotesEntryDto(
        UUID id,
        String title,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
