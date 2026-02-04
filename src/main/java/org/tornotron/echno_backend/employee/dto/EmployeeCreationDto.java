package org.tornotron.echno_backend.employee.dto;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EmployeeCreationDto {

    @NotBlank(message = "designation is required")
    @Size(min = 3, max = 50, message = "designation must be between 3 and 50 characters")
    private String designation;

    @NotBlank(message = "department is required")
    @Size(min = 3, max = 50, message = "department must be between 3 and 50 characters")
    private String department;

    private LocalDateTime joiningDate;

    private Double salary;

    private Long managerId;

    private String shiftTiming;

    @NotBlank(message = "status is required")
    @Size(min = 3, max = 50, message = "status must be between 3 and 50 characters")
    @Enumerated(EnumType.STRING)
    private String status;

    @NotBlank(message = "employeeName is required")
    @Size(min = 3,max = 50,message = "employeeName must be between 3 and 50 characters")
    private String employeeName;

    @NotBlank(message = "message is required")
    @Size(min = 3,max = 10,message = "gender must be between 3 and 10 characters")
    private String gender;

    @Size(max = 255, message = "address must be at most 255 characters")
    private String address;

    @NotBlank(message = "phoneNumber is required")
    @Size(min = 10,max = 10,message = "phoneNumber must be of 10 characters")
    private String phoneNumber;

    @NotBlank(message = "emailAddress is required")
    @Size(max = 255)
    @Pattern(
            regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$",
            message = "Invalid email address format"
    )
    private String emailAddress;

    @NotNull(message = "dateOfBirth is required")
    private LocalDateTime dateOfBirth;

    @Size(max = 255, message = "organizationName must be at most 255 characters")
    private String organizationName;

    private boolean isManager;
}
