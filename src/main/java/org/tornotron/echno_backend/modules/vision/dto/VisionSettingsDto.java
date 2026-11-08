package org.tornotron.echno_backend.modules.vision.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "An organization's computer vision settings. With no settings saved, auto-inference is off.")
public record VisionSettingsDto(
        @Schema(description = "Run the models on every new inspection or observation evidence image.")
        boolean autoInfer,
        @Schema(description = "The model families to run, by key. Empty means every family with an ACTIVE version.")
        List<String> modelKeys,
        @Schema(nullable = true) Long updatedById,
        @Schema(nullable = true) LocalDateTime updatedAt
) {}
