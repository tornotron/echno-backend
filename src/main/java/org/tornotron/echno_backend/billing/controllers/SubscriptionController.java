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
import org.tornotron.echno_backend.billing.Subscription;
import org.tornotron.echno_backend.billing.dto.*;
import org.tornotron.echno_backend.billing.repositories.SubscriptionRepository;
import org.tornotron.echno_backend.billing.services.SubscriptionService;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.user.UserContextService;

import javax.naming.AuthenticationException;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/billing/subscriptions/web")
@RequiredArgsConstructor
@Validated
@Tag(
        name = "Billing Subscriptions",
        description = "A user's subscription to a plan, tracking its status, billing period and renewal "
                + "dates. Covers self-service subscription management for the current user, feature access "
                + "checks, usage recording for metered features, and an admin section for managing any "
                + "user's subscription."
)
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SubscriptionRepository subscriptionRepository;
    private final UserContextService userContextService;

    /**
     * Retrieves the current user's active subscription.
     *
     * @return Active subscription or empty if none
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/current")
    @Operation(
            summary = "Get the current user's subscription",
            description = "Returns the caller's active subscription, if any. An empty body with 204 means "
                    + "the caller has no active subscription."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Active subscription returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "Caller has no active subscription"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<SubscriptionDto> getCurrentSubscription() throws AuthenticationException {
        Long userId = userContextService.getCurrentUserIdOrThrow();
        Optional<Subscription> subscription = subscriptionService.getActiveSubscription(userId);
        return subscription
                .map(sub -> ResponseEntity.ok(BillingMapper.toSubscriptionDto(sub)))
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Retrieves the subscription history for the current user.
     *
     * @return List of all subscriptions
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @GetMapping("/history")
    @Operation(
            summary = "Get the current user's subscription history",
            description = "Returns every subscription the caller has ever held, most recently created first."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription history returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<SubscriptionDto>> getSubscriptionHistory() throws AuthenticationException {
        Long userId = userContextService.getCurrentUserIdOrThrow();
        List<Subscription> subscriptions = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return ResponseEntity.ok(BillingMapper.toSubscriptionDtoList(subscriptions));
    }

    /**
     * Creates a new subscription for the current user.
     *
     * @param dto Subscription creation data with plan code
     * @return Created subscription
     */
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping
    @Operation(
            summary = "Create a subscription",
            description = "Subscribes the current user to the plan identified by planCode, for the given "
                    + "billing period."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Subscription created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given code"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Caller already has an active subscription")
    })
    public ResponseEntity<SubscriptionDto> createSubscription(@Valid @RequestBody SubscriptionCreateDto dto)
            throws AuthenticationException {
        Long userId = userContextService.getCurrentUserIdOrThrow();
        Subscription subscription = subscriptionService.createSubscription(userId, dto.getPlanCode(), dto.getBillingPeriod());
        return ResponseEntity.status(HttpStatus.CREATED).body(BillingMapper.toSubscriptionDto(subscription));
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
            summary = "Change the current user's plan",
            description = "Moves the caller's active subscription to a different plan, identified by "
                    + "newPlanCode, keeping the same subscription record."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription moved to the new plan"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Caller has no active subscription, or no plan with the given code")
    })
    public ResponseEntity<SubscriptionDto> changeSubscription(@Valid @RequestBody SubscriptionChangeDto dto)
            throws AuthenticationException {
        Long userId = userContextService.getCurrentUserIdOrThrow();
        Subscription subscription = subscriptionService.changeSubscription(userId, dto.getNewPlanCode());
        return ResponseEntity.ok(BillingMapper.toSubscriptionDto(subscription));
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
            summary = "Cancel the current user's subscription",
            description = "Cancels the caller's active subscription. By default it stays active until the "
                    + "end of the current billing period; setting immediate true in the request body ends "
                    + "it right away."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription canceled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Caller has no active subscription")
    })
    public ResponseEntity<ApiResponse> cancelSubscription(@RequestBody(required = false) SubscriptionCancelDto dto)
            throws AuthenticationException {
        Long userId = userContextService.getCurrentUserIdOrThrow();
        boolean immediate = dto != null && dto.isImmediate();
        subscriptionService.cancelSubscription(userId, immediate);
        String message = immediate
                ? "Subscription canceled immediately"
                : "Subscription will be canceled at the end of the current billing period";
        return ResponseEntity.ok(new ApiResponse(message));
    }

    /**
     * Checks if the current user has access to a specific feature.
     *
     * @param featureCode The feature code to check
     * @return Feature access result
     */
    @PreAuthorize("@orgSecurity.isMemberOfCurrentTenant()")
    @GetMapping("/features/{featureCode}/access")
    @Operation(
            summary = "Check feature access",
            description = "Reports whether the current user's subscription grants access to the given "
                    + "feature code, and if it is quota-limited, how much remains."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Feature access result returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<FeatureAccessResultDto> checkFeatureAccess(@PathVariable String featureCode)
            throws AuthenticationException {
        Long userId = userContextService.getCurrentUserIdOrThrow();
        FeatureAccessResultDto result = subscriptionService.checkFeatureAccess(userId, featureCode);
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
            description = "Adds the given amount to the current user's recorded usage of a metered feature, "
                    + "for quota enforcement and billing."
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
        subscriptionService.recordUsage(userId, dto.getFeatureCode(), dto.getAmount());
        return ResponseEntity.ok(new ApiResponse("Usage recorded successfully"));
    }

    // --- Admin Endpoints ---

    /**
     * Retrieves subscription for a specific user.
     * Admin only.
     *
     * @param userId User ID
     * @return Active subscription or empty
     */
    @PreAuthorize("hasAuthority('billing:admin')")
    @GetMapping("/user/{userId}")
//    @PreAuthorize("hasAuthority('billing:admin')")
    @Operation(
            summary = "Get a user's subscription",
            description = "Returns the active subscription for the given user id. An empty body with 204 "
                    + "means the user has no active subscription."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Active subscription returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "User has no active subscription"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the billing admin authority")
    })
    public ResponseEntity<SubscriptionDto> getUserSubscription(@PathVariable Long userId) {
        Optional<Subscription> subscription = subscriptionService.getActiveSubscription(userId);
        return subscription
                .map(sub -> ResponseEntity.ok(BillingMapper.toSubscriptionDto(sub)))
                .orElse(ResponseEntity.noContent().build());
    }

    /**
     * Retrieves subscription history for a specific user.
     * Admin only.
     *
     * @param userId User ID
     * @return List of subscriptions
     */
    @PreAuthorize("hasAuthority('billing:admin')")
    @GetMapping("/user/{userId}/history")
//    @PreAuthorize("hasAuthority('billing:admin')")
    @Operation(
            summary = "Get a user's subscription history",
            description = "Returns every subscription the given user has ever held, most recently created "
                    + "first."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription history returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the billing admin authority")
    })
    public ResponseEntity<List<SubscriptionDto>> getUserSubscriptionHistory(@PathVariable Long userId) {
        List<Subscription> subscriptions = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return ResponseEntity.ok(BillingMapper.toSubscriptionDtoList(subscriptions));
    }

    /**
     * Creates a subscription for a specific user.
     * Admin only.
     *
     * @param userId User ID
     * @param dto    Subscription creation data
     * @return Created subscription
     */
    @PreAuthorize("hasAuthority('billing:admin')")
    @PostMapping("/user/{userId}")
//    @PreAuthorize("hasAuthority('billing:admin')")
    @Operation(
            summary = "Create a subscription for a user",
            description = "Subscribes the given user to the plan identified by planCode, for the given "
                    + "billing period."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Subscription created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the billing admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given code"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "User already has an active subscription")
    })
    public ResponseEntity<SubscriptionDto> createSubscriptionForUser(
            @PathVariable Long userId,
            @Valid @RequestBody SubscriptionCreateDto dto) {
        Subscription subscription = subscriptionService.createSubscription(userId, dto.getPlanCode(), dto.getBillingPeriod());
        return ResponseEntity.status(HttpStatus.CREATED).body(BillingMapper.toSubscriptionDto(subscription));
    }

    /**
     * Changes subscription plan for a specific user.
     * Admin only.
     *
     * @param userId User ID
     * @param dto    Plan change data
     * @return Updated subscription
     */
    @PreAuthorize("hasAuthority('billing:admin')")
    @PutMapping("/user/{userId}/change-plan")
//    @PreAuthorize("hasAuthority('billing:admin')")
    @Operation(
            summary = "Change a user's plan",
            description = "Moves the given user's active subscription to a different plan, identified by "
                    + "newPlanCode."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription moved to the new plan"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the billing admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User has no active subscription, or no plan with the given code")
    })
    public ResponseEntity<SubscriptionDto> changeSubscriptionForUser(
            @PathVariable Long userId,
            @Valid @RequestBody SubscriptionChangeDto dto) {
        Subscription subscription = subscriptionService.changeSubscription(userId, dto.getNewPlanCode());
        return ResponseEntity.ok(BillingMapper.toSubscriptionDto(subscription));
    }

    /**
     * Cancels subscription for a specific user.
     * Admin only.
     *
     * @param userId User ID
     * @param dto    Cancellation options
     * @return Success message
     */
    @PreAuthorize("hasAuthority('billing:admin')")
    @PostMapping("/user/{userId}/cancel")
//    @PreAuthorize("hasAuthority('billing:admin')")
    @Operation(
            summary = "Cancel a user's subscription",
            description = "Cancels the given user's active subscription. By default it stays active until "
                    + "the end of the current billing period; setting immediate true in the request body "
                    + "ends it right away."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Subscription canceled"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the billing admin authority"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User has no active subscription")
    })
    public ResponseEntity<ApiResponse> cancelSubscriptionForUser(
            @PathVariable Long userId,
            @RequestBody(required = false) SubscriptionCancelDto dto) {
        boolean immediate = dto != null && dto.isImmediate();
        subscriptionService.cancelSubscription(userId, immediate);
        String message = immediate
                ? "Subscription canceled immediately"
                : "Subscription will be canceled at the end of the current billing period";
        return ResponseEntity.ok(new ApiResponse(message));
    }
}
