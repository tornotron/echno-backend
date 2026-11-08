package org.tornotron.echno_backend.modules.vision.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "Replaces the organization's computer vision settings.")
public record UpdateVisionSettingsRequest(
        @NotNull Boolean autoInfer,
        @Schema(description = "Registered model family keys, up to 20. Empty means every family with an ACTIVE version.")
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 50) String> modelKeys
) {}
