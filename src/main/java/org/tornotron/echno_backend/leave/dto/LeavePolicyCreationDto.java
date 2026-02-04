package org.tornotron.echno_backend.leave.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LeavePolicyCreationDto {

    @NotNull(message = "Organization ID is required")
    private Long organizationId;

    @NotBlank(message = "Leave type code is required")
    @Size(max = 50, message = "Leave type code must not exceed 50 characters")
    private String leaveTypeCode;

    @NotBlank(message = "Leave type name is required")
    @Size(max = 100, message = "Leave type name must not exceed 100 characters")
    private String leaveTypeName;

    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @NotNull(message = "Annual quota is required")
    @Positive(message = "Annual quota must be positive")
    private Double annualQuota;

    private Double accrualRatePerMonth;

    private Double carryForwardLimit;

    private Integer carryForwardExpiryMonths;

    private Double minDaysPerRequest = 0.5;

    private Double maxDaysPerRequest;

    private Integer advanceNoticeDays = 0;

    private Boolean requiresAttachment = false;

    private Integer attachmentRequiredAfterDays;

    private String applicableGenders = "ALL";

    private Integer minServiceMonths = 0;

    private Boolean allowHalfDay = true;

    private Boolean isPaid = true;

    private Integer displayOrder = 0;
}
