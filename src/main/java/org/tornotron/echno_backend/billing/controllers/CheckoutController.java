package org.tornotron.echno_backend.billing.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.billing.checkout.CheckoutService;
import org.tornotron.echno_backend.billing.dto.BillingProviderInfoDto;
import org.tornotron.echno_backend.billing.dto.CheckoutMandateTermsDto;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionCreateDto;
import org.tornotron.echno_backend.billing.dto.CheckoutSessionDto;
import org.tornotron.echno_backend.billing.dto.MandateAcknowledgeDto;
import org.tornotron.echno_backend.billing.dto.MandateDto;
import org.tornotron.echno_backend.billing.dto.SubscriptionDto;
import org.tornotron.echno_backend.billing.dto.VerifyCheckoutDto;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.user.UserContextService;

import javax.naming.AuthenticationException;

/**
 * The hosted checkout the web drives for the caller's organization. Reads are open to any
 * member; opening a checkout, verifying its result and acknowledging the mandate are the
 * organization's billing administration, so they need the org system-admin role.
 */
@RestController
@RequestMapping("/api/v1/billing/checkout/web")
@RequiredArgsConstructor
@Validated
@Tag(
        name = "Billing Checkout",
        description = "Online checkout for the caller's organization: which provider is wired, opening a provider "
                + "subscription for a plan, verifying the browser widget's result, and the recurring-payment mandate. "
                + "Entitlement is granted only after the payment result verifies or the provider's webhook arrives."
)
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final UserContextService userContextService;

    private static Long currentOrganizationId() {
        return TenantContext.getCurrentOrgId();
    }

    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @GetMapping("/provider")
    @Operation(
            summary = "Get the billing provider",
            description = "Reports which payment provider the environment is wired to, whether checkout is enabled, the "
                    + "public key id the browser widget needs, the currency, and the RBI mandate constraints. Under "
                    + "provider NONE the response is well formed with enabled false."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Provider information returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<BillingProviderInfoDto> getProvider() {
        return ResponseEntity.ok(checkoutService.providerInfo());
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/sessions")
    @Operation(
            summary = "Open a checkout session",
            description = "Creates the provider subscription for the plan and returns what the browser widget needs. A "
                    + "free plan is activated directly and returned in the subscription field with no provider ids. "
                    + "No entitlement is granted for a paid plan here."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Checkout session opened"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No plan with the given code"),
            @ApiResponse(responseCode = "409", description = "No billing provider is configured, or the organization is already subscribed to the plan"),
            @ApiResponse(responseCode = "422", description = "The plan breaks a mandate rule: above the RBI cap without acceptance, or no channel for the pre-debit notice")
    })
    public ResponseEntity<CheckoutSessionDto> createSession(@Valid @RequestBody CheckoutSessionCreateDto dto)
            throws AuthenticationException {
        Long userId = userContextService.getCurrentUserIdOrThrow();
        return ResponseEntity.status(HttpStatus.CREATED).body(checkoutService.createSession(currentOrganizationId(), userId, dto));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/verify")
    @Operation(
            summary = "Verify a checkout result",
            description = "Checks the provider signature of the browser widget's success payload against the checkout "
                    + "session and activates the subscription once. Idempotent: a session already verified, or a "
                    + "subscription the provider's webhook already activated, returns the row as it is."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signature verified; the projected subscription returned"),
            @ApiResponse(responseCode = "400", description = "The signature does not verify, or the body is invalid"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No checkout session matches the payment result"),
            @ApiResponse(responseCode = "409", description = "No billing provider is configured")
    })
    public ResponseEntity<SubscriptionDto> verify(@Valid @RequestBody VerifyCheckoutDto dto) {
        return ResponseEntity.ok(checkoutService.verify(currentOrganizationId(), dto));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/mandate")
    @Operation(
            summary = "Get the organization's mandate",
            description = "Returns the organization's most recently registered recurring-payment mandate. An empty "
                    + "body with 204 means none has been registered."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Mandate returned"),
            @ApiResponse(responseCode = "204", description = "No mandate registered"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<MandateDto> getMandate() {
        return checkoutService.currentMandate(currentOrganizationId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/mandate")
    @Operation(
            summary = "Acknowledge the mandate terms",
            description = "Records the buyer's acceptance of the recurring-payment terms for a plan and cycle and returns "
                    + "the constraints: the ceiling, the pre-debit notice, and whether each charge needs authentication. "
                    + "The mandate itself is registered by the provider during subscription authorization."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Terms returned and the acknowledgement recorded"),
            @ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @ApiResponse(responseCode = "404", description = "No plan with the given code"),
            @ApiResponse(responseCode = "422", description = "The terms break a mandate rule")
    })
    public ResponseEntity<CheckoutMandateTermsDto> acknowledgeMandate(@Valid @RequestBody MandateAcknowledgeDto dto) {
        return ResponseEntity.ok(checkoutService.acknowledgeMandate(currentOrganizationId(), dto));
    }
}
