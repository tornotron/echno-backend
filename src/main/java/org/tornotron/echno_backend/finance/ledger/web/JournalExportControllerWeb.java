package org.tornotron.echno_backend.finance.ledger.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.finance.ledger.service.JournalCsvService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/finance/journal/web")
@RequiredArgsConstructor
@Tag(
        name = "Journal Export",
        description = "CSV export of the ledger's journal entries for external accounting systems, one "
                + "row per journal line. Tenant scoped and limited to system administrators and project "
                + "managers."
)
public class JournalExportControllerWeb {

    private final JournalCsvService csvService;

    @GetMapping("/export")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Export journal entries as CSV",
            description = "Streams the current tenant's journal entries as a CSV attachment with the "
                    + "columns entryDate, entryNumber, accountCode, accountName, debit, credit, narration, "
                    + "referenceType and referenceId, one row per line. The optional from and to query "
                    + "parameters narrow the export by entry date (inclusive)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CSV generated and returned as an attachment"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] csv = csvService.exportCsv(from, to).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"journal-entries.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
