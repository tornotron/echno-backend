package org.tornotron.echno_backend.modules.access.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantsRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleCreatedDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleIdsRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserRolesRequest;

/**
 * What the Roles &amp; Permissions screen changes: an organization's roles, their module
 * permissions and grantable roles, and the roles each person holds.
 *
 * <p>Every change needs {@code access:edit}, and {@link AccessAdminWriter} then applies the write
 * rules: a caller changes only what their own roles may assign and gives only what they hold,
 * system roles are fixed, the baseline's grants need {@code access:edit-employee-role}, nobody
 * changes their own roles or grants, and the organization keeps an administrator. A change answers
 * with no body; the screen reads the role or person again.
 */
@RestController
@RequestMapping("/api/v1/access/web")
@Tag(
        name = "Access Administration (Web)",
        description = "The Roles & Permissions screen: the areas and actions roles can grant, and the "
                + "organization's roles and people's access."
)
public class AccessAdminWriteControllerWeb {

    static final String EDIT_GUARD = "@access.can('access', 'edit')";

    private final AccessAdminWriter writer;

    public AccessAdminWriteControllerWeb(AccessAdminWriter writer) {
        this.writer = writer;
    }

    @PostMapping("/roles")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Create a role",
            description = "A role with no grants. The System Admin may assign it, and so may each role the "
                    + "caller holds that already assigns others."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The new role"),
            @ApiResponse(responseCode = "400", description = "A field failed validation"),
            @ApiResponse(responseCode = "403", description = "Caller may not edit roles and permissions"),
            @ApiResponse(responseCode = "409", description = "A role of that name, ignoring case, already exists")
    })
    public ResponseEntity<AccessRoleCreatedDto> createAccessRole(@Valid @RequestBody AccessRoleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(writer.createRole(request.name(), request.subtitle(), request.description()));
    }

    @PutMapping("/roles/{roleId}")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Rename a role",
            description = "Changes a role's name, subtitle and description. Its slug never changes. System "
                    + "roles are never renamed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Changed"),
            @ApiResponse(responseCode = "400", description = "A field failed validation"),
            @ApiResponse(responseCode = "403", description = "Caller may not edit roles, or their roles may not assign this one"),
            @ApiResponse(responseCode = "404", description = "No such role in this organization"),
            @ApiResponse(responseCode = "409", description = "Another role has that name, ignoring case"),
            @ApiResponse(responseCode = "422", description = "The role is a system role")
    })
    public ResponseEntity<Void> updateAccessRole(@PathVariable UUID roleId, @Valid @RequestBody AccessRoleRequest request) {
        writer.updateRole(roleId, request.name(), request.subtitle(), request.description());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/roles/{roleId}/grants")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Replace a role's module permissions",
            description = "The whole set of grants the role should hold. Any action brings read on its area "
                    + "with the same scope. A grant being added must be on an action the catalog marks enforced, "
                    + "and must cover ALL records unless the area is scope aware. The caller gives or takes only "
                    + "grants they hold. The baseline role's grants need access:edit-employee-role and never "
                    + "include the access area; System Admin's are fixed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Changed; the role's holders see it on their next request"),
            @ApiResponse(responseCode = "400", description = "A permission or scope is not offered, or the baseline was "
                    + "given a grant on the access area"),
            @ApiResponse(responseCode = "403", description = "Caller may not edit this role, holds it, or does not hold a "
                    + "grant they tried to give or take"),
            @ApiResponse(responseCode = "404", description = "No such role in this organization"),
            @ApiResponse(responseCode = "422", description = "The role is System Admin")
    })
    public ResponseEntity<Void> replaceAccessRoleGrants(@PathVariable UUID roleId,
                                                        @Valid @RequestBody AccessGrantsRequest request) {
        writer.replaceRoleGrants(roleId, request.grants());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/roles/{roleId}/grantable-roles")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Replace the roles a role's holders may assign",
            description = "The whole set of roles the role's holders may give others. The caller lists, or takes "
                    + "off the list, only roles they may assign themselves. System roles' lists are fixed, and "
                    + "the baseline is never listed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Changed"),
            @ApiResponse(responseCode = "400", description = "The baseline role was listed"),
            @ApiResponse(responseCode = "403", description = "Caller may not edit this role, holds it, or may not assign a "
                    + "role they listed or took off"),
            @ApiResponse(responseCode = "404", description = "A role is not in this organization"),
            @ApiResponse(responseCode = "422", description = "The role is a system role")
    })
    public ResponseEntity<Void> replaceAccessRoleGrantableRoles(@PathVariable UUID roleId,
                                                                @Valid @RequestBody AccessRoleIdsRequest request) {
        writer.replaceGrantableRoles(roleId, request.roleIds());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/roles/{roleId}")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Delete a role",
            description = "Deletes a role nobody holds. System roles, and roles that stand in for an old role "
                    + "while the old checks read it, stay. A deleted starting role is not copied back."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "403", description = "Caller may not edit roles, or their roles may not assign this one"),
            @ApiResponse(responseCode = "404", description = "No such role in this organization"),
            @ApiResponse(responseCode = "422", description = "The role is a system role, stands in for an old role, "
                    + "or is held by someone")
    })
    public ResponseEntity<Void> deleteAccessRole(@PathVariable UUID roleId) {
        writer.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{employeeId}/roles")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Replace a person's roles",
            description = "The whole set of roles the person should hold, each on a project or across the "
                    + "organization. Every role added or taken must be one the caller's roles may assign. Nobody "
                    + "changes their own roles, the organization's last active System Admin keeps the role, and "
                    + "a role that stands in for an old role takes no project yet."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Changed; the person sees it on their next request"),
            @ApiResponse(responseCode = "400", description = "The baseline role was listed, a role that stands in for an "
                    + "old role was given a project, or the change would leave no System Admin"),
            @ApiResponse(responseCode = "403", description = "Caller may not edit roles, changed their own, or may not "
                    + "assign a role they gave or took"),
            @ApiResponse(responseCode = "404", description = "The person, a role or a project is not in this organization")
    })
    public ResponseEntity<Void> replaceAccessUserRoles(@PathVariable Long employeeId,
                                                       @Valid @RequestBody AccessUserRolesRequest request) {
        writer.replaceUserRoles(employeeId, request.roles());
        return ResponseEntity.noContent().build();
    }
}
