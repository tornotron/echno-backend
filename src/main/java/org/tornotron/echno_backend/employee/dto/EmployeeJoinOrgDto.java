package org.tornotron.echno_backend.employee.dto;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EmployeeJoinOrgDto {
//    @NotBlank(message = "designation is required")
    @Size(min = 3, max = 50, message = "designation must be between 3 and 50 characters")
    private String designation;

//    @NotBlank(message = "department is required")
    @Size(min = 3, max = 50, message = "department must be between 3 and 50 characters")
    private String department;

    private LocalDateTime joiningDate;

    private String employeeId;

    private Double salary;

    private Long managerId;

    private String shiftTiming;

    @NotBlank(message = "status is required")
    @Size(min = 3, max = 50, message = "status must be between 3 and 50 characters")
    @Enumerated(EnumType.STRING)
    private String status = "active";

    private String employeeName;

    private String email;

    private String phone;


}
