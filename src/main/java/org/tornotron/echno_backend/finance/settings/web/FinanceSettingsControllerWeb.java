package org.tornotron.echno_backend.finance.settings.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.finance.settings.FinanceSettingsService;
import org.tornotron.echno_backend.finance.settings.dtos.FinanceSettingsDto;
import org.tornotron.echno_backend.finance.settings.dtos.UpdateFinanceSettingsRequest;

@RestController
@RequestMapping("/api/v1/finance/settings/web")
@RequiredArgsConstructor
@Tag(
        name = "Finance Settings",
        description = "Organization-level finance configuration for the web client. Holds the approval "
                + "threshold that decides whether a construction invoice is auto-approved on submit or "
                + "routed for manual approval. Reading is open to project managers and system "
                + "administrators; changing the settings is restricted to system administrators."
)
public class FinanceSettingsControllerWeb {

    private final FinanceSettingsService service;

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin', 'project-manager')")
    @Operation(
            summary = "Get finance settings",
            description = "Returns the finance settings for the current tenant, creating the default row "
                    + "on first access. A null approval threshold means every construction invoice needs "
                    + "manual approval."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Finance settings returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public FinanceSettingsDto get() {
        return service.getSettings();
    }

    @PutMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Update finance settings",
            description = "Sets the auto-approval threshold for the current tenant. Send a null threshold "
                    + "to require manual approval on every construction invoice, or a value T to "
                    + "auto-approve invoices whose total is below T."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Finance settings updated"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller is not a system administrator in the current tenant")
    })
    public FinanceSettingsDto update(@Valid @RequestBody UpdateFinanceSettingsRequest request) {
        return service.update(request);
    }
}
