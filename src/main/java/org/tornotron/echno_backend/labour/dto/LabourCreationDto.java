package org.tornotron.echno_backend.labour.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Payload to create a labour record for a worker engaged on daily wage, monthly, "
        + "contract or piece-rate terms.")
@Data
public class LabourCreationDto {

    @Schema(description = "Organization-assigned labour code.", example = "LAB-0087")
    @NotBlank(message = "'LabourID'(String) is required")
    @Size(max = 15,message = "must not exceed 15 characters")
    private String labourID;

    @Schema(description = "Full name of the worker.", example = "Suresh Pillai")
    @NotBlank(message = "'fullName'(String) is required")
    @Size(max = 255, message = "must not exceed 255 characters")
    private String fullName;

    @Schema(description = "Contact email address.", example = "suresh.pillai@example.com")
    @Email
    @Size(max = 255, message = "must not exceed 255 characters")
    private String email;

    @Schema(description = "Postal address of the worker.", example = "12 Kaloor, Kochi")
    @Size(max = 255, message = "must not exceed 255 characters")
    private String address;

    @Schema(description = "Ten-digit contact phone number.", example = "9847098470")
    @NotBlank(message = "'phoneNumber'(String) is required")
    @Size(max = 255, message = "must not exceed 255 characters")
    private String phoneNumber;

    @Schema(description = "Name of the emergency contact.", example = "Latha Pillai")
    @Size(max = 255, message = "must not exceed 255 characters")
    private String emergencyContactName;

    @Schema(description = "Ten-digit phone number of the emergency contact.", example = "9846012345")
    @Size(max = 255, message = "must not exceed 255 characters")
    private String emergencyContactPhone;

    @Schema(description = "Trade or skill specialization.", example = "Bar Bender")
    @Size(max = 255, message = "must not exceed 255 characters")
    private String specialization;

    @Schema(description = "Employment arrangement. One of DAILY_WAGE, MONTHLY, CONTRACT, PIECE_RATE.", example = "DAILY_WAGE")
    @NotBlank(message = "'employmentType'(String) is required")
    @Size(max = 255, message = "must not exceed 255 characters")
    @Enumerated(EnumType.STRING)
    private String employmentType;

    @Schema(description = "Skill level. One of SKILLED, SEMI_SKILLED, HIGHLY_SKILLED, UNSKILLED.", example = "SKILLED")
    @NotBlank(message = "'skillLevel'(String) is required")
    @Size(max = 255, message = "must not exceed 255 characters")
    @Enumerated(EnumType.STRING)
    private String skillLevel;

    @Schema(description = "Employment status. One of ACTIVE, INACTIVE, ON_LEAVE, TERMINATED.", example = "ACTIVE")
    @NotBlank(message = "'status'(String) is required")
    @Size(max = 255, message = "must not exceed 255 characters")
    @Enumerated(EnumType.STRING)
    private String status;

    @Schema(description = "Date the worker joined. Must be in the past.", example = "2026-01-15")
    @NotNull(message = "'joiningDate'(LocalDate) is required")
    @Past(message = "joiningDate must be in the past")
    private LocalDate joiningDate;

    @Schema(description = "Id of the project the worker is currently assigned to.", example = "4")
    private Long currentProjectId;

    @Schema(description = "Daily wage rate.", example = "850.00")
    private BigDecimal dailyRate;

    @Schema(description = "Overtime rate per hour.", example = "120.00")
    private BigDecimal overTimeRate;

    @Schema(description = "Bank account number for wage payment.", example = "50100234567890")
    private String bankAccountNumber;

    @Schema(description = "Name of the bank.", example = "State Bank of India")
    private String bankName;

    @Schema(description = "Bank IFSC code.", example = "SBIN0001234")
    private String ifscCode;

    @Schema(description = "Free-text notes about the worker.", example = "Certified for tower crane rigging support.")
    private String additionalNotes;

}
