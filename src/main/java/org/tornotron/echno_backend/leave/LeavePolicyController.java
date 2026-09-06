package org.tornotron.echno_backend.leave;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.leave.dto.LeavePolicyCreationDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicyDto;

import java.util.List;
import java.util.Map;
import org.tornotron.echno_backend.leave.dto.LeavePolicyUpdateFieldsDto;

@RestController
@RequestMapping("/api/v1/leave-policies")
@Validated
@Tag(
        name = "Leave Policies",
        description = "Leave types configured per organization: annual quota, accrual rate, carry-forward "
                + "rule, half-day and attachment rules. Endpoints cover creating, reading, updating, "
                + "deactivating, reactivating and duplicating a policy into another organization. Creation, "
                + "updates and lifecycle changes are gated to the system-admin or hr-admin role in the "
                + "caller's tenant."
)
public class LeavePolicyController {

    private final LeavePolicyService policyService;

    public LeavePolicyController(LeavePolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @Operation(
            summary = "Create a leave policy",
            description = "Creates a leave policy for an organization from the given quota, accrual and "
                    + "eligibility rules. Returns the created policy."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Policy created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "The policy payload failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<LeavePolicyDto> createPolicy(
            @Valid @RequestBody LeavePolicyCreationDto dto) {
        LeavePolicyDto created = policyService.createPolicy(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{policyId}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Get a leave policy by id",
            description = "Returns a single leave policy with its quota, accrual and eligibility rules."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave policy with the given id")
    })
    public ResponseEntity<LeavePolicyDto> getPolicy(@PathVariable Long policyId) {
        return ResponseEntity.ok(policyService.getPolicy(policyId));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "List all leave policies",
            description = "Returns every leave policy across all organizations, regardless of active status."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policies returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant")
    })
    public ResponseEntity<List<LeavePolicyDto>> getAllPolicies() {
        return ResponseEntity.status(HttpStatus.OK).body(policyService.getAllPolicies());
    }

    @GetMapping("/organization/{organizationId}")
    @PreAuthorize("@orgSecurity.isCurrentTenant(#organizationId) "
            + "and @orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "List an organization's leave policies",
            description = "Returns the leave policies configured for the organization. Active policies "
                    + "only by default; pass includeInactive=true to also return deactivated ones."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policies returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is not entitled to the organization named, or lacks the required role in it"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No organization with the given id")
    })
    public ResponseEntity<List<LeavePolicyDto>> getPoliciesByOrganization(
            @PathVariable Long organizationId,
            @RequestParam(required = false, defaultValue = "false") Boolean includeInactive) {
        if (includeInactive) {
            return ResponseEntity.ok(policyService.getAllPoliciesByOrganization(organizationId));
        }
        return ResponseEntity.ok(policyService.getPoliciesByOrganization(organizationId));
    }

    @GetMapping("/employee/{employeeId}")
    @PreAuthorize("@orgSecurity.isSelfOrHasAnyOrgRole(#employeeId, 'system-admin', 'hr-admin')")
    @Operation(
            summary = "List policies applicable to an employee",
            description = "Returns the leave policies the employee is eligible for, filtered by their "
                    + "organization, gender and length of service against each policy's rules."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Applicable policies returned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller is neither the employee identified by the id nor a holder of the system-admin or hr-admin role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id")
    })
    public ResponseEntity<List<LeavePolicyDto>> getApplicablePoliciesForEmployee(
            @PathVariable Long employeeId) {
        return ResponseEntity.ok(policyService.getApplicablePoliciesForEmployee(employeeId));
    }

    @PatchMapping("/{policyId}")
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Partially update a leave policy",
            description = "Applies the given field updates to the policy and returns the updated record."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "One of the update fields failed validation"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave policy with the given id")
    })
    public ResponseEntity<LeavePolicyDto> updatePolicy(
            @PathVariable Long policyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(schema = @Schema(implementation = LeavePolicyUpdateFieldsDto.class)))
            @RequestBody Map<String, Object> updates) {
        return ResponseEntity.ok(policyService.updatePolicy(policyId, updates));
    }

    @DeleteMapping("/{policyId}")
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Deactivate a leave policy",
            description = "Marks the policy inactive so it stops applying to new leave requests. Existing "
                    + "balances and requests already raised against it are unaffected."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy deactivated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave policy with the given id")
    })
    public ResponseEntity<ApiResponse> deactivatePolicy(@PathVariable Long policyId) {
        policyService.deactivatePolicy(policyId);
        return ResponseEntity.ok(new ApiResponse("Leave policy deactivated successfully"));
    }

    @PostMapping("/{policyId}/activate")
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    @Operation(
            summary = "Reactivate a leave policy",
            description = "Marks a previously deactivated policy active again so it resumes applying to "
                    + "new leave requests."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Policy activated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave policy with the given id")
    })
    public ResponseEntity<ApiResponse> activatePolicy(@PathVariable Long policyId) {
        policyService.activatePolicy(policyId);
        return ResponseEntity.ok(new ApiResponse("Leave policy activated successfully"));
    }

    /**
     * Copies a policy into another organization.
     *
     * <p>Two organizations are named by a call to this: the one the session is scoped to, which
     * owns the source policy, and the target the copy is written into. The guard used to answer
     * only for the first. The entitlement to the second came from
     * {@code OrganizationRepository.findByIdAndUserEmail}, which establishes an {@code Employee}
     * row in the target and nothing about what the caller may do there, so the guard read as
     * though the role applied to the target when it did not. {@code Organization} is the tenant
     * root, so it implements no {@code TenantScopedEntity} and neither the org filter nor the
     * fail-closed load listener has anything to say about which one is resolved; a caller-named
     * organization has to be answered for in the guard or nowhere.
     *
     * <p>{@code hasAnyOrgRole(#targetOrganizationId, ...)} is the half that does that. It reads
     * the caller's authorities for the organization they named, rather than for the one their
     * session happens to hold, which is all {@code hasAnyOrgRoleForCurrentTenant} can answer for.
     * Both halves are required: the source policy is read out of the current tenant, so a role
     * there is not made redundant by holding one in the target. See #698.
     *
     * <p>What the repaired guard does not fix, and what a reader should know before relying on
     * this endpoint: it cannot currently succeed for any input.
     * {@code OrganizationLookupUnderTheOrgFilterIT} measured the target lookup against a database
     * and the org filter reaches the {@code JOIN o.employees e} inside it, so a target that is not
     * the current tenant resolves nothing and the method raises not-found. Naming the current
     * tenant instead reaches the duplicate-code check, which the source policy's own leave-type
     * code satisfies, so that answers 409. Whether cross-organization duplication is a feature at
     * all is a product decision; if it is, the target resolution and the duplicate-code check both
     * have to become deliberate cross-tenant reads rather than queries that quietly return
     * nothing. Tracked separately.
     *
     * @param policyId The ID of the source policy, read from the caller's own organization.
     * @param targetOrganizationId The organization to copy into, in which the caller must hold
     *                             the same role.
     */
    @PostMapping("/{policyId}/duplicate")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')"
            + " and @orgSecurity.hasAnyOrgRole(#targetOrganizationId, 'system-admin', 'hr-admin')")
    @Operation(
            summary = "Duplicate a leave policy into another organization",
            description = "Copies the policy's quota, accrual and eligibility rules into a new policy owned "
                    + "by the target organization. Returns the newly created policy. The caller must hold "
                    + "the system-admin or hr-admin role in their own organization and in the target."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Policy duplicated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant or in the target organization"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No leave policy or target organization with the given id")
    })
    public ResponseEntity<LeavePolicyDto> duplicatePolicy(
            @PathVariable Long policyId,
            @RequestParam Long targetOrganizationId) {
        LeavePolicyDto duplicated = policyService.duplicatePolicy(policyId, targetOrganizationId);
        return ResponseEntity.status(HttpStatus.CREATED).body(duplicated);
    }
}
