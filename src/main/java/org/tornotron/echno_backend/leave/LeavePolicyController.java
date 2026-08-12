package org.tornotron.echno_backend.leave;

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

@RestController
@RequestMapping("/api/v1/leave-policies")
@Validated
public class LeavePolicyController {

    private final LeavePolicyService policyService;

    public LeavePolicyController(LeavePolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    public ResponseEntity<LeavePolicyDto> createPolicy(
            @Valid @RequestBody LeavePolicyCreationDto dto) {
        LeavePolicyDto created = policyService.createPolicy(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{policyId}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeavePolicyDto> getPolicy(@PathVariable Long policyId) {
        return ResponseEntity.ok(policyService.getPolicy(policyId));
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeavePolicyDto>> getAllPolicies() {
        return ResponseEntity.status(HttpStatus.OK).body(policyService.getAllPolicies());
    }

    @GetMapping("/organization/{organizationId}")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<List<LeavePolicyDto>> getPoliciesByOrganization(
            @PathVariable Long organizationId,
            @RequestParam(required = false, defaultValue = "false") Boolean includeInactive) {
        if (includeInactive) {
            return ResponseEntity.ok(policyService.getAllPoliciesByOrganization(organizationId));
        }
        return ResponseEntity.ok(policyService.getPoliciesByOrganization(organizationId));
    }

    @GetMapping("/employee/{employeeId}")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.isSelfInCurrentTenant(#employeeId)")
    public ResponseEntity<List<LeavePolicyDto>> getApplicablePoliciesForEmployee(
            @PathVariable Long employeeId) {
        return ResponseEntity.ok(policyService.getApplicablePoliciesForEmployee(employeeId));
    }

    @PatchMapping("/{policyId}")
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeavePolicyDto> updatePolicy(
            @PathVariable Long policyId,
            @RequestBody Map<String, Object> updates) {
        return ResponseEntity.ok(policyService.updatePolicy(policyId, updates));
    }

    @DeleteMapping("/{policyId}")
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<ApiResponse> deactivatePolicy(@PathVariable Long policyId) {
        policyService.deactivatePolicy(policyId);
        return ResponseEntity.ok(new ApiResponse("Leave policy deactivated successfully"));
    }

    @PostMapping("/{policyId}/activate")
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<ApiResponse> activatePolicy(@PathVariable Long policyId) {
        policyService.activatePolicy(policyId);
        return ResponseEntity.ok(new ApiResponse("Leave policy activated successfully"));
    }

    @PostMapping("/{policyId}/duplicate")
//    @PreAuthorize("hasAuthority('leave:admin')")
    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin','hr-admin')")
    public ResponseEntity<LeavePolicyDto> duplicatePolicy(
            @PathVariable Long policyId,
            @RequestParam Long targetOrganizationId) {
        LeavePolicyDto duplicated = policyService.duplicatePolicy(policyId, targetOrganizationId);
        return ResponseEntity.status(HttpStatus.CREATED).body(duplicated);
    }
}
