package org.tornotron.echno_backend.employee.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EmployeeCreationDto {

    @NotBlank
    @Size(min = 3,max = 50,message = "employeeName must be between 3 and 50 characters")
    private String employeeName;

    @NotBlank
    @Size(min = 3,max = 10,message = "gender must be between 3 and 10 characters")
    private String gender;

    @NotBlank
    @Size(min = 10,max = 10,message = "phoneNumber must be of 10 characters")
    private String phoneNumber;

    @NotBlank
    @Size(max = 255)
    @Pattern(
            regexp = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$",
            message = "Invalid email address format"
    )
    private String emailAddress;

    @NotNull(message = "must not be blank")
    private LocalDateTime dateOfBirth;
}
