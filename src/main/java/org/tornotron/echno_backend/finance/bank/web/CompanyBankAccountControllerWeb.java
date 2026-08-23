package org.tornotron.echno_backend.finance.bank.web;

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
import org.tornotron.echno_backend.finance.bank.dtos.CompanyBankAccountDto;
import org.tornotron.echno_backend.finance.bank.dtos.CreateCompanyBankAccountRequest;
import org.tornotron.echno_backend.finance.bank.service.CompanyBankAccountService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/company-bank-accounts/web")
@RequiredArgsConstructor
@Tag(
        name = "Company Bank Accounts",
        description = "The organization's own bank accounts used to receive and pay funds, each linked to "
                + "a ledger account for reconciliation. Endpoints cover listing, reading, creating and "
                + "deactivating accounts. All endpoints are tenant scoped and limited to system "
                + "administrators and project managers."
)
public class CompanyBankAccountControllerWeb {

    private final CompanyBankAccountService service;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "List company bank accounts",
            description = "Returns the organization's bank accounts. By default only active accounts are "
                    + "returned; set activeOnly to false to include deactivated accounts as well."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of matching bank accounts"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public List<CompanyBankAccountDto> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return activeOnly ? service.findAllActive() : service.findAll();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get a company bank account by id",
            description = "Returns a single company bank account, including the linked ledger account."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bank account found"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No bank account with the given id in the current tenant")
    })
    public CompanyBankAccountDto get(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Create a company bank account",
            description = "Registers a new company bank account, linked to an existing ledger account for "
                    + "reconciliation."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Bank account created"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No ledger account with the given id")
    })
    public ResponseEntity<CompanyBankAccountDto> create(@Valid @RequestBody CreateCompanyBankAccountRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Deactivate a company bank account",
            description = "Marks a bank account inactive so it can no longer be selected on new "
                    + "transactions. Existing records that reference it are left unchanged."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Bank account deactivated"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No bank account with the given id in the current tenant")
    })
    public CompanyBankAccountDto deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }
}
