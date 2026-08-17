package org.tornotron.echno_backend.billing.controllers;

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
