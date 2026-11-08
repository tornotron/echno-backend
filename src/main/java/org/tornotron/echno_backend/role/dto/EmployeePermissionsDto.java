package org.tornotron.echno_backend.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "What one employee may do in the organization, and which of it was granted to them directly.")
public record EmployeePermissionsDto(
        @Schema(description = "The employee's id.") Long employeeId,
        @Schema(description = "Permission codes granted to the employee directly, without a role, sorted.")
        List<String> direct,
        @Schema(description = "Every permission code the employee holds here: their roles' and their direct "
                + "grants together, sorted.")
        List<String> effective) {
}
