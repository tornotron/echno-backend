package org.tornotron.echno_backend.finance.ledger.web;

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
@Tag(
        name = "Ledger Accounts",
        description = "Chart of accounts for double-entry bookkeeping. Accounts are arranged in a "
                + "hierarchy under the five root types (asset, liability, equity, income, expense), and "
                + "only leaf accounts are postable. All endpoints are tenant scoped and limited to system "
                + "administrators and project managers, apart from the default seed, which is admin only."
)
public class AccountControllerWeb {

    private final AccountService service;
    private final ChartOfAccountsSeeder chartOfAccountsSeeder;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "List accounts",
            description = "Returns the flat list of accounts in the current tenant. By default only active "
                    + "accounts are returned; set activeOnly to false to include deactivated accounts."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of accounts"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<AccountDto> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return activeOnly ? service.findAllActiveAccounts() : service.findAllAccounts();
    }

    @GetMapping("/tree")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get the chart of accounts as a tree",
            description = "Returns the accounts of the current tenant as a nested tree rooted at the five "
                    + "account types, with each account carrying its child accounts."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account tree"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<AccountTreeDto> tree() {
        return service.findAccountTree();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get an account by id",
            description = "Returns a single account by its unique id."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No account with the given id in the current tenant")
    })
    public AccountDto get(@PathVariable UUID id) {
        return service.findAccountById(id);
    }

    @GetMapping("/by-code/{code}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get an account by code",
            description = "Returns a single account by its account code, which is unique within the tenant."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No account with the given code in the current tenant")
    })
    public AccountDto getByCode(@PathVariable String code) {
        return service.findByAccountByCode(code);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Create an account",
            description = "Creates a new account in the chart. When a parent is given, the account type is "
                    + "inherited from the parent; a root account must declare its own type. When the code is "
                    + "left blank it is generated from the parent and existing sibling accounts."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "The referenced parent account does not exist")
    })
    public ResponseEntity<AccountDto> create(@Valid @RequestBody CreateAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Deactivate an account",
            description = "Marks an account inactive so it can no longer be used on new journal entries. "
                    + "Existing entries that reference the account are left unchanged."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account deactivated"),
            @ApiResponse(responseCode = "400", description = "Account cannot be deactivated in its current state"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No account with the given id in the current tenant")
    })
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
    @Operation(
            summary = "Seed the default chart of accounts",
            description = "Creates the default chart of accounts for the current tenant. The operation is "
                    + "idempotent: a tenant that already has a chart is left untouched, so it can safely "
                    + "back-fill an organization created before seeding was wired in. Returns the number of "
                    + "accounts created. Restricted to system administrators."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Seed complete, returns the count of accounts created"),
            @ApiResponse(responseCode = "403", description = "Caller is not a system administrator in the current tenant")
    })
    public Map<String, Integer> seedDefaults() {
        return Map.of("created", chartOfAccountsSeeder.seedDefaults());
    }

}
