package org.tornotron.echno_backend.invitation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

@Schema(description = "A role the invited person gets on acceptance, on one project or, with none, across the organization.")
public record InvitationRoleDto(
        @Schema(description = "Id of a role in this organization.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "roleId is required")
        UUID roleId,
        @Schema(description = "Project the role holds on. Absent for a role across the organization.", nullable = true)
        Long projectId) {
}
