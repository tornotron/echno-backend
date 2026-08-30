package org.tornotron.echno_backend.finance.invoice.web;


import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.pagination.PageQuery;
import org.tornotron.echno_backend.finance.ledger.JournalLimits;
import org.tornotron.echno_backend.finance.invoice.InvoiceStatus;
import org.tornotron.echno_backend.finance.invoice.dtos.CreateInvoiceRequest;
import org.tornotron.echno_backend.finance.invoice.dtos.InvoiceDto;
import org.tornotron.echno_backend.finance.invoice.service.InvoiceService;

import java.util.UUID;

@RestController
@Validated
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

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "List invoices",
            description = "Returns a page of customer invoices in the current tenant, newest invoice date "
                    + "first. The optional customer, status and openOnly parameters narrow the result and "
                    + "combine with AND; omitting a parameter leaves that dimension unfiltered. Use pageNo "
                    + "and pageSize to page through the results."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of matching invoices"),
            @ApiResponse(responseCode = "400", description = "pageNo or pageSize is outside its permitted range"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public Page<InvoiceDto> list(
            @Parameter(description = "Restrict to invoices billed to this customer.")
            @RequestParam(required = false) UUID customerId,
            @Parameter(description = "Restrict to invoices in this lifecycle status.")
            @RequestParam(required = false) InvoiceStatus status,
            @Parameter(description = "Restrict to invoices still owed, that is ISSUED or PARTIALLY_PAID.")
            @RequestParam(required = false, defaultValue = "false") boolean openOnly,
            @Valid @ParameterObject PageQuery pageQuery) {
        return service.findAll(customerId, status, openOnly,
                pageQuery.getPageNo(), pageQuery.getPageSize());
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
                    + "issued, a reversing journal entry is posted to back out the receivable. "
                    + "The reason is recorded on the reversing entry's description, which also carries a "
                    + "prefix naming the entry being reversed, so it must be present and at most 455 "
                    + "characters."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice cancelled"),
            @ApiResponse(responseCode = "400",
                    description = "The reason is blank or longer than 455 characters, or the invoice "
                            + "is already paid or otherwise cannot be cancelled"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant")
    })
    public InvoiceDto cancel(
            @PathVariable UUID id,
            @RequestParam @NotBlank @Size(max = JournalLimits.REVERSAL_REASON_MAX_LENGTH) String reason) {
        return service.cancel(id, reason);
    }
}