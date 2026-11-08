package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "What \"confirm all\" confirmed, and the readers it queued.")
public record ConfirmAllResultDto(int documentsConfirmed, int segmentsConfirmed, int jobsQueued,
                                  @Schema(description = "Documents still to confirm, beyond this call's page; call again while above zero")
                                  int remaining) {
}
