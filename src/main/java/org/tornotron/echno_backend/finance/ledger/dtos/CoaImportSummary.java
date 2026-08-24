package org.tornotron.echno_backend.finance.ledger.dtos;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Outcome of a chart-of-accounts CSV import: how many accounts were created and "
        + "updated, and any per-row errors. Accounts absent from the file are never deleted.")
public record CoaImportSummary(

        @Schema(description = "Number of accounts created.", example = "4")
        int created,

        @Schema(description = "Number of existing accounts updated.", example = "12")
        int updated,

        @Schema(description = "Per-row error messages for rows that were skipped.")
        List<String> errors
) {}
