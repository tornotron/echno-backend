package org.tornotron.echno_backend.finance.construction.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.construction.ConstructionPayeeType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentType;
import org.tornotron.echno_backend.finance.construction.ConstructionPaymentVoucherStatus;
import org.tornotron.echno_backend.finance.construction.dtos.ConstructionPaymentDto;
import org.tornotron.echno_backend.finance.construction.dtos.CreateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.dtos.UpdateConstructionPaymentRequest;
import org.tornotron.echno_backend.finance.construction.service.ConstructionPaymentService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/construction-payments/web")
@RequiredArgsConstructor
public class ConstructionPaymentControllerWeb {

    private final ConstructionPaymentService service;

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ResponseEntity<ConstructionPaymentDto> create(@Valid @RequestBody CreateConstructionPaymentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ConstructionPaymentDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public Page<ConstructionPaymentDto> list(@RequestParam(required = false) Long projectId,
                                             @RequestParam(required = false) Long vendorId,
                                             @RequestParam(required = false) ConstructionPaymentVoucherStatus status,
                                             @RequestParam(required = false) ConstructionPaymentType type,
                                             @RequestParam(required = false) ConstructionPayeeType payeeType,
                                             Pageable pageable) {
        return service.findAll(projectId, vendorId, status, type, payeeType, pageable);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ConstructionPaymentDto update(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateConstructionPaymentRequest req) {
        return service.update(id, req);
    }
}
