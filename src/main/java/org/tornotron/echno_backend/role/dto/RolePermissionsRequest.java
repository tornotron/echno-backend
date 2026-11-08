package org.tornotron.echno_backend.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

@Schema(description = "The complete set of permissions a built-in role should grant in this organization.")
public record RolePermissionsRequest(
        @Schema(description = "The permission codes, replacing what the role grants now. Sending the role's "
                + "defaults resets it.", example = "[\"project.create\", \"project.update\"]")
        @NotNull(message = "permissions is required")
        Set<String> permissions) {
}
