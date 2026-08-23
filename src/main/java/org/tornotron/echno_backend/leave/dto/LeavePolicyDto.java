package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Schema(description = "A leave policy with its quota, accrual and eligibility rules.")
@Data
public class LeavePolicyDto {
    @Schema(description = "Id of the policy.", example = "3")
    private Long id;

    @Schema(description = "Id of the organization the policy belongs to.", example = "2")
    private Long organizationId;

    @Schema(description = "Name of the organization the policy belongs to.", example = "Asset Homes")
    private String organizationName;

    @Schema(description = "Short code identifying the leave type.", example = "CL")
    private String leaveTypeCode;

    @Schema(description = "Display name of the leave type.", example = "Casual Leave")
    private String leaveTypeName;

    @Schema(description = "Description of the leave type and when it applies.", example = "Short-notice "
            + "leave for personal matters, not carried forward beyond the configured limit")
    private String description;

    @Schema(description = "Total days granted per year under this policy.", example = "12.0")
    private Double annualQuota;

    @Schema(description = "Days accrued per completed month, for policies that accrue monthly.", example = "1.0")
    private Double accrualRatePerMonth;

    @Schema(description = "Maximum days that can be carried forward into the next year.", example = "5.0")
    private Double carryForwardLimit;

    @Schema(description = "Number of months into the next year before carried-forward days expire.", example = "3")
    private Integer carryForwardExpiryMonths;

    @Schema(description = "Smallest number of days that can be requested at once.", example = "0.5")
    private Double minDaysPerRequest;

    @Schema(description = "Largest number of days that can be requested at once.", example = "15.0")
    private Double maxDaysPerRequest;

    @Schema(description = "Minimum number of days' notice required before the leave starts.", example = "2")
    private Integer advanceNoticeDays;

    @Schema(description = "Whether a supporting attachment is required for requests under this policy.", example = "false")
    private Boolean requiresAttachment;

    @Schema(description = "Number of consecutive days after which an attachment becomes required.", example = "3")
    private Integer attachmentRequiredAfterDays;

    @Schema(description = "Genders this policy applies to.", example = "ALL")
    private String applicableGenders;

    @Schema(description = "Minimum months of service before an employee becomes eligible.", example = "6")
    private Integer minServiceMonths;

    @Schema(description = "Whether requests under this policy can be for half a day.", example = "true")
    private Boolean allowHalfDay;

    @Schema(description = "Whether leave taken under this policy is paid.", example = "true")
    private Boolean isPaid;

    @Schema(description = "Whether the policy is currently active.", example = "true")
    private Boolean isActive;

    @Schema(description = "Order this policy is shown in, relative to the organization's other policies.", example = "1")
    private Integer displayOrder;

    @Schema(description = "Time the policy was created.", example = "2026-01-05T10:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Time the policy was last updated.", example = "2026-07-01T08:30:00")
    private LocalDateTime updatedAt;
}
