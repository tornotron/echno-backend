package org.tornotron.echno_backend.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

@Schema(description = "A custom role as created or replaced.")
public record CustomRoleRequest(
        @Schema(description = "The role's name, unique in the organization and not the name of a built-in role.",
                example = "Site Accountant")
        @NotBlank(message = "name is required")
        @Size(max = 100, message = "name must be at most 100 characters")
        String name,
        @Schema(nullable = true, description = "What the role is for.", example = "Records site expenses")
        @Size(max = 500, message = "description must be at most 500 characters")
        String description,
        @Schema(description = "The permission codes the role grants. GET /api/v1/roles/web/permissions lists them.",
                example = "[\"project.update\", \"project.manage-team\"]")
        @NotNull(message = "permissions is required")
        Set<String> permissions) {
}
