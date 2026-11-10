package org.tornotron.echno_backend.modules.documentingestion.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.modules.documentingestion.domain.MatrixStatus;

@Schema(description = "A person's status for an equipment or personnel requirement, which is text and is not compared "
        + "automatically. A null status clears it. Who set it and when is recorded.")
public record RequirementStatusRequest(
        @Schema(nullable = true) MatrixStatus status,
        @Schema(nullable = true, example = "Only four of the five named posts are on our staff.") @Size(max = 1000)
        String note) {
}
