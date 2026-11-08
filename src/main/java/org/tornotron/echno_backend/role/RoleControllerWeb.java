package org.tornotron.echno_backend.role;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.role.dto.CustomRoleRequest;
import org.tornotron.echno_backend.role.dto.EmployeePermissionsDto;
import org.tornotron.echno_backend.role.dto.PermissionDto;
import org.tornotron.echno_backend.role.dto.RoleDto;
import org.tornotron.echno_backend.role.dto.RoleMemberDto;
import org.tornotron.echno_backend.role.dto.RolePermissionsRequest;

import java.util.List;
import java.util.UUID;

/**
 * Roles and permissions for the current organization.
 *
 * <p>Any member reads them, because a client needs to know what the caller may do. Only a
 * system-admin changes them. That stays a role check rather than a permission on purpose: a
 * permission to manage roles would let whoever holds it grant themselves every other one.
 */
@RestController
@RequestMapping("/api/v1/roles/web")
@Tag(name = "Roles and permissions", description = "The permissions there are, the organization's roles "
        + "(built-in and custom) with what each grants, and who holds the custom ones. Members read; a "
        + "system admin changes what a role grants, creates and deletes custom roles, and assigns them.")
public class RoleControllerWeb {

    static final String READ_GUARD = "@orgSecurity.isMemberOfCurrentTenant()";
    static final String MANAGE_GUARD = "@orgSecurity.hasAnyOrgRoleForCurrentTenant('system-admin')";

    private final RoleService service;

    public RoleControllerWeb(RoleService service) {
        this.service = service;
    }

    @GetMapping("/permissions")
    @PreAuthorize(READ_GUARD)
    @Operation(summary = "List all permissions", description = "Every permission a role can grant, with its module.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Permissions returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<List<PermissionDto>> readPermissions() {
        return ResponseEntity.ok(service.catalogue());
    }

    @GetMapping
    @PreAuthorize(READ_GUARD)
    @Operation(summary = "List roles", description = "The built-in roles, then the organization's custom roles, "
            + "each with the permissions it grants in this organization.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<List<RoleDto>> readRoles() {
        return ResponseEntity.ok(service.listRoles());
    }

    @GetMapping("/me")
    @PreAuthorize(READ_GUARD)
    @Operation(summary = "List my permissions", description = "The permission codes the caller holds in the "
            + "current organization, from all their roles.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Permission codes returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant")
    })
    public ResponseEntity<List<String>> readMyPermissions() {
        return ResponseEntity.ok(service.myPermissions());
    }

    @GetMapping("/employees/{employeeId}")
    @PreAuthorize(READ_GUARD)
    @Operation(summary = "List an employee's roles", description = "The built-in and custom roles the employee "
            + "holds in this organization, each with what it grants.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Roles returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No employee with the given id in this organization")
    })
    public ResponseEntity<List<RoleDto>> readRolesOfEmployee(@PathVariable Long employeeId) {
        return ResponseEntity.ok(service.rolesOfEmployee(employeeId));
    }

    @GetMapping("/employees/{employeeId}/permissions")
    @PreAuthorize(READ_GUARD)
    @Operation(summary = "List an employee's permissions", description = "The permissions granted to the "
            + "employee directly, and every permission they hold here once their roles are added in.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Permissions returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No employee with the given id in this organization")
    })
    public ResponseEntity<EmployeePermissionsDto> readPermissionsOfEmployee(@PathVariable Long employeeId) {
        return ResponseEntity.ok(service.permissionsOfEmployee(employeeId));
    }

    @PutMapping("/employees/{employeeId}/permissions/{permission}")
    @PreAuthorize(MANAGE_GUARD)
    @Operation(summary = "Grant an employee a permission directly", description = "Gives the employee one "
            + "permission without a role, on top of what their roles grant. Granting it again changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Permission granted; the employee's permissions returned"),
            @ApiResponse(responseCode = "400", description = "An unknown permission code"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role"),
            @ApiResponse(responseCode = "404", description = "No employee with the given id in this organization")
    })
    public ResponseEntity<EmployeePermissionsDto> grantDirect(@PathVariable Long employeeId,
                                                              @PathVariable String permission) {
        return ResponseEntity.ok(service.grantDirect(employeeId, permission));
    }

    @DeleteMapping("/employees/{employeeId}/permissions/{permission}")
    @PreAuthorize(MANAGE_GUARD)
    @Operation(summary = "Revoke a permission granted directly", description = "Takes away a permission the "
            + "employee was granted directly. One their roles grant stays; revoking one they were not granted "
            + "changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Permission revoked; the employee's permissions returned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role"),
            @ApiResponse(responseCode = "404", description = "No employee with the given id in this organization")
    })
    public ResponseEntity<EmployeePermissionsDto> revokeDirect(@PathVariable Long employeeId,
                                                               @PathVariable String permission) {
        return ResponseEntity.ok(service.revokeDirect(employeeId, permission));
    }

    @PutMapping("/built-in/{role}/permissions")
    @PreAuthorize(MANAGE_GUARD)
    @Operation(summary = "Set what a built-in role grants", description = "Replaces the permissions the built-in "
            + "role grants in this organization. Sending the role's defaults resets it. system-admin cannot be "
            + "changed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role updated"),
            @ApiResponse(responseCode = "400", description = "An unknown permission code, or the role is system-admin"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role")
    })
    public ResponseEntity<RoleDto> updateBuiltInPermissions(@PathVariable OrgRole role,
                                                            @Valid @RequestBody RolePermissionsRequest request) {
        return ResponseEntity.ok(service.setBuiltInPermissions(role, request.permissions()));
    }

    @PostMapping("/custom")
    @PreAuthorize(MANAGE_GUARD)
    @Operation(summary = "Create a custom role", description = "Adds a role with a name, a description and the "
            + "permissions it grants. Nobody holds it until it is assigned.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Role created"),
            @ApiResponse(responseCode = "400", description = "A field failed validation, an unknown permission code, "
                    + "or the name of a built-in role"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role"),
            @ApiResponse(responseCode = "409", description = "A role with that name already exists")
    })
    public ResponseEntity<RoleDto> createCustomRole(@Valid @RequestBody CustomRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCustomRole(request));
    }

    @PutMapping("/custom/{roleId}")
    @PreAuthorize(MANAGE_GUARD)
    @Operation(summary = "Change a custom role", description = "Replaces the role's name, description and "
            + "permissions. Everyone holding it is affected on their next request.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role changed"),
            @ApiResponse(responseCode = "400", description = "A field failed validation, an unknown permission code, "
                    + "or the name of a built-in role"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role"),
            @ApiResponse(responseCode = "404", description = "No custom role with that id in this organization"),
            @ApiResponse(responseCode = "409", description = "Another role already has that name")
    })
    public ResponseEntity<RoleDto> updateCustomRole(@PathVariable UUID roleId,
                                                    @Valid @RequestBody CustomRoleRequest request) {
        return ResponseEntity.ok(service.updateCustomRole(roleId, request));
    }

    @DeleteMapping("/custom/{roleId}")
    @PreAuthorize(MANAGE_GUARD)
    @Operation(summary = "Delete a custom role", description = "Deletes the role. Everyone holding it loses it.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Role deleted"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role"),
            @ApiResponse(responseCode = "404", description = "No custom role with that id in this organization")
    })
    public ResponseEntity<Void> deleteCustomRole(@PathVariable UUID roleId) {
        service.deleteCustomRole(roleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/custom/{roleId}/members")
    @PreAuthorize(READ_GUARD)
    @Operation(summary = "List who holds a custom role", description = "The employees holding the role, by name.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Members returned"),
            @ApiResponse(responseCode = "403", description = "Caller is not a member of the current tenant"),
            @ApiResponse(responseCode = "404", description = "No custom role with that id in this organization")
    })
    public ResponseEntity<List<RoleMemberDto>> readMembers(@PathVariable UUID roleId) {
        return ResponseEntity.ok(service.members(roleId));
    }

    @PutMapping("/custom/{roleId}/members/{employeeId}")
    @PreAuthorize(MANAGE_GUARD)
    @Operation(summary = "Give an employee a custom role", description = "Assigns the role. Assigning it again "
            + "changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Role assigned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role"),
            @ApiResponse(responseCode = "404", description = "No such custom role or employee in this organization")
    })
    public ResponseEntity<Void> assign(@PathVariable UUID roleId, @PathVariable Long employeeId) {
        service.assign(roleId, employeeId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/custom/{roleId}/members/{employeeId}")
    @PreAuthorize(MANAGE_GUARD)
    @Operation(summary = "Take a custom role away from an employee", description = "Unassigns the role. "
            + "Unassigning one the employee does not hold changes nothing.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Role unassigned"),
            @ApiResponse(responseCode = "403", description = "Caller lacks the system-admin role"),
            @ApiResponse(responseCode = "404", description = "No such custom role or employee in this organization")
    })
    public ResponseEntity<Void> unassign(@PathVariable UUID roleId, @PathVariable Long employeeId) {
        service.unassign(roleId, employeeId);
        return ResponseEntity.noContent().build();
    }
}
