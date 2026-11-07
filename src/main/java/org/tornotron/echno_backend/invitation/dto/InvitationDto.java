package org.tornotron.echno_backend.invitation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;
import org.tornotron.echno_backend.invitation.InvitationStage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "An invitation to join the organization, and where it stands.")
public record InvitationDto(
        UUID id,
        String email,
        @Schema(description = "PENDING until accepted, revoked or expired. ACCEPTED_BY_ANOTHER_ADDRESS means the "
                + "provider let a different address accept, so nobody was added.")
        InvitationStage status,
        @Schema(description = "When the link stops working. Resending keeps it; resending an expired invitation "
                + "sends a new link with a new expiry.")
        Instant expiresAt,
        @Schema(description = "When the provider sent the link. Absent while it waits to be sent.", nullable = true)
        Instant sentAt,
        @Schema(nullable = true)
        Instant acceptedAt,
        @Schema(nullable = true)
        Instant revokedAt,
        Instant createdAt,
        @Schema(description = "The Echno user who sent it. Absent for one the system created.", nullable = true)
        Long invitedByUserId,
        List<InvitationRoleDto> roles,
        EmployeeJoinOrgDto employee) {
}
