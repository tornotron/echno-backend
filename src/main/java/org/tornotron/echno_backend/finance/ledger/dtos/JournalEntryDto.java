package org.tornotron.echno_backend.finance.ledger.dtos;

import org.tornotron.echno_backend.finance.ledger.JournalStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record JournalEntryDto(
        UUID id,
        String entryNumber,
        LocalDate entryDate,
        String description,
        String reference,
        JournalStatus status,
        UUID reversedByEntryId,
        UUID reversesEntryId,
        String sourceType,
        UUID sourceId,
        List<JournalEntryLineDto> lines,
        Instant createdAt,
        String createdBy
) {
}
