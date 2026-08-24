package org.tornotron.echno_backend.finance.posting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.posting.PostingRole;
import org.tornotron.echno_backend.finance.posting.dtos.PostingAccountMappingDto;
import org.tornotron.echno_backend.finance.posting.dtos.UpsertPostingAccountMappingRequest;
import org.tornotron.echno_backend.finance.posting.service.PostingAccountMappingService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/finance/posting-accounts/web")
@RequiredArgsConstructor
@Tag(
        name = "Posting Account Mapping",
        description = "Per-organization mapping of finance posting roles (accounts receivable, GST "
                + "output, accounts payable, GST input, default revenue, default expense) to concrete "
                + "accounts. Where a role is unmapped, the postings fall back to the configured default "
                + "account. Reading is open to project managers and system administrators; changing a "
                + "mapping is restricted to system administrators."
)
public class PostingAccountMappingControllerWeb {

    private final PostingAccountMappingService service;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "List effective posting accounts",
            description = "Returns all six posting roles with the account each currently resolves to for "
                    + "the tenant, flagged as coming from an explicit mapping (MAPPED) or the configured "
                    + "default (DEFAULT)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Effective posting accounts returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "A default account is not configured in the tenant's chart")
    })
    public List<PostingAccountMappingDto> list() {
        return service.listEffective();
    }

    @PutMapping("/{role}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Map a posting role to an account",
            description = "Points the given posting role at a specific account, creating the mapping or "
                    + "updating it in place. The account must be a postable leaf account in the current "
                    + "tenant."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mapping saved, effective mapping returned"),
            @ApiResponse(responseCode = "400", description = "The account is inactive or is a header account"),
            @ApiResponse(responseCode = "403", description = "Caller is not a system administrator in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No account with the given id in the current tenant")
    })
    public PostingAccountMappingDto upsert(@PathVariable PostingRole role,
                                           @Valid @RequestBody UpsertPostingAccountMappingRequest req) {
        return service.upsert(role, req.accountId());
    }

    @DeleteMapping("/{role}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Remove a posting-role mapping",
            description = "Removes the mapping override for the given role, reverting it to the configured "
                    + "default account. A no-op when the role has no mapping."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mapping removed, effective (default) mapping returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a system administrator in the current tenant"),
            @ApiResponse(responseCode = "404", description = "The default account is not configured in the tenant's chart")
    })
    public PostingAccountMappingDto delete(@PathVariable PostingRole role) {
        return service.delete(role);
    }
}
