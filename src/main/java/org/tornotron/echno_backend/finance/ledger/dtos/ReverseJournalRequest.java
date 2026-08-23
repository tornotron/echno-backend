package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Payload to reverse a posted journal entry.")
public record ReverseJournalRequest(
        @Schema(description = "Reason the entry is being reversed, recorded on the reversing entry.",
                example = "Vendor bill cancelled after posting")
        @NotBlank @Size(max = 500) String reason
) {
}
