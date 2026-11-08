package org.tornotron.echno_backend.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.tornotron.echno_backend.employee.Employee;

@Schema(description = "An employee holding a custom role.")
public record RoleMemberDto(
        @Schema(description = "The employee's id.") Long employeeId,
        @Schema(description = "The employee's name.") String employeeName) {

    public static RoleMemberDto from(Employee employee) {
        return new RoleMemberDto(employee.getId(), employee.getEmployeeName());
    }
}
