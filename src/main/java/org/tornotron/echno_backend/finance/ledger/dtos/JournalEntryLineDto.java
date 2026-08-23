package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "One line of a journal entry, debiting or crediting a single account.")
public record JournalEntryLineDto(
        @Schema(description = "Unique line id.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID id,

        @Schema(description = "Id of the account posted to.", example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
        UUID accountId,

        @Schema(description = "Code of the account posted to.", example = "1100")
        String accountCode,

        @Schema(description = "Name of the account posted to.", example = "Cash and Cash Equivalents")
        String accountName,

        @Schema(description = "Debit amount on this line. Zero when the line is a credit.", example = "76275.00")
        BigDecimal debit,

        @Schema(description = "Credit amount on this line. Zero when the line is a debit.", example = "0.00")
        BigDecimal credit,

        @Schema(description = "Optional narration for the line.", example = "Payment received from customer")
        String narration,

        @Schema(description = "Order of the line within the entry, starting at zero.", example = "0")
        int lineOrder
) {
}
