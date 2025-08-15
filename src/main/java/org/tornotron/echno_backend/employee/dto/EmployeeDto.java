package org.tornotron.echno_backend.employee.dto;

import lombok.Data;
import org.tornotron.echno_backend.user.enums.UserRole;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class EmployeeDto {
    private Long id;
    private String employeeName;
    private String gender;
    private String phoneNumber;
    private String emailAddress;
    private LocalDateTime dateOfBirth;
    private String bloodGroup;
    private String qualification;
    private List<String> skills;
    private Integer experience;
    private String cvUrl;
    private String emergencyContact;
    private UserRole role;
    private String profilePictureUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
