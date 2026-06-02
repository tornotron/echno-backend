package org.tornotron.echno_backend.finance.ledger.dtos;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReverseJournalRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
