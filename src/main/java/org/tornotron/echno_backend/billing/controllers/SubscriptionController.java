package org.tornotron.echno_backend.billing.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.billing.dto.*;
import org.tornotron.echno_backend.billing.services.SubscriptionLifecycleService;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.user.UserContextService;

import javax.naming.AuthenticationException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/billing/subscriptions/web")
@RequiredArgsConstructor
@Validated
@Tag(
        name = "Billing Subscriptions",
        description = "An organization's subscription to a plan, tracking its status, billing period and "
                + "renewal dates. Covers self-service subscription management for the caller's current "
                + "organization, feature access checks, usage recording for metered features, and an admin "
                + "section for managing any organization's subscription."
)
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SubscriptionLifecycleService lifecycle;
    private final UserContextService userContextService;

    /**
     * The organization the self-service endpoints act for. Every one of them is guarded on a
     * role in the current tenant, which refuses a null organization before this runs, so the
     * value is present by the time it is read.
     */
    private static Long currentOrganizationId() {
        return TenantContext.getCurrentOrgId();
    }

    /**
     * Retrieves the current organization's active subscription.
     *
     * @return Active subscription or empty if none
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/current")
    @Operation(
            summary = "Get the current organization's subscription",
            description = "Returns the active subscription of the caller's current organization, if any. An "
                    + "empty body with 204 means the organization has no active subscription."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Active subscription returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Caller has no active subscription"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<SubscriptionDto> getCurrentSubscription() {
        return subscriptionService.getActiveSubscription(currentOrganizationId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Retrieves the subscription history for the current organization.
     *
     * @return List of all subscriptions
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/history")
    @Operation(
            summary = "Get the current organization's subscription history",
            description = "Returns every subscription the caller's current organization has ever held, most "
                    + "recently created first."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription history returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<SubscriptionDto>> getSubscriptionHistory() {
        return ResponseEntity.ok(subscriptionService.getSubscriptionHistory(currentOrganizationId()));
    }

    /**
     * Creates a new subscription for the current organization.
     *
     * @param dto Subscription creation data with plan code
     * @return Created subscription
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping
    @Operation(
            summary = "Create a subscription",
            description = "Subscribes the caller's current organization to the plan identified by planCode, "
                    + "for the given billing period. A free plan is activated at once. With a payment provider "
                    + "wired, a paid plan opens a checkout instead: the row comes back INCOMPLETE with the "
                    + "provider subscription id, and the checkout session for it (POST /billing/checkout/web/sessions, "
                    + "same plan and period) carries what the payment widget needs; the entitlement follows once "
                    + "the buyer authorizes. Without a provider a paid plan is refused unless manual paid rows are allowed."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Subscription created, or opened INCOMPLETE pending the buyer's authorization at the provider"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given code"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Organization already has an active subscription")
    })
    public ResponseEntity<SubscriptionDto> createSubscription(@Valid @RequestBody SubscriptionCreateDto dto)
            throws AuthenticationException {
        Long userId = userContextService.getCurrentUserIdOrThrow();
        SubscriptionDto subscription = lifecycle.subscribe(
                currentOrganizationId(), userId, dto.getPlanCode(), dto.getBillingPeriod());
        return ResponseEntity.status(HttpStatus.CREATED).body(subscription);
    }

    /**
     * Changes the current subscription to a different plan.
     *
     * @param dto Plan change data
     * @return Updated subscription
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PutMapping("/change-plan")
    @Operation(
            summary = "Change the current organization's plan",
            description = "Moves the current organization's active subscription to a different plan, "
                    + "identified by newPlanCode, keeping the same subscription record. A provider-backed "
                    + "subscription is changed at the provider and the record follows the provider's answer, "
                    + "which may schedule the change for the end of the current cycle. A manual subscription "
                    + "cannot be moved to a paid plan this way while a provider is wired; open a checkout for the new plan."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription moved to the new plan"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Organization has no active subscription, or no plan with the given code"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "The new plan is paid and needs a checkout, or no provider is wired for a provider-backed subscription"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "422", description = "The new plan's cycle needs the buyer's per-charge acceptance")
    })
    public ResponseEntity<SubscriptionDto> changeSubscription(@Valid @RequestBody SubscriptionChangeDto dto) {
        return ResponseEntity.ok(lifecycle.changePlan(currentOrganizationId(), dto.getNewPlanCode(), dto.isAcceptPerChargeAfa(), true));
    }

    /**
     * Cancels the current subscription.
     *
     * @param dto Cancellation options
     * @return Success message
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/cancel")
    @Operation(
            summary = "Cancel the current organization's subscription",
            description = "Cancels the current organization's active subscription. By default it stays active "
                    + "until the end of the current billing period; setting immediate true in the request "
                    + "body ends it right away. A provider-backed subscription is cancelled at the provider, so the "
                    + "mandate stops, and the record follows the provider's answer."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription canceled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Organization has no active subscription")
    })
    public ResponseEntity<ApiResponse> cancelSubscription(@RequestBody(required = false) SubscriptionCancelDto dto) {
        boolean immediate = dto != null && dto.isImmediate();
        lifecycle.cancel(currentOrganizationId(), immediate);
        String message = immediate
                ? "Subscription canceled immediately"
                : "Subscription will be canceled at the end of the current billing period";
        return ResponseEntity.ok(new ApiResponse(message));
    }

    /**
     * Checks if the current organization has access to a specific feature.
     *
     * @param featureCode The feature code to check
     * @return Feature access result
     */
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @GetMapping("/features/{featureCode}/access")
    @Operation(
            summary = "Check feature access",
            description = "Reports whether the current organization's subscription grants access to the "
                    + "given feature code, and if it is quota-limited, how much remains."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Feature access result returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<FeatureAccessResultDto> checkFeatureAccess(@PathVariable String featureCode) {
        FeatureAccessResultDto result = subscriptionService.checkFeatureAccess(currentOrganizationId(), featureCode);
        return ResponseEntity.ok(result);
    }

    /**
     * Records usage for a metered feature.
     *
     * @param dto Usage record data
     * @return Success message
     */
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @PostMapping("/usage")
    @Operation(
            summary = "Record feature usage",
            description = "Adds the given amount to the current organization's recorded usage of a metered "
                    + "feature, for quota enforcement and billing."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Usage recorded"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No feature with the given code")
    })
    public ResponseEntity<ApiResponse> recordUsage(@Valid @RequestBody UsageRecordDto dto)
            throws AuthenticationException {
        Long userId = userContextService.getCurrentUserIdOrThrow();
        subscriptionService.recordUsage(currentOrganizationId(), userId, dto.getFeatureCode(), dto.getAmount());
        return ResponseEntity.ok(new ApiResponse("Usage recorded successfully"));
    }

    // --- Admin Endpoints ---

    /**
     * Retrieves subscription for a specific organization.
     * Admin only.
     *
     * @param organizationId Organization ID
     * @return Active subscription or empty
     */
    @PreAuthorize("hasRole('platform-admin')")
    @GetMapping("/organization/{organizationId}")
    @Operation(
            summary = "Get an organization's subscription",
            description = "Returns the active subscription for the given organization id. An empty body "
                    + "with 204 means the organization has no active subscription."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Active subscription returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Organization has no active subscription"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role")
    })
    public ResponseEntity<SubscriptionDto> getOrganizationSubscription(@PathVariable Long organizationId) {
        return subscriptionService.getActiveSubscription(organizationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Retrieves subscription history for a specific organization.
     * Admin only.
     *
     * @param organizationId Organization ID
     * @return List of subscriptions
     */
    @PreAuthorize("hasRole('platform-admin')")
    @GetMapping("/organization/{organizationId}/history")
    @Operation(
            summary = "Get an organization's subscription history",
            description = "Returns every subscription the given organization has ever held, most recently "
                    + "created first."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription history returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role")
    })
    public ResponseEntity<List<SubscriptionDto>> getOrganizationSubscriptionHistory(@PathVariable Long organizationId) {
        return ResponseEntity.ok(subscriptionService.getSubscriptionHistory(organizationId));
    }

    /**
     * Creates a subscription for a specific organization.
     * Admin only.
     *
     * @param organizationId Organization ID
     * @param dto    Subscription creation data
     * @return Created subscription
     */
    @PreAuthorize("hasRole('platform-admin')")
    @PostMapping("/organization/{organizationId}")
    @Operation(
            summary = "Create a subscription for an organization",
            description = "Subscribes the given organization to the plan identified by planCode, for the "
                    + "given billing period. The caller is recorded as the buyer."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Subscription created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given code"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Organization already has an active subscription")
    })
    public ResponseEntity<SubscriptionDto> createSubscriptionForOrganization(
            @PathVariable Long organizationId,
            @Valid @RequestBody SubscriptionCreateDto dto) {
        SubscriptionDto subscription = subscriptionService.createSubscription(
                organizationId, userContextService.getCurrentUserId(), dto.getPlanCode(), dto.getBillingPeriod());
        return ResponseEntity.status(HttpStatus.CREATED).body(subscription);
    }

    /**
     * Changes subscription plan for a specific organization.
     * Admin only.
     *
     * @param organizationId Organization ID
     * @param dto    Plan change data
     * @return Updated subscription
     */
    @PreAuthorize("hasRole('platform-admin')")
    @PutMapping("/organization/{organizationId}/change-plan")
    @Operation(
            summary = "Change an organization's plan",
            description = "Moves the given organization's active subscription to a different plan, "
                    + "identified by newPlanCode."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription moved to the new plan"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Organization has no active subscription, or no plan with the given code")
    })
    public ResponseEntity<SubscriptionDto> changeSubscriptionForOrganization(
            @PathVariable Long organizationId,
            @Valid @RequestBody SubscriptionChangeDto dto) {
        return ResponseEntity.ok(lifecycle.changePlan(organizationId, dto.getNewPlanCode(), dto.isAcceptPerChargeAfa(), false));
    }

    /**
     * Cancels subscription for a specific organization.
     * Admin only.
     *
     * @param organizationId Organization ID
     * @param dto    Cancellation options
     * @return Success message
     */
    @PreAuthorize("hasRole('platform-admin')")
    @PostMapping("/organization/{organizationId}/cancel")
    @Operation(
            summary = "Cancel an organization's subscription",
            description = "Cancels the given organization's active subscription. By default it stays active "
                    + "until the end of the current billing period; setting immediate true in the request "
                    + "body ends it right away."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription canceled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Organization has no active subscription")
    })
    public ResponseEntity<ApiResponse> cancelSubscriptionForOrganization(
            @PathVariable Long organizationId,
            @RequestBody(required = false) SubscriptionCancelDto dto) {
        boolean immediate = dto != null && dto.isImmediate();
        lifecycle.cancel(organizationId, immediate);
        String message = immediate
                ? "Subscription canceled immediately"
                : "Subscription will be canceled at the end of the current billing period";
        return ResponseEntity.ok(new ApiResponse(message));
    }
}
