package org.tornotron.echno_backend.finance.ledger.dtos;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PostJournalRequest(
        @NotNull LocalDate entryDate,
        @NotBlank @Size(max = 500) String description,
        @Size(max = 100) String reference,
        @NotNull @Size(min = 2, message = "At least 2 lines required") @Valid List<LineRequest> lines
) {
    public record LineRequest(
            @NotNull UUID accountId,
            @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal debit,
            @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal credit,
            @Size(max = 500) String narration
    ) {}
}
