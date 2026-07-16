package org.tornotron.echno_backend.finance.ledger.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.ledger.dtos.CreateCustomerRequest;
import org.tornotron.echno_backend.finance.ledger.dtos.CustomerDto;
import org.tornotron.echno_backend.finance.ledger.dtos.UpdateCustomerRequest;
import org.tornotron.echno_backend.finance.ledger.service.CustomerService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/customers/web")
@RequiredArgsConstructor
public class CustomerControllerWeb {

    private final CustomerService service;

    @GetMapping("/all")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public List<CustomerDto> list(@RequestParam(required = false) String name, Pageable pageable) {
        return service.search(name, pageable).getContent();
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public CustomerDto get(@RequestParam UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ResponseEntity<CustomerDto> create(@Valid @RequestBody CreateCustomerRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public CustomerDto update(@PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }

}
