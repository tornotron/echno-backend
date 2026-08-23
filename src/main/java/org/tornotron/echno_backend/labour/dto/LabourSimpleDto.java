package org.tornotron.echno_backend.labour.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "A terse view of a labour record, returned after creation, without the resolved project relation expanded beyond its name and id.")
@Data
public class LabourSimpleDto {

    @Schema(description = "Database id of the labour record.", example = "18")
    private Long id;
    @Schema(description = "Organization-assigned labour code.", example = "LAB-0087")
    private String labourId;
    @Schema(description = "Id of the organization the worker belongs to.", example = "1")
    private Long organizationId;
    @Schema(description = "Name of the organization the worker belongs to.", example = "Asset Homes")
    private String organizationName;
    @Schema(description = "Full name of the worker.", example = "Suresh Pillai")
    private String fullName;
    @Schema(description = "Contact email address.", example = "suresh.pillai@example.com")
    private String email;
    @Schema(description = "Postal address of the worker.", example = "12 Kaloor, Kochi")
    private String address;
    @Schema(description = "Ten-digit contact phone number.", example = "9847098470")
    private String phoneNumber;
    @Schema(description = "Name of the emergency contact.", example = "Latha Pillai")
    private String emergencyContactName;
    @Schema(description = "Ten-digit phone number of the emergency contact.", example = "9846012345")
    private String emergencyContactNumber;
    @Schema(description = "Trade or skill specialization.", example = "Bar Bender")
    private String specialization;
    @Schema(description = "Employment arrangement.", example = "DAILY_WAGE")
    private String employmentType;
    @Schema(description = "Skill level.", example = "SKILLED")
    private String skillLevel;
    @Schema(description = "Employment status.", example = "ACTIVE")
    private String status;
    @Schema(description = "Date the worker joined.", example = "2026-01-15")
    private LocalDate joiningDate;
    @Schema(description = "Name of the project the worker is currently assigned to.", example = "Marina Heights Towers")
    private String currentProjectName;
    @Schema(description = "Id of the project the worker is currently assigned to.", example = "4")
    private String currentProjectId;
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
