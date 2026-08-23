package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "Payload to post a journal entry. The lines must balance, with total debits equal to "
        + "total credits.")
public record PostJournalRequest(
        @Schema(description = "Accounting date of the entry.", example = "2026-08-01")
        @NotNull LocalDate entryDate,

        @Schema(description = "Description of the entry.", example = "Vendor bill CINV-2026-0042 posted")
        @NotBlank @Size(max = 500) String description,

        @Schema(description = "Free-text reference, such as a document number.", example = "CINV-2026-0042")
        @Size(max = 100) String reference,

        @Schema(description = "Debit and credit lines. At least two lines are required and they must balance.")
        @NotNull @Size(min = 2, message = "At least 2 lines required") @Valid List<LineRequest> lines
) {
    @Schema(description = "One line of a journal entry to post. A line carries either a debit or a credit, "
            + "with the other amount set to zero.")
    public record LineRequest(
            @Schema(description = "Id of the account to post to. Must be an active postable account.",
                    example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
            @NotNull UUID accountId,

            @Schema(description = "Debit amount for this line. Use zero when the line is a credit.",
                    example = "76275.00")
            @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal debit,

            @Schema(description = "Credit amount for this line. Use zero when the line is a debit.",
                    example = "0.00")
            @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal credit,

            @Schema(description = "Optional narration for the line.", example = "Payment received from customer")
            @Size(max = 500) String narration
    ) {}
}
