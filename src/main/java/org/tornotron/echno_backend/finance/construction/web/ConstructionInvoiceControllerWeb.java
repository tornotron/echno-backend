package org.tornotron.echno_backend.finance.construction.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceStatus;
import org.tornotron.echno_backend.finance.construction.ConstructionInvoiceType;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionInvoiceDto;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionInvoiceRequest;
import org.tornotron.echno_backend.finance.construction.service.ConstructionInvoiceService;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/construction-invoices/web")
@RequiredArgsConstructor
public class ConstructionInvoiceControllerWeb {

    private final ConstructionInvoiceService service;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ResponseEntity<ConstructionInvoiceDto> create(@Valid @RequestBody CreateConstructionInvoiceRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ConstructionInvoiceDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public Page<ConstructionInvoiceDto> list(@RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) Long vendorId,
                                             @RequestParam(required = false) ConstructionInvoiceStatus status,
                                             @RequestParam(required = false) ConstructionInvoiceType type,
                                             Pageable pageable) {
        return service.findAll(projectId, vendorId, status, type, pageable);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ConstructionInvoiceDto update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateConstructionInvoiceRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ConstructionInvoiceDto submit(@PathVariable UUID id) {
        return service.submit(id);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ConstructionInvoiceDto approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ConstructionInvoiceDto cancel(@PathVariable UUID id, @RequestParam String reason) {
        return service.cancel(id, reason);
    }

    @PostMapping("/{id}/record-payment")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ConstructionInvoiceDto recordPayment(@PathVariable UUID id, @RequestParam BigDecimal amount) {
        return service.recordPayment(id, amount);
    }
}
