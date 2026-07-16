package org.tornotron.echno_backend.finance.invoice.web;


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
public class InvoiceControllerWeb {

    private final InvoiceService service;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ResponseEntity<InvoiceDto> createDraft(@Valid @RequestBody CreateInvoiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createDraft(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public InvoiceDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public InvoiceDto issue(@PathVariable UUID id) {
        return service.issue(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public InvoiceDto cancel(@PathVariable UUID id, @RequestParam String reason) {
        return service.cancel(id, reason);
    }
}