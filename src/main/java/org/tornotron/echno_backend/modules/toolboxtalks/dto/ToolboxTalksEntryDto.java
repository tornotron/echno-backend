package org.tornotron.echno_backend.modules.toolboxtalks.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "One Toolbox Talks entry of the current tenant.")
public record ToolboxTalksEntryDto(
        UUID id,
        String title,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
