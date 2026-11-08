package org.tornotron.echno_backend.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.role.DefaultRolePermissions;
import org.tornotron.echno_backend.role.Permission;
import org.tornotron.echno_backend.role.RoleDefinition;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Schema(description = "A role in the organization, built-in or custom, with the permissions it grants here.")
public record RoleDto(
        @Schema(nullable = true, description = "The custom role's id. Null for a built-in role.")
        UUID id,
        @Schema(nullable = true, description = "The built-in role, as used in /employee/web/{id}/roles. "
                + "Null for a custom role.", example = "PROJECT_MANAGER")
        OrgRole builtInRole,
        @Schema(description = "The role's name.", example = "project-manager")
        String name,
        @Schema(nullable = true, description = "What the role is for. Custom roles only.")
        String description,
        @Schema(description = "Whether this is a built-in role.")
        boolean builtIn,
        @Schema(description = "Whether the organization may change what it grants. False only for system-admin.")
        boolean editable,
        @Schema(description = "The permission codes the role grants in this organization, sorted.")
        List<String> permissions) {

    public static RoleDto builtIn(OrgRole role, Collection<Permission> permissions) {
        return new RoleDto(null, role, role.getRoleName(), null, true,
                DefaultRolePermissions.isEditable(role), codes(permissions));
    }

    public static RoleDto custom(RoleDefinition role) {
        List<String> granted = role.getPermissions().entrySet().stream()
                .filter(entry -> Boolean.TRUE.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        return new RoleDto(role.getId(), null, role.getName(), role.getDescription(), false, true, granted);
    }

    private static List<String> codes(Collection<Permission> permissions) {
        return permissions.stream().map(Permission::getCode).sorted().toList();
    }
}
