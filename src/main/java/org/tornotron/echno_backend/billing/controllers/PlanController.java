package org.tornotron.echno_backend.billing.controllers;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.billing.Plan;
import org.tornotron.echno_backend.billing.dto.*;
import org.tornotron.echno_backend.billing.services.PlanService;
import org.tornotron.echno_backend.common.response.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/v1/billing/plans/web")
@RequiredArgsConstructor
@Validated
public class PlanController {

    private final PlanService planService;

    /**
     * Retrieves all public and active plans.
     * Available to all authenticated users for viewing available plans.
     *
     * @return List of public plans
     */
    @GetMapping("/public")
    public ResponseEntity<List<PlanDto>> getPublicPlans() {
        List<Plan> plans = planService.getAllPublicPlans();
        return ResponseEntity.ok(BillingMapper.toPlanDtoList(plans));
    }

    /**
     * Retrieves all plans including inactive ones.
     * Admin only.
     *
     * @return List of all plans
     */
    @GetMapping
//    @PreAuthorize("hasAuthority('billing:admin')")
    public ResponseEntity<List<PlanDto>> getAllPlans() {
        List<Plan> plans = planService.getAllPlans();
        return ResponseEntity.ok(BillingMapper.toPlanDtoList(plans));
    }

    /**
     * Retrieves a specific plan by ID with all features.
     *
     * @param id Plan ID
     * @return Plan details
     */
    @GetMapping("/{id}")
//    @PreAuthorize("hasAuthority('billing:read') or hasAuthority('billing:admin')")
    public ResponseEntity<PlanDto> getPlanById(@PathVariable Long id) {
        Plan plan = planService.getPlanById(id);
        return ResponseEntity.ok(BillingMapper.toPlanDto(plan));
    }

    /**
     * Retrieves a specific plan by code.
     *
     * @param code Plan code
     * @return Plan details
     */
    @GetMapping("/code/{code}")
//    @PreAuthorize("hasAuthority('billing:read') or hasAuthority('billing:admin')")
    public ResponseEntity<PlanDto> getPlanByCode(@PathVariable String code) {
        Plan plan = planService.getPlanByCode(code);
        return ResponseEntity.ok(BillingMapper.toPlanDto(plan));
    }

    /**
     * Creates a new plan.
     * Admin only.
     *
     * @param dto Plan creation data
     * @return Created plan
     */
    @PostMapping
//    @PreAuthorize("hasAuthority('billing:admin')")
    public ResponseEntity<PlanDto> createPlan(@Valid @RequestBody PlanCreateDto dto) {
        Plan plan = planService.createPlan(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(BillingMapper.toPlanDto(plan));
    }

    /**
     * Updates an existing plan.
     * Admin only.
     *
     * @param id  Plan ID
     * @param dto Plan update data
     * @return Updated plan
     */
    @PutMapping("/{id}")
//    @PreAuthorize("hasAuthority('billing:admin')")
    public ResponseEntity<PlanDto> updatePlan(@PathVariable Long id, @Valid @RequestBody PlanCreateDto dto) {
        Plan plan = planService.updatePlan(id, dto);
        return ResponseEntity.ok(BillingMapper.toPlanDto(plan));
    }

    /**
     * Deactivates a plan (soft delete).
     * Admin only.
     *
     * @param id Plan ID
     * @return Success message
     */
    @DeleteMapping("/{id}")
//    @PreAuthorize("hasAuthority('billing:admin')")
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
    @PostMapping("/{id}/activate")
//    @PreAuthorize("hasAuthority('billing:admin')")
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
    @PostMapping("/{planId}/features")
//    @PreAuthorize("hasAuthority('billing:admin')")
    public ResponseEntity<PlanDto> assignFeatureToPlan(
            @PathVariable Long planId,
            @Valid @RequestBody PlanFeatureAssignDto dto) {
        Plan plan = planService.assignFeatureToPlan(planId, dto);
        return ResponseEntity.ok(BillingMapper.toPlanDto(plan));
    }

    /**
     * Removes a feature from a plan.
     * Admin only.
     *
     * @param planId      Plan ID
     * @param featureCode Feature code to remove
     * @return Updated plan
     */
    @DeleteMapping("/{planId}/features/{featureCode}")
//    @PreAuthorize("hasAuthority('billing:admin')")
    public ResponseEntity<PlanDto> removeFeatureFromPlan(
            @PathVariable Long planId,
            @PathVariable String featureCode) {
        Plan plan = planService.removeFeatureFromPlan(planId, featureCode);
        return ResponseEntity.ok(BillingMapper.toPlanDto(plan));
    }
}
