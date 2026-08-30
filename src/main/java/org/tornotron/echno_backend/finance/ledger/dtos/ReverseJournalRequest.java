package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.finance.ledger.JournalLimits;

@Schema(description = "Payload to reverse a posted journal entry.")
public record ReverseJournalRequest(
        @Schema(description = "Reason the entry is being reversed, recorded on the reversing entry. "
                + "The reversing entry's description is the reason with a fixed prefix naming the "
                + "entry being reversed, so the bound is the description column less that prefix.",
                example = "Vendor bill cancelled after posting")
        @NotBlank @Size(max = JournalLimits.REVERSAL_REASON_MAX_LENGTH) String reason
) {
}
