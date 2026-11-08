package org.tornotron.echno_backend.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.role.Permission;

@Schema(description = "One permission a role can grant.")
public record PermissionDto(
        @Schema(description = "The code guards and role definitions use.", example = "project.create")
        String code,
        @Schema(description = "The module the permission belongs to.", example = "project")
        String module,
        @Schema(description = "What the permission allows.", example = "Create projects")
        String description) {

    public static PermissionDto from(Permission permission) {
        return new PermissionDto(permission.getCode(), permission.getModule(), permission.getDescription());
    }
}
