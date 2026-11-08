package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Turns reading tender text with the outside AI service on or off for the organization.")
public record LlmReadingRequest(@NotNull Boolean enabled) {
}
