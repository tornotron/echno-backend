package org.tornotron.echno_backend.invitation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.tornotron.echno_backend.employee.dto.EmployeeJoinOrgDto;

import java.util.List;

@Schema(description = "An invitation to join the caller's organization.")
public record InvitationCreationDto(
        @Schema(description = "The address the link goes to. Only this exact address can accept it.",
                example = "ravi.kumar@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "email is required")
        @Email(message = "email must be an email address")
        @Size(max = 320, message = "email must be at most 320 characters")
        String email,
        @Schema(description = "Roles the person gets on acceptance. Each is checked as an assignment by the "
                + "caller would be.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "roles is required")
        @Size(max = 50, message = "at most 50 roles")
        List<@Valid InvitationRoleDto> roles,
        @Schema(description = "The employee details the join form collects. The name, contact address and "
                + "phone come from the person's account at acceptance.", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "employee is required")
        @Valid
        EmployeeJoinOrgDto employee) {
}
