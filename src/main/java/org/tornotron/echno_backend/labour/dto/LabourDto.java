package org.tornotron.echno_backend.labour.dto;

import lombok.Data;
import org.tornotron.echno_backend.labour.enums.EmploymentType;
import org.tornotron.echno_backend.labour.enums.SkillLevel;
import org.tornotron.echno_backend.labour.enums.Status;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class LabourDto {
    private Long id;
    private String labourID;
    private String fullName;
    private String email;
    private String address;
    private String phoneNumber;
    private String emergencyContactName;
    private String emergencyContactNumber;
    private String specialization;
    private EmploymentType employmentType;
    private SkillLevel skillLevel;
    private Status status;
    private LocalDate joiningDate;
    private BigDecimal dailyRate;
    private BigDecimal overTimeRate;
    private String bankAccountNumber;
    private String bankName;
    private String ifscCode;
    private String additionalNotes;
}
