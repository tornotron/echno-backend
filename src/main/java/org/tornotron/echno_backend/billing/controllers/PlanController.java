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
import org.tornotron.echno_backend.billing.services.PlanService;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.util.List;

/**
 * Two different bars here, because these are two different kinds of endpoint.
 *
 * <p>The catalogue reads are ordinary authenticated reads. A signed-in user, including one who
 * belongs to no organization yet, has to be able to see what plans exist before they can buy one,
 * which is what a pricing page needs. {@code GET /public} in particular is named for that and was
 * previously gated on a platform-admin authority, which is wrong on its own terms.
 *
 * <p>The catalogue writes are platform-staff operations. The plan catalogue is global, so it must
 * not fall to a customer's own {@code system-admin}: that role is held by the administrator of
 * every tenant, and letting it edit plans would let any customer rewrite what every other customer
 * is sold.
 *
 * <p>All of these used to ask for {@code hasAuthority('billing:admin')}. {@code JwtAuthConverter}
 * mints a bare {@code resource:scope} authority in exactly one place, from the {@code authorization}
 * claim of an RPT, so that string needed a Keycloak Authorization Services resource named
 * {@code billing} carrying an {@code admin} scope. The multi-tenancy audit of 2026-08-18 checked
 * the live realm for the identical case of {@code organization:admin} and found zero authorization
 * scopes realm-wide, one scopeless {@code Default Resource} and one scopeless
 * {@code Default Permission}. A scopeless permission yields no {@code resource:scope} authority, so
 * {@code billing:admin} was issued to nobody and could not be issued to anybody. It is now a
 * Keycloak client role, delivered through {@code resource_access} as {@code ROLE_platform-admin},
 * which is the grant mechanism this realm actually has. Until the realm carries that role these
 * endpoints still refuse, but they refuse deliberately and the grant is a realm change rather than
 * an impossibility. See #641.
 */
@RestController
@RequestMapping("/api/v1/billing/plans/web")
@RequiredArgsConstructor
@Validated
@Tag(
        name = "Billing Plans",
        description = "Subscription plans such as Starter, Professional or Enterprise, each carrying a "
                + "price per billing period and a set of assigned features. Endpoints cover listing public "
                + "plans, full plan administration, and attaching or detaching features on a plan. All "
                + "administrative endpoints require the platform-admin role."
)
public class PlanController {

    private final PlanService planService;

    /**
     * Retrieves all public and active plans.
     * Available to all authenticated users for viewing available plans.
     *
     * @return List of public plans
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/public")
    @Operation(
            summary = "List public plans",
            description = "Returns the plans that are both public and active, for display on a pricing page."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of public plans"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not authenticated")
    })
    public ResponseEntity<List<PlanDto>> getPublicPlans() {
        return ResponseEntity.ok(planService.getAllPublicPlans());
    }

    /**
     * Retrieves all plans including inactive ones.
     * Admin only.
     *
     * @return List of all plans
     */
    @PreAuthorize("hasRole('platform-admin')")
    @GetMapping
    @Operation(
            summary = "List all plans",
            description = "Returns every plan, including private and inactive ones, for administration."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "List of all plans"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role")
    })
    public ResponseEntity<List<PlanDto>> getAllPlans() {
        return ResponseEntity.ok(planService.getAllPlans());
    }

    /**
     * Retrieves a specific plan by ID with all features.
     *
     * @param id Plan ID
     * @return Plan details
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    @Operation(
            summary = "Get a plan by id",
            description = "Returns a single plan by its numeric id, including its assigned features."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Plan found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given id")
    })
    public ResponseEntity<PlanDto> getPlanById(@PathVariable Long id) {
        return ResponseEntity.ok(planService.getPlanById(id));
    }

    /**
     * Retrieves a specific plan by code.
     *
     * @param code Plan code
     * @return Plan details
     */
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/code/{code}")
    @Operation(
            summary = "Get a plan by code",
            description = "Returns a single plan by its unique code, such as professional-monthly."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Plan found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not authenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given code")
    })
    public ResponseEntity<PlanDto> getPlanByCode(@PathVariable String code) {
        return ResponseEntity.ok(planService.getPlanByCode(code));
    }

    /**
     * Creates a new plan.
     * Admin only.
     *
     * @param dto Plan creation data
     * @return Created plan
     */
    @PreAuthorize("hasRole('platform-admin')")
    @PostMapping
    @Operation(
            summary = "Create a plan",
            description = "Creates a new plan with the given price and billing period. Features are attached "
                    + "afterwards through the plan features endpoints."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Plan created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role")
    })
    public ResponseEntity<PlanDto> createPlan(@Valid @RequestBody PlanCreateDto dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(planService.createPlan(dto));
    }

    /**
     * Updates an existing plan.
     * Admin only.
     *
     * @param id  Plan ID
     * @param dto Plan update data
     * @return Updated plan
     */
    @PreAuthorize("hasRole('platform-admin')")
    @PutMapping("/{id}")
    @Operation(
            summary = "Update a plan",
            description = "Updates the price, period or visibility of an existing plan identified by id."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Plan updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given id")
    })
    public ResponseEntity<PlanDto> updatePlan(@PathVariable Long id, @Valid @RequestBody PlanCreateDto dto) {
        return ResponseEntity.ok(planService.updatePlan(id, dto));
    }

    /**
     * Deactivates a plan (soft delete).
     * Admin only.
     *
     * @param id Plan ID
     * @return Success message
     */
    @PreAuthorize("hasRole('platform-admin')")
    @DeleteMapping("/{id}")
    @Operation(
            summary = "Deactivate a plan",
            description = "Soft-deletes a plan so it can no longer be subscribed to. Existing subscriptions "
                    + "on the plan are left unchanged."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Plan deactivated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given id")
    })
    public ResponseEntity<ApiResponse> deactivatePlan(@PathVariable Long id) {
        planService.deactivatePlan(id);
        return ResponseEntity.ok(new ApiResponse("Plan deactivated successfully"));
    }

    /**
     * Reactivates a deactivated plan.
     * Admin only.
     *
     * @param id Plan ID
     * @return Success message
     */
    @PreAuthorize("hasRole('platform-admin')")
    @PostMapping("/{id}/activate")
    @Operation(
            summary = "Reactivate a plan",
            description = "Reactivates a previously deactivated plan so it can be subscribed to again."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Plan activated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given id")
    })
    public ResponseEntity<ApiResponse> activatePlan(@PathVariable Long id) {
        planService.activatePlan(id);
        return ResponseEntity.ok(new ApiResponse("Plan activated successfully"));
    }

    /**
     * Assigns a feature to a plan.
     * Admin only.
     *
     * @param planId Plan ID
     * @param dto    Feature assignment data
     * @return Updated plan
     */
    @PreAuthorize("hasRole('platform-admin')")
    @PostMapping("/{planId}/features")
    @Operation(
            summary = "Assign a feature to a plan",
            description = "Attaches a feature to a plan, optionally with a quota or limit value carried in "
                    + "the request body."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Feature assigned, plan returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation failed on the request body"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given id, or no feature with the given code")
    })
    public ResponseEntity<PlanDto> assignFeatureToPlan(
            @PathVariable Long planId,
            @Valid @RequestBody PlanFeatureAssignDto dto) {
        return ResponseEntity.ok(planService.assignFeatureToPlan(planId, dto));
    }

    /**
     * Removes a feature from a plan.
     * Admin only.
     *
     * @param planId      Plan ID
     * @param featureCode Feature code to remove
     * @return Updated plan
     */
    @PreAuthorize("hasRole('platform-admin')")
    @DeleteMapping("/{planId}/features/{featureCode}")
    @Operation(
            summary = "Remove a feature from a plan",
            description = "Detaches a feature from a plan. Subscribers to the plan lose access to the "
                    + "feature immediately."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Feature removed, plan returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the platform-admin role"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No plan with the given id, or the feature is not assigned to it")
    })
    public ResponseEntity<PlanDto> removeFeatureFromPlan(
            @PathVariable Long planId,
            @PathVariable String featureCode) {
        return ResponseEntity.ok(planService.removeFeatureFromPlan(planId, featureCode));
    }
}
