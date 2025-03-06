package org.tornotron.echno_backend.employee.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EmployeeDto {
    private Long id;
    private String employeeName;
    private String gender;
    private String phoneNumber;
    private String emailAddress;
    private LocalDateTime dateOfBirth;
}
