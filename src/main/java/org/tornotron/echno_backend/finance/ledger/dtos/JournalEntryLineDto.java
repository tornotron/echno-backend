package org.tornotron.echno_backend.finance.ledger.dtos;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalEntryLineDto(
        UUID id,
        UUID accountId,
        String accountCode,
        String accountName,
        BigDecimal debit,
        BigDecimal credit,
        String narration,
        int lineOrder
) {
}
