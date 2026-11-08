package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Confirms an upload. The key must be the one the presign returned for this document.")
public record RegisterDocumentRequest(@NotBlank @Size(max = 500) String key) {
}
