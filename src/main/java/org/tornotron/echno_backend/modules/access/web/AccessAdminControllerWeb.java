package org.tornotron.echno_backend.modules.access.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.api.AccessSubject;
import org.tornotron.echno_backend.modules.access.guard.CurrentAccessSubject;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalog;
import org.tornotron.echno_backend.modules.access.web.dto.AccessCatalogDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGrantsRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGroupDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessGroupRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessMembersRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleIdsRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleRequest;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserDto;

/**
 * The Roles &amp; Permissions screen: the catalog it draws the matrix from, and the organization's
 * roles, groups and people.
 *
 * <p>Reading needs {@code access:view}; creating, changing and deleting need {@code access:create},
 * {@code access:edit} and {@code access:delete}. A write is further limited to the roles the
 * caller's roles may assign and the grants the caller holds (see {@link AccessAdminWriter}).
 * Everything here is the organization the request is scoped to.
 */
@RestController
@RequestMapping("/api/v1/access/web")
@Tag(
        name = "Access Administration (Web)",
        description = "The Roles & Permissions screen: the areas and actions roles can grant, and the "
                + "organization's roles, departments and groups, and people's access."
)
public class AccessAdminControllerWeb {

    static final String VIEW_GUARD = "@access.can('access', 'view')";
    static final String CREATE_GUARD = "@access.can('access', 'create')";
    static final String EDIT_GUARD = "@access.can('access', 'edit')";
    static final String DELETE_GUARD = "@access.can('access', 'delete')";

    private final AccessAdminReader reader;
    private final AccessAdminWriter writer;
    private final CurrentAccessSubject current;

    public AccessAdminControllerWeb(AccessAdminReader reader, AccessAdminWriter writer, CurrentAccessSubject current) {
        this.reader = reader;
        this.writer = writer;
        this.current = current;
    }

    @GetMapping("/catalog")
    @PreAuthorize(VIEW_GUARD)
    @Operation(
            summary = "Get the access catalog",
            description = "The sections, areas and actions the matrix shows, and the scopes a grant can "
                    + "carry. A permission is written <area>:<action>, for example wbs:edit."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The catalog"),
            @ApiResponse(responseCode = "403", description = "Caller may not view roles and permissions")
    })
    public AccessCatalogDto getAccessCatalog() {
        return new AccessCatalogDto(
                AccessCatalog.SECTIONS,
                AccessCatalog.areas().stream()
                        .map(area -> new AccessCatalogDto.AreaDto(area.id(), area.label(), area.section(), area.actions()))
                        .toList(),
                Arrays.stream(AccessScope.values()).map(AccessScope::name).toList());
    }

    @GetMapping("/roles")
    @PreAuthorize(VIEW_GUARD)
    @Operation(
            summary = "List roles",
            description = "The organization's roles, the baseline role first: each with its grants, the "
                    + "employees it is assigned to directly, and the roles its holders may assign."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The roles"),
            @ApiResponse(responseCode = "403", description = "Caller may not view roles and permissions")
    })
    public List<AccessRoleDto> listAccessRoles() {
        return reader.roles(TenantContext.getCurrentOrgId());
    }

    @PostMapping("/roles")
    @PreAuthorize(CREATE_GUARD)
    @Operation(
            summary = "Create a role",
            description = "A new role with no grants; its slug is made from its name. The caller's roles "
                    + "that already assign others may assign it too."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The new role"),
            @ApiResponse(responseCode = "400", description = "The name is missing or too long"),
            @ApiResponse(responseCode = "403", description = "Caller may not create roles"),
            @ApiResponse(responseCode = "409", description = "A role with that name already exists")
    })
    public ResponseEntity<AccessRoleDto> createAccessRole(@Valid @RequestBody AccessRoleRequest request) {
        AccessSubject caller = caller();
        UUID id = writer.createRole(caller, request.name(), request.subtitle(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(reader.role(caller.organizationId(), id));
    }

    @PutMapping("/roles/{roleId}")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Change a role's name and description",
            description = "The slug never changes. Only a role the caller's roles may assign, or the "
                    + "baseline role, can be changed."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The role"),
            @ApiResponse(responseCode = "403", description = "Caller may not change this role"),
            @ApiResponse(responseCode = "404", description = "No such role in this organization")
    })
    public AccessRoleDto updateAccessRole(@PathVariable UUID roleId, @Valid @RequestBody AccessRoleRequest request) {
        AccessSubject caller = caller();
        writer.updateRole(caller, roleId, request.name(), request.subtitle(), request.description());
        return reader.role(caller.organizationId(), roleId);
    }

    @PutMapping("/roles/{roleId}/grants")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Replace a role's grants",
            description = "The grants sent become the role's grants. Any action brings view with the same "
                    + "scope; locked grants stay whether sent or not. The caller may give or take only "
                    + "grants they hold, with that scope or ALL. Holders feel the change on their next request."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The role"),
            @ApiResponse(responseCode = "400", description = "A permission is not in the catalog, or a scope is unknown"),
            @ApiResponse(responseCode = "403", description = "Caller may not change this role, or does not hold a grant they sent or removed"),
            @ApiResponse(responseCode = "404", description = "No such role in this organization")
    })
    public AccessRoleDto replaceAccessRoleGrants(@PathVariable UUID roleId,
                                                 @Valid @RequestBody AccessGrantsRequest request) {
        AccessSubject caller = caller();
        writer.replaceRoleGrants(caller, roleId, RequestedGrants.parse(request.grants()));
        return reader.role(caller.organizationId(), roleId);
    }

    @PutMapping("/roles/{roleId}/grantable-roles")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Replace the roles a role's holders may assign",
            description = "The caller may add or take off only roles they may assign themselves. The "
                    + "baseline role is never listed, since everyone holds it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The role"),
            @ApiResponse(responseCode = "400", description = "The baseline role was listed"),
            @ApiResponse(responseCode = "403", description = "Caller may not change this role, or may not assign a role listed or removed"),
            @ApiResponse(responseCode = "404", description = "No such role in this organization")
    })
    public AccessRoleDto replaceAccessRoleGrantableRoles(@PathVariable UUID roleId,
                                                         @Valid @RequestBody AccessRoleIdsRequest request) {
        AccessSubject caller = caller();
        writer.replaceGrantableRoles(caller, roleId, request.roleIds());
        return reader.role(caller.organizationId(), roleId);
    }

    @DeleteMapping("/roles/{roleId}")
    @PreAuthorize(DELETE_GUARD)
    @Operation(
            summary = "Delete a role",
            description = "Only a role the organization made; the starting roles stay. Its holders lose it "
                    + "on their next request."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "403", description = "Caller may not delete this role"),
            @ApiResponse(responseCode = "404", description = "No such role in this organization"),
            @ApiResponse(responseCode = "422", description = "A starting role cannot be deleted")
    })
    public ResponseEntity<Void> deleteAccessRole(@PathVariable UUID roleId) {
        writer.deleteRole(caller(), roleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/groups")
    @PreAuthorize(VIEW_GUARD)
    @Operation(
            summary = "List departments and groups",
            description = "The organization's departments and groups: each with its members, the roles "
                    + "its members hold through it, and what it grants them directly."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The departments and groups"),
            @ApiResponse(responseCode = "403", description = "Caller may not view roles and permissions")
    })
    public List<AccessGroupDto> listAccessGroups() {
        return reader.groups(TenantContext.getCurrentOrgId());
    }

    @PostMapping("/groups")
    @PreAuthorize(CREATE_GUARD)
    @Operation(
            summary = "Create a department or group",
            description = "A new department or group with no members, roles or grants."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "The new department or group"),
            @ApiResponse(responseCode = "400", description = "The name is missing or the kind is unknown"),
            @ApiResponse(responseCode = "403", description = "Caller may not create departments or groups"),
            @ApiResponse(responseCode = "409", description = "One with that name already exists")
    })
    public ResponseEntity<AccessGroupDto> createAccessGroup(@Valid @RequestBody AccessGroupRequest request) {
        AccessSubject caller = caller();
        UUID id = writer.createGroup(caller, request.kind(), request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(reader.group(caller.organizationId(), id));
    }

    @PutMapping("/groups/{groupId}")
    @PreAuthorize(EDIT_GUARD)
    @Operation(summary = "Change a department or group's name, kind and description")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The department or group"),
            @ApiResponse(responseCode = "400", description = "The name is missing or the kind is unknown"),
            @ApiResponse(responseCode = "403", description = "Caller may not change departments or groups"),
            @ApiResponse(responseCode = "404", description = "No such department or group in this organization"),
            @ApiResponse(responseCode = "409", description = "Another one has that name")
    })
    public AccessGroupDto updateAccessGroup(@PathVariable UUID groupId, @Valid @RequestBody AccessGroupRequest request) {
        AccessSubject caller = caller();
        writer.updateGroup(caller, groupId, request.kind(), request.name(), request.description());
        return reader.group(caller.organizationId(), groupId);
    }

    @PutMapping("/groups/{groupId}/members")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Replace a department or group's members",
            description = "Joining or leaving gives or takes everything the group holds, so the caller must "
                    + "be able to assign each of its roles and hold each of its grants."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The department or group"),
            @ApiResponse(responseCode = "403", description = "Caller may not change members, or could not give what the group holds"),
            @ApiResponse(responseCode = "404", description = "No such group, or an employee is not in this organization")
    })
    public AccessGroupDto replaceAccessGroupMembers(@PathVariable UUID groupId,
                                                    @Valid @RequestBody AccessMembersRequest request) {
        AccessSubject caller = caller();
        writer.replaceGroupMembers(caller, groupId, request.employeeIds());
        return reader.group(caller.organizationId(), groupId);
    }

    @PutMapping("/groups/{groupId}/roles")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Replace the roles a department or group gives its members",
            description = "The caller may add or take only roles they may assign. The baseline role is never "
                    + "listed, since everyone holds it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The department or group"),
            @ApiResponse(responseCode = "400", description = "The baseline role was listed"),
            @ApiResponse(responseCode = "403", description = "Caller may not assign a role added or taken"),
            @ApiResponse(responseCode = "404", description = "No such group or role in this organization")
    })
    public AccessGroupDto replaceAccessGroupRoles(@PathVariable UUID groupId,
                                                  @Valid @RequestBody AccessRoleIdsRequest request) {
        AccessSubject caller = caller();
        writer.replaceGroupRoles(caller, groupId, request.roleIds());
        return reader.group(caller.organizationId(), groupId);
    }

    @PutMapping("/groups/{groupId}/grants")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Replace what a department or group grants its members",
            description = "Any action brings view with the same scope. The caller may give or take only "
                    + "grants they hold, with that scope or ALL."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The department or group"),
            @ApiResponse(responseCode = "400", description = "A permission is not in the catalog, or a scope is unknown"),
            @ApiResponse(responseCode = "403", description = "Caller does not hold a grant they sent or removed"),
            @ApiResponse(responseCode = "404", description = "No such department or group in this organization")
    })
    public AccessGroupDto replaceAccessGroupGrants(@PathVariable UUID groupId,
                                                   @Valid @RequestBody AccessGrantsRequest request) {
        AccessSubject caller = caller();
        writer.replaceGroupGrants(caller, groupId, RequestedGrants.parse(request.grants()));
        return reader.group(caller.organizationId(), groupId);
    }

    @DeleteMapping("/groups/{groupId}")
    @PreAuthorize(DELETE_GUARD)
    @Operation(
            summary = "Delete a department or group",
            description = "Its members lose what it gave them, so the caller must be able to give all of it."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deleted"),
            @ApiResponse(responseCode = "403", description = "Caller may not delete it, or could not give what it holds"),
            @ApiResponse(responseCode = "404", description = "No such department or group in this organization")
    })
    public ResponseEntity<Void> deleteAccessGroup(@PathVariable UUID groupId) {
        writer.deleteGroup(caller(), groupId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/users")
    @PreAuthorize(VIEW_GUARD)
    @Operation(
            summary = "List people's access",
            description = "Every employee of the organization with what is given to them directly: their "
                    + "roles, their departments and groups, and their own grants, and whether their status "
                    + "still gives them access."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The people and their access"),
            @ApiResponse(responseCode = "403", description = "Caller may not view roles and permissions")
    })
    public List<AccessUserDto> listAccessUsers() {
        return reader.users(TenantContext.getCurrentOrgId());
    }

    @PutMapping("/users/{employeeId}/roles")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Replace the roles assigned to a person",
            description = "The roles sent become the person's direct roles; roles they hold through a "
                    + "department or group are not touched. The caller may add or take only roles they may "
                    + "assign. The change applies on the person's next request."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The person's access"),
            @ApiResponse(responseCode = "400", description = "The baseline role was listed"),
            @ApiResponse(responseCode = "403", description = "Caller may not assign a role added or taken"),
            @ApiResponse(responseCode = "404", description = "No such employee or role in this organization")
    })
    public AccessUserDto replaceAccessUserRoles(@PathVariable Long employeeId,
                                                @Valid @RequestBody AccessRoleIdsRequest request) {
        AccessSubject caller = caller();
        writer.replaceUserRoles(caller, employeeId, request.roleIds());
        return reader.user(caller.organizationId(), employeeId);
    }

    @PutMapping("/users/{employeeId}/grants")
    @PreAuthorize(EDIT_GUARD)
    @Operation(
            summary = "Replace what is granted to a person directly",
            description = "On top of their roles and groups. Any action brings view with the same scope. "
                    + "The caller may give or take only grants they hold, with that scope or ALL."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The person's access"),
            @ApiResponse(responseCode = "400", description = "A permission is not in the catalog, or a scope is unknown"),
            @ApiResponse(responseCode = "403", description = "Caller does not hold a grant they sent or removed"),
            @ApiResponse(responseCode = "404", description = "No such employee in this organization")
    })
    public AccessUserDto replaceAccessUserGrants(@PathVariable Long employeeId,
                                                 @Valid @RequestBody AccessGrantsRequest request) {
        AccessSubject caller = caller();
        writer.replaceUserGrants(caller, employeeId, RequestedGrants.parse(request.grants()));
        return reader.user(caller.organizationId(), employeeId);
    }

    private AccessSubject caller() {
        return current.resolve().orElseThrow(() -> new AccessDeniedException("Not a member of this organization"));
    }
}
