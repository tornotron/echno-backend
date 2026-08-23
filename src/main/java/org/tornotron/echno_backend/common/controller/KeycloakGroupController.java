package org.tornotron.echno_backend.common.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
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
@Tag(
        name = "Keycloak Groups",
        description = "Bridges the platform's users and employees to Keycloak's organization group and "
                + "role model: adding a user to the current tenant's Keycloak organization, and "
                + "assigning or removing an employee's org role. Restricted to a system admin."
)
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

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/assign")
    @Operation(
            summary = "Add a user to the tenant's Keycloak organization",
            description = "Adds the given user's Keycloak identity to the current tenant's Keycloak "
                    + "organization group."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User added to the Keycloak organization"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No user with the given id")
    })
    public ResponseEntity<ApiResponse> assignUserToKeycloakOrg(
            @RequestParam Long userId
    ) {
        // Look up the user by database ID to get their Keycloak ID
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + userId));

        if (user.getKeycloakId() == null) {
            throw new IllegalStateException("User does not have a Keycloak ID");
        }

        keycloakGroupService.addUserToOrganization(user.getKeycloakId(), TenantContext.getCurrentOrgId().toString());
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("User added to keycloak Org"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/assignRole")
    @Operation(
            summary = "Assign an org role to an employee",
            description = "Grants the named organization role to the given employee's Keycloak user."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Role assigned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id, or the employee has no Keycloak user")
    })
    public ResponseEntity<ApiResponse> assignOrgRole(
            @RequestParam Long employeeId,
            @RequestParam String orgRole
    ) {
        Employee employee = employeeRepository.findByIdAndOrganizationIdWithUser(employeeId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId + " in organization "));

        User user = employee.getUser();
        if (user == null || user.getKeycloakId() == null) {
            throw new IllegalStateException("Employee does not have a Keycloak user");
        }

        employeeService.assignOrgRole(employee.getId(), OrgRole.valueOf(orgRole));
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("User assigned a role"));
    }

    @PreAuthorize("@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')")
    @PostMapping("/unassignRole")
    @Operation(
            summary = "Remove an org role from an employee",
            description = "Revokes the named organization role from the given employee's Keycloak user."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Role unassigned"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Caller lacks the required role in the current tenant"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "No employee with the given id, or the employee has no Keycloak user")
    })
    public ResponseEntity<ApiResponse> unassignOrgRole(
            @RequestParam Long employeeId,
            @RequestParam String orgRole
    ) {
        Employee employee = employeeRepository.findByIdAndOrganizationIdWithUser(employeeId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found with id: " + employeeId + " in organization"));

        User user = employee.getUser();
        if (user == null || user.getKeycloakId() == null) {
            throw new IllegalStateException("Employee does not have a Keycloak user");
        }

        employeeService.removeOrgRole(employee.getId(), OrgRole.valueOf(orgRole));
        return ResponseEntity.status(HttpStatus.OK).body(new ApiResponse("Role unassigned from user"));
    }
}
