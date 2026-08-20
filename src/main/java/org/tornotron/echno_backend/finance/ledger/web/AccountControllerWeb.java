package org.tornotron.echno_backend.finance.ledger.web;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.ledger.dtos.AccountDto;
import org.tornotron.echno_backend.finance.ledger.dtos.AccountTreeDto;
import org.tornotron.echno_backend.finance.ledger.dtos.CreateAccountRequest;
import org.tornotron.echno_backend.finance.ledger.service.AccountService;
import org.tornotron.echno_backend.finance.ledger.service.ChartOfAccountsSeeder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/accounts/web")
@RequiredArgsConstructor
public class AccountControllerWeb {

    private final AccountService service;
    private final ChartOfAccountsSeeder chartOfAccountsSeeder;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public List<AccountDto> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return activeOnly ? service.findAllActiveAccounts() : service.findAllAccounts();
    }

    @GetMapping("/tree")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public List<AccountTreeDto> tree() {
        return service.findAccountTree();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public AccountDto get(@PathVariable UUID id) {
        return service.findAccountById(id);
    }

    @GetMapping("/by-code/{code}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public AccountDto getByCode(@PathVariable String code) {
        return service.findByAccountByCode(code);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public ResponseEntity<AccountDto> create(@Valid @RequestBody CreateAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    public AccountDto deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }

    /**
     * Seeds the default chart of accounts for the current tenant. Idempotent: an org
     * that already has a chart is left untouched. Lets an org created before the seed
     * was wired into organization creation be back-filled. Restricted to system-admin.
     */
    @PostMapping("/seed-defaults")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public Map<String, Integer> seedDefaults() {
        return Map.of("created", chartOfAccountsSeeder.seedDefaults());
    }

}
