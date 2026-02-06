package org.tornotron.echno_backend.leave;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.leave.dto.LeavePolicyCreationDto;
import org.tornotron.echno_backend.leave.dto.LeavePolicyDto;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/leave-policies/web")
@Validated
public class LeavePolicyControllerWeb {

    private final LeavePolicyService policyService;

    public LeavePolicyControllerWeb(LeavePolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
//    @PreAuthorize("hasAuthority('leave:admin')")
    public ResponseEntity<LeavePolicyDto> createPolicy(
            @Valid @RequestBody LeavePolicyCreationDto dto) {
        LeavePolicyDto created = policyService.createPolicy(dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/policy")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<LeavePolicyDto> getPolicy(@RequestParam Long policyId) {
        return ResponseEntity.ok(policyService.getPolicy(policyId));
    }

    @GetMapping
    public ResponseEntity<List<LeavePolicyDto>> getAllPolicies() {
        return ResponseEntity.status(HttpStatus.OK).body(policyService.getAllPolicies());
    }

    @GetMapping("/organization")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeavePolicyDto>> getPoliciesByOrganization(
            @RequestParam Long organizationId,
            @RequestParam(required = false, defaultValue = "false") Boolean includeInactive) {
        if (includeInactive) {
            return ResponseEntity.ok(policyService.getAllPoliciesByOrganization(organizationId));
        }
        return ResponseEntity.ok(policyService.getPoliciesByOrganization(organizationId));
    }

    @GetMapping("/employee")
//    @PreAuthorize("hasAuthority('leave:read') or hasAuthority('leave:admin')")
    public ResponseEntity<List<LeavePolicyDto>> getApplicablePoliciesForEmployee(
            @RequestParam Long employeeId) {
        return ResponseEntity.ok(policyService.getApplicablePoliciesForEmployee(employeeId));
    }

    @PatchMapping("/update")
//    @PreAuthorize("hasAuthority('leave:admin')")
    public ResponseEntity<LeavePolicyDto> updatePolicy(
            @RequestParam Long policyId,
            @RequestBody Map<String, Object> updates) {
        return ResponseEntity.ok(policyService.updatePolicy(policyId, updates));
    }

    @DeleteMapping("/deactivate")
//    @PreAuthorize("hasAuthority('leave:admin')")
    public ResponseEntity<ApiResponse> deactivatePolicy(@RequestParam Long policyId) {
        policyService.deactivatePolicy(policyId);
        return ResponseEntity.ok(new ApiResponse("Leave policy deactivated successfully"));
    }

    @PostMapping("/activate")
//    @PreAuthorize("hasAuthority('leave:admin')")
    public ResponseEntity<ApiResponse> activatePolicy(@RequestParam Long policyId) {
        policyService.activatePolicy(policyId);
        return ResponseEntity.ok(new ApiResponse("Leave policy activated successfully"));
    }

    @PostMapping("/duplicate")
//    @PreAuthorize("hasAuthority('leave:admin')")
    public ResponseEntity<LeavePolicyDto> duplicatePolicy(
            @RequestParam Long policyId,
            @RequestParam Long targetOrganizationId) {
        LeavePolicyDto duplicated = policyService.duplicatePolicy(policyId, targetOrganizationId);
        return ResponseEntity.status(HttpStatus.CREATED).body(duplicated);
    }
}
