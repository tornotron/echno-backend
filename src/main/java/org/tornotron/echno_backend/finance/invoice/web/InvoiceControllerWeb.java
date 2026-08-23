package org.tornotron.echno_backend.finance.invoice.web;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.invoice.dtos.CreateInvoiceRequest;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.service.InvoiceService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/invoices/web")
@RequiredArgsConstructor
@Tag(
        name = "Customer Invoices",
        description = "Accounts-receivable invoices raised to customers. An invoice is created as a draft, "
                + "issued to post its receivable journal entry, and can be cancelled with a reversing entry. "
                + "All endpoints are tenant scoped and limited to system administrators and project managers."
)
public class InvoiceControllerWeb {

    private final InvoiceService service;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Create a draft invoice",
            description = "Creates a draft customer invoice from the supplied header and line items. "
                    + "Line subtotals, tax and totals are computed server side. A draft posts no ledger entry "
                    + "until it is issued."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Invoice created and returned in draft status"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<InvoiceDto> createDraft(@Valid @RequestBody CreateInvoiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDraft(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get an invoice by id",
            description = "Returns a single customer invoice, including its computed totals and line items."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant")
    })
    public InvoiceDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Issue a draft invoice",
            description = "Issues a draft invoice, transitioning it to issued status and posting the "
                    + "receivable journal entry to the ledger. Once issued the invoice can be paid."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice issued and posted"),
            @ApiResponse(responseCode = "400", description = "Invoice is not in a state that can be issued"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant")
    })
    public InvoiceDto issue(@PathVariable UUID id) {
        return service.issue(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Cancel an invoice",
            description = "Cancels an invoice and records the supplied reason. If the invoice was already "
                    + "issued, a reversing journal entry is posted to back out the receivable."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice cancelled"),
            @ApiResponse(responseCode = "400", description = "Invoice is already paid or otherwise cannot be cancelled"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant")
    })
    public InvoiceDto cancel(@PathVariable UUID id, @RequestParam String reason) {
        return service.cancel(id, reason);
    }
}