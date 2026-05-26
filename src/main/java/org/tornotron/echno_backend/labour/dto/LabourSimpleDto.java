package org.tornotron.echno_backend.labour.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class LabourSimpleDto {

    private Long id;
    private String labourId;
    private Long organizationId;
    private String organizationName;
    private String fullName;
    private String email;
    private String address;
    private String phoneNumber;
    private String emergencyContactName;
    private String emergencyContactNumber;
    private String specialization;
    private String employmentType;
    private String skillLevel;
    private String status;
    private LocalDate joiningDate;
    private String currentProjectName;
    private String currentProjectId;
    private BigDecimal dailyRate;
    private BigDecimal overTimeRate;
    private String bankAccountNumber;
    private String bankName;
    private String ifscCode;
    private String additionalNotes;
}
