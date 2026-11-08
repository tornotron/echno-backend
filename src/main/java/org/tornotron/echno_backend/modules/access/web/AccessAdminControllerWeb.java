package org.tornotron.echno_backend.modules.access.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.modules.access.api.AccessScope;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalog;
import org.tornotron.echno_backend.modules.access.matrix.catalog.AccessCatalogReader;
import org.tornotron.echno_backend.modules.access.web.dto.AccessCatalogDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessRoleDto;
import org.tornotron.echno_backend.modules.access.web.dto.AccessUserDto;

/**
 * What the Roles &amp; Permissions screen reads: the catalog it draws the matrix from, and the
 * organization's roles and people. Its two tabs are Roles and Users.
 *
 * <p>Reading needs {@code access:read}. Everything here is the organization the request is scoped
 * to; a role or person of another organization reads as absent.
 */
@RestController
@RequestMapping("/api/v1/access/web")
@Tag(
        name = "Access Administration (Web)",
        description = "The Roles & Permissions screen: the areas and actions roles can grant, and the "
                + "organization's roles and people's access."
)
public class AccessAdminControllerWeb {

    static final String READ_GUARD = "@access.can('access', 'read')";

    private final AccessAdminReader reader;
    private final AccessCatalogReader catalog;

    public AccessAdminControllerWeb(AccessAdminReader reader, AccessCatalogReader catalog) {
        this.reader = reader;
        this.catalog = catalog;
    }

    @GetMapping("/catalog")
    @PreAuthorize(READ_GUARD)
    @Operation(
            summary = "Get the access catalog",
            description = "The sections, areas and actions the matrix shows, whether a guard enforces each "
                    + "action, and the scopes a grant can carry. A permission is written <area>:<action>, for "
                    + "example finance:read. Only enforced actions can be granted; the rest are shown as not "
                    + "yet controlled here."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The catalog"),
            @ApiResponse(responseCode = "403", description = "Caller may not read roles and permissions")
    })
    public AccessCatalogDto getAccessCatalog() {
        return new AccessCatalogDto(
                AccessCatalog.SECTIONS,
                catalog.areas(),
                Arrays.stream(AccessScope.values()).map(AccessScope::name).toList());
    }

    @GetMapping("/roles")
    @PreAuthorize(READ_GUARD)
    @Operation(
            summary = "List roles",
            description = "The organization's roles, the baseline role first: each with its grants, the "
                    + "people assigned to it and the roles its holders may assign."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The roles"),
            @ApiResponse(responseCode = "403", description = "Caller may not read roles and permissions")
    })
    public List<AccessRoleDto> listAccessRoles() {
        return reader.roles(TenantContext.getCurrentOrgId());
    }

    @GetMapping("/roles/{roleId}")
    @PreAuthorize(READ_GUARD)
    @Operation(
            summary = "Get a role",
            description = "One role as the list gives it, for the role's details panel: its module "
                    + "permissions and its assigned people."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The role"),
            @ApiResponse(responseCode = "403", description = "Caller may not read roles and permissions"),
            @ApiResponse(responseCode = "404", description = "No such role in this organization")
    })
    public AccessRoleDto getAccessRole(@PathVariable UUID roleId) {
        return reader.role(TenantContext.getCurrentOrgId(), roleId);
    }

    @GetMapping("/users")
    @PreAuthorize(READ_GUARD)
    @Operation(
            summary = "List people's access",
            description = "Every employee of the organization with what is given to them directly: their "
                    + "roles, each on a project or across the organization, their own grants, and whether "
                    + "their status still gives them access."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The people and their access"),
            @ApiResponse(responseCode = "403", description = "Caller may not read roles and permissions")
    })
    public List<AccessUserDto> listAccessUsers() {
        return reader.users(TenantContext.getCurrentOrgId());
    }

    @GetMapping("/users/{employeeId}")
    @PreAuthorize(READ_GUARD)
    @Operation(
            summary = "Get a person's access",
            description = "One employee as the list gives them."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The person and their access"),
            @ApiResponse(responseCode = "403", description = "Caller may not read roles and permissions"),
            @ApiResponse(responseCode = "404", description = "No such employee in this organization")
    })
    public AccessUserDto getAccessUser(@PathVariable Long employeeId) {
        return reader.user(TenantContext.getCurrentOrgId(), employeeId);
    }
}
