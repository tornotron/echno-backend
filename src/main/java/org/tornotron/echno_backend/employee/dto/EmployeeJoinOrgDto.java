package org.tornotron.echno_backend.employee.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Employment details supplied when a user joins an organization as an employee.")
@Data
public class EmployeeJoinOrgDto {
//    @NotBlank(message = "designation is required")
    @Schema(description = "Job title within the organization.", example = "Site Engineer")
    @Size(min = 3, max = 50, message = "designation must be between 3 and 50 characters")
    private String designation;

//    @NotBlank(message = "department is required")
    @Schema(description = "Department within the organization.", example = "Civil")
    @Size(min = 3, max = 50, message = "department must be between 3 and 50 characters")
    private String department;

    @Schema(description = "Date the employee joined.", example = "2026-01-15T00:00:00")
    private LocalDateTime joiningDate;

    @Schema(description = "Organization-assigned employee code.", example = "EMP-0042")
    private String employeeId;

    @Schema(description = "Salary of the employee.", example = "65000.0")
    private Double salary;

    @Schema(description = "Id of this employee's reporting manager.", example = "5")
    private Long managerId;

    @Schema(description = "Id of the structured shift timing to assign to the employee.", example = "5")
    private Long shiftTimingId;

    @Schema(description = "Employment status. Defaults to active.", example = "active")
    @NotBlank(message = "status is required")
    @Size(min = 3, max = 50, message = "status must be between 3 and 50 characters")
    @Enumerated(EnumType.STRING)
    private String status = "active";

    @Schema(description = "Full name of the employee.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(description = "Contact email address.", example = "ravi.kumar@example.com")
    private String email;

    @Schema(description = "Contact phone number.", example = "9847012345")
    private String phone;


}
