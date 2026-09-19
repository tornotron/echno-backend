package org.tornotron.echno_backend.modules.__MODULE_PKG__.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "One __MODULE_NAME__ entry of the current tenant.")
public record __MODULE_PASCAL__EntryDto(
        UUID id,
        String title,
        String notes,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
