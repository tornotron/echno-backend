package org.tornotron.echno_backend.leave.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class LeavePolicyDto {
    private Long id;
    private Long organizationId;
    private String organizationName;
    private String leaveTypeCode;
    private String leaveTypeName;
    private String description;
    private Double annualQuota;
    private Double accrualRatePerMonth;
    private Double carryForwardLimit;
    private Integer carryForwardExpiryMonths;
    private Double minDaysPerRequest;
    private Double maxDaysPerRequest;
    private Integer advanceNoticeDays;
    private Boolean requiresAttachment;
    private Integer attachmentRequiredAfterDays;
    private String applicableGenders;
    private Integer minServiceMonths;
    private Boolean allowHalfDay;
    private Boolean isPaid;
    private Boolean isActive;
    private Integer displayOrder;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
