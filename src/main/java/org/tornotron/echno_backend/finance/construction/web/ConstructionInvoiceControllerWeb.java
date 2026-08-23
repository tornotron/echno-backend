package org.tornotron.echno_backend.finance.construction.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.pdf.ConstructionInvoicePdfService;
import org.tornotron.echno_backend.finance.construction.service.ConstructionInvoiceService;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/construction-invoices/web")
@RequiredArgsConstructor
@Tag(
        name = "Construction Invoices",
        description = "Vendor and progress invoices raised against construction projects. "
                + "Invoices move through a draft, submitted, approved and paid lifecycle, and posting "
                + "an approved invoice creates the matching ledger journal entry. All endpoints are "
                + "tenant scoped and limited to system administrators and project managers."
)
public class ConstructionInvoiceControllerWeb {

    private final ConstructionInvoiceService service;
    private final ConstructionInvoicePdfService pdfService;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Create a construction invoice",
            description = "Creates a draft invoice from the supplied header and line items. "
                    + "Line-level subtotal, tax, discount and total amounts are computed server side "
                    + "from the quantity, unit price and percentage rates on each line."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Invoice created and returned in draft status"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<ConstructionInvoiceDto> create(@Valid @RequestBody CreateConstructionInvoiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get a construction invoice by id",
            description = "Returns a single invoice, including its computed totals and line items."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant")
    })
    public ConstructionInvoiceDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping("/{id}/pdf")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Download a construction invoice as a PDF",
            description = "Renders the invoice, with its header, billing details and line items, into a "
                    + "professionally formatted PDF and returns it as a downloadable attachment named "
                    + "after the invoice number."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF generated and returned as an attachment"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant"),
            @ApiResponse(responseCode = "500", description = "PDF rendering failed")
    })
    public ResponseEntity<byte[]> downloadPdf(@PathVariable UUID id) throws IOException {
        ConstructionInvoiceDto invoice = service.findById(id);
        byte[] pdf = pdfService.render(invoice);
        String number = invoice.invoiceNumber();
        String filename = (number == null || number.isBlank()) ? "invoice" : number;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "List construction invoices",
            description = "Returns a paged list of invoices in the current tenant. The optional project, "
                    + "vendor, status and type parameters narrow the result; omitting a parameter leaves "
                    + "that dimension unfiltered."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of matching invoices"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public Page<ConstructionInvoiceDto> list(@RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) Long vendorId,
                                             @RequestParam(required = false) ConstructionInvoiceStatus status,
                                             @RequestParam(required = false) ConstructionInvoiceType type,
                                             Pageable pageable) {
        return service.findAll(projectId, vendorId, status, type, pageable);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Update a construction invoice",
            description = "Replaces the editable header and line items of a draft invoice. "
                    + "Invoices that have already been submitted or approved cannot be edited."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or the invoice is not in an editable state"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant")
    })
    public ConstructionInvoiceDto update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateConstructionInvoiceRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Submit a draft invoice for approval",
            description = "Transitions a draft invoice to submitted status so it can be reviewed and approved."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice submitted"),
            @ApiResponse(responseCode = "400", description = "Invoice is not in a state that can be submitted"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant")
    })
    public ConstructionInvoiceDto submit(@PathVariable UUID id) {
        return service.submit(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Approve a submitted invoice",
            description = "Approves a submitted invoice and posts the matching journal entry to the ledger. "
                    + "Once approved the invoice is eligible for payment."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice approved and posted"),
            @ApiResponse(responseCode = "400", description = "Invoice is not in a state that can be approved"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant")
    })
    public ConstructionInvoiceDto approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Cancel an invoice",
            description = "Cancels an invoice and records the supplied reason. If the invoice was already "
                    + "posted, the ledger entry is reversed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Invoice cancelled"),
            @ApiResponse(responseCode = "400", description = "Invoice is already paid or otherwise cannot be cancelled"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant")
    })
    public ConstructionInvoiceDto cancel(@PathVariable UUID id, @RequestParam String reason) {
        return service.cancel(id, reason);
    }

    @PostMapping("/{id}/record-payment")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Record a payment against an invoice",
            description = "Applies a payment of the given amount to an approved invoice, reducing its "
                    + "outstanding balance and advancing the payment status once fully settled."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Payment recorded and balance updated"),
            @ApiResponse(responseCode = "400", description = "Amount is invalid or exceeds the outstanding balance"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No invoice with the given id in the current tenant")
    })
    public ConstructionInvoiceDto recordPayment(@PathVariable UUID id, @RequestParam BigDecimal amount) {
        return service.recordPayment(id, amount);
    }
}
