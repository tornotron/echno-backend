package org.tornotron.echno_backend.common.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.response.ApiResponse;
import org.tornotron.echno_backend.common.service.KeycloakGroupService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.employee.EmployeeService;
import org.tornotron.echno_backend.user.User;
import org.tornotron.echno_backend.user.UserRepository;

@Validated
@RestController
@RequestMapping("/api/v1/keycloakGroup/web")
public class KeycloakGroupController {

    private final KeycloakGroupService keycloakGroupService;
    private final UserRepository userRepository;
    private final EmployeeService employeeService;
    private final EmployeeRepository employeeRepository;

    public KeycloakGroupController(KeycloakGroupService keycloakGroupService, UserRepository userRepository, EmployeeService employeeService, EmployeeRepository employeeRepository) {
        this.keycloakGroupService = keycloakGroupService;
        this.userRepository = userRepository;
        this.employeeService = employeeService;
        this.employeeRepository = employeeRepository;
    }

    @GetMapping("/assign/userId/{userId}/organizationId/{organizationId}")
    public ResponseEntity<ApiResponse> assignUserToKeycloakOrg(
            @PathVariable Long userId,
            @PathVariable Long organizationId
    ) {
        // Look up the user by database ID to get their Keycloak ID
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (user.getKeycloakId() == null) {
            throw new IllegalStateException("User does not have a Keycloak ID");
        }

        keycloakGroupService.addUserToOrganization(user.getKeycloakId(), organizationId.toString());
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("User added to keycloak Org"));
    }

    @GetMapping("/assignRole")
    public ResponseEntity<ApiResponse> assignOrgRole(
            @RequestParam Long employeeId,
            @RequestParam String orgRole
    ) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId + " in organization "));

        User user = employee.getUser();
        if (user == null || user.getKeycloakId() == null) {
            throw new IllegalStateException("Employee does not have a Keycloak user");
        }

        employeeService.assignOrgRole(employee.getId(), OrgRole.valueOf(orgRole));
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("User assigned a role"));
    }
}
