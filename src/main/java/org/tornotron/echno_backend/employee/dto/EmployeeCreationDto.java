package org.tornotron.echno_backend.employee.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "Payload to create an employee record with their personal and employment details.")
@Data
public class EmployeeCreationDto {

    @Schema(description = "Job title of the employee.", example = "Site Engineer")
    @NotBlank(message = "designation is required")
    @Size(min = 3, max = 50, message = "designation must be between 3 and 50 characters")
    private String designation;

    @Schema(description = "Department the employee works in.", example = "Civil")
    @NotBlank(message = "department is required")
    @Size(min = 3, max = 50, message = "department must be between 3 and 50 characters")
    private String department;

    @Schema(description = "Date the employee joined.", example = "2026-01-15T00:00:00")
    private LocalDateTime joiningDate;

    @Schema(description = "Organization-assigned employee code.", example = "EMP-0042")
    private String employeeId;

    @Schema(description = "Monthly or annual salary, per organization convention.", example = "65000.0")
    private Double salary;

    @Schema(description = "Id of this employee's reporting manager.", example = "5")
    private Long managerId;

    @Schema(description = "Working shift.", example = "09:00-18:00")
    private String shiftTiming;

    @Schema(description = "Employment status.", example = "active")
    @NotBlank(message = "status is required")
    @Size(min = 3, max = 50, message = "status must be between 3 and 50 characters")
    @Enumerated(EnumType.STRING)
    private String status;

    @Schema(description = "Full name of the employee.", example = "Ravi Kumar")
    @NotBlank(message = "employeeName is required")
    @Size(min = 3,max = 50,message = "employeeName must be between 3 and 50 characters")
    private String employeeName;

    @Schema(description = "Gender of the employee.", example = "male")
    @NotBlank(message = "message is required")
    @Size(min = 3,max = 10,message = "gender must be between 3 and 10 characters")
    private String gender;

    @Schema(description = "Postal address of the employee.", example = "45 Anna Nagar, Chennai")
    @Size(max = 255, message = "address must be at most 255 characters")
    private String address;

    @Schema(description = "Ten-digit contact phone number.", example = "9847012345")
    @NotBlank(message = "phoneNumber is required")
    @Size(min = 10,max = 10,message = "phoneNumber must be of 10 characters")
    private String phoneNumber;

    @Schema(description = "Contact email address.", example = "ravi.kumar@example.com")
    @NotBlank(message = "emailAddress is required")
    @Size(max = 255)
    @Pattern(
            regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$",
            message = "Invalid email address format"
    )
    private String emailAddress;

    @Schema(description = "Date of birth of the employee.", example = "1994-03-22T00:00:00")
    @NotNull(message = "dateOfBirth is required")
    private LocalDateTime dateOfBirth;

    @Schema(description = "Name of the organization the employee belongs to.", example = "Asset Homes")
    @Size(max = 255, message = "organizationName must be at most 255 characters")
    private String organizationName;

    @Schema(description = "Whether the employee is a manager.", example = "false")
    private boolean isManager;
}
