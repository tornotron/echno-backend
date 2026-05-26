package org.tornotron.echno_backend.labour.dto;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class LabourCreationDto {

    @NotBlank(message = "'LabourID'(String) is required")
    @Size(max = 15,message = "must not exceed 15 characters")
    private String labourID;

    @NotBlank(message = "'fullName'(String) is required")
    @Size(max = 255, message = "must not exceed 255 characters")
    private String fullName;

    @Email
    @Size(max = 255, message = "must not exceed 255 characters")
    private String email;

    @Size(max = 255, message = "must not exceed 255 characters")
    private String address;

    @NotBlank(message = "'phoneNumber'(String) is required")
    @Size(max = 255, message = "must not exceed 255 characters")
    private String phoneNumber;

    @Size(max = 255, message = "must not exceed 255 characters")
    private String emergencyContactName;

    @Size(max = 255, message = "must not exceed 255 characters")
    private String emergencyContactPhone;

    @Size(max = 255, message = "must not exceed 255 characters")
    private String specialization;

    @NotBlank(message = "'employmentType'(String) is required")
    @Size(max = 255, message = "must not exceed 255 characters")
    @Enumerated(EnumType.STRING)
    private String employmentType;

    @NotBlank(message = "'skillLevel'(String) is required")
    @Size(max = 255, message = "must not exceed 255 characters")
    @Enumerated(EnumType.STRING)
    private String skillLevel;

    @NotBlank(message = "'status'(String) is required")
    @Size(max = 255, message = "must not exceed 255 characters")
    @Enumerated(EnumType.STRING)
    private String status;

    @NotNull(message = "'joiningDate'(LocalDate) is required")
    @Past(message = "joiningDate must be in the past")
    private LocalDate joiningDate;

    private Long currentProjectId;

    private BigDecimal dailyRate;

    private BigDecimal overTimeRate;

    private String bankAccountNumber;

    private String bankName;

    private String ifscCode;

    private String additionalNotes;

}
