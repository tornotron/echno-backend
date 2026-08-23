package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.finance.ledger.JournalStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Schema(description = "A posted journal entry with its balanced debit and credit lines.")
public record JournalEntryDto(
        @Schema(description = "Unique journal entry id.", example = "3f2504e0-4f89-41d3-9a0c-0305e82c3301")
        UUID id,

        @Schema(description = "Human-readable entry number assigned by the system.", example = "JE-2026-0042")
        String entryNumber,

        @Schema(description = "Accounting date of the entry.", example = "2026-08-01")
        LocalDate entryDate,

        @Schema(description = "Description of the entry.", example = "Vendor bill CINV-2026-0042 posted")
        String description,

        @Schema(description = "Free-text reference, such as a document number.", example = "CINV-2026-0042")
        String reference,

        @Schema(description = "Status of the entry.", example = "POSTED")
        JournalStatus status,

        @Schema(description = "Id of the entry that reversed this entry, present once it has been reversed.",
                example = "9b2f1c44-7a1e-4e2b-9f0a-2c8d5e6f7a10")
        UUID reversedByEntryId,

        @Schema(description = "Id of the entry this entry reverses, present when it is itself a reversal.",
                example = "1c2f3d44-5a6e-4b7b-8f9a-0c1d2e3f4a5b")
        UUID reversesEntryId,

        @Schema(description = "Type of the source document that generated the entry, if any.",
                example = "CONSTRUCTION_INVOICE")
        String sourceType,

        @Schema(description = "Id of the source document that generated the entry, if any.",
                example = "7a1e9b2f-1c44-4e2b-9f0a-2c8d5e6f7a10")
        UUID sourceId,

        @Schema(description = "Debit and credit lines that make up the entry.")
        List<JournalEntryLineDto> lines,

        @Schema(description = "Timestamp the entry was created.", example = "2026-08-01T09:15:00Z")
        Instant createdAt,

        @Schema(description = "User that created the entry.", example = "abin")
        String createdBy
) {
}
