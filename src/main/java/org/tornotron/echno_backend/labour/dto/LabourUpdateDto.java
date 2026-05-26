package org.tornotron.echno_backend.labour.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.tornotron.echno_backend.labour.enums.EmploymentType;
import org.tornotron.echno_backend.labour.enums.SkillLevel;
import org.tornotron.echno_backend.labour.enums.Status;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class LabourUpdateDto {

    @Size(max = 15, message = "must not exceed 15 characters")
    private String labourID;

    @Size(max = 255, message = "must not exceed 255 characters")
    private String fullName;

    @Email
    @Size(max = 255, message = "must not exceed 255 characters")
    private String email;

    @Size(max = 255, message = "must not exceed 255 characters")
    private String address;

    @Size(max = 255, message = "must not exceed 255 characters")
    private String phoneNumber;

    @Size(max = 255, message = "must not exceed 255 characters")
    private String emergencyContactName;

    @Size(max = 255, message = "must not exceed 255 characters")
    private String emergencyContactPhone;

    @Size(max = 255, message = "must not exceed 255 characters")
    private String specialization;

    private EmploymentType employmentType;

    private SkillLevel skillLevel;

    private Status status;

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
