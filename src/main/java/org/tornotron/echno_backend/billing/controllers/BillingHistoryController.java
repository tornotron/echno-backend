package org.tornotron.echno_backend.billing.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.billing.checkout.CheckoutService;
import org.tornotron.echno_backend.billing.dto.BillingEventSummaryDto;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;

import java.util.List;

/**
 * The caller's organization's billing history: its own charges, invoices and failures. The
 * global inbox with the dead-letter list is the platform-admin surface at
 * {@code /billing/web/events}; this one never shows another organization's rows.
 */
@RestController
@RequestMapping("/api/v1/billing/events/web")
@RequiredArgsConstructor
@Tag(
        name = "Billing History",
        description = "The caller's organization's billing events, newest first: charges, invoices and payment failures "
                + "as the provider reported them."
)
public class BillingHistoryController {

    private final CheckoutService checkoutService;

    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @GetMapping
    @Operation(
            summary = "List the organization's billing events",
            description = "Returns the caller's organization's billing events, newest first, with the amount and "
                    + "provider reference each carries."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Billing events returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<List<BillingEventSummaryDto>> listEvents() {
        return ResponseEntity.ok(checkoutService.listEvents(TenantContext.getCurrentOrgId()));
    }
}
