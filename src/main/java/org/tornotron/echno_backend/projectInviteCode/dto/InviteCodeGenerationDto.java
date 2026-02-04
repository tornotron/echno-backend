package org.tornotron.echno_backend.projectInviteCode.dto;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;


@Data
public class InviteCodeGenerationDto {

    int maxUses = 1;

    int validityDays = 5;

    private String employeeId;

    private String employeeName;

    private String email;

    private String phone;

    @NotBlank(message = "designation is required")
    @Size(min = 3, max = 50, message = "designation must be between 3 and 50 characters")
    private String designation;

    @NotBlank(message = "department is required")
    @Size(min = 3, max = 50, message = "department must be between 3 and 50 characters")
    private String department;

    private Double salary;

    private Long managerId;

    private String shiftTiming;

    @NotBlank(message = "status is required")
    @Size(min = 3, max = 50, message = "status must be between 3 and 50 characters")
    @Enumerated(EnumType.STRING)
    private String status;

}
