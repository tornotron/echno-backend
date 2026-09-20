package org.tornotron.echno_backend.documentReversal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why an approver refused a reversal. Required, so the refusal reads as a decision rather than
 * as the absence of one; 500 characters is the width of the {@code decision_note} column.
 */
@Schema(description = "Payload to reject a reversal request, carrying the reason it was refused.")
public record DocumentReversalRejectionRequest(
        @Schema(description = "Why the reversal is being refused. Required.",
                example = "The stock has been issued to the slab pour; raise an adjustment instead")
        @NotBlank @Size(max = 500) String reason
) {}
