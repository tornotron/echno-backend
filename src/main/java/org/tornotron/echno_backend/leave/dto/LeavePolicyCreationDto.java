package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Schema(description = "Payload to create a leave policy for an organization, with its quota, accrual and "
        + "eligibility rules.")
@Data
public class LeavePolicyCreationDto {

    @Schema(description = "Id of the organization the policy belongs to.", example = "2")
    @NotNull(message = "Organization ID is required")
    private Long organizationId;

    @Schema(description = "Short code identifying the leave type.", example = "CL")
    @NotBlank(message = "Leave type code is required")
    @Size(max = 50, message = "Leave type code must not exceed 50 characters")
    private String leaveTypeCode;

    @Schema(description = "Display name of the leave type.", example = "Casual Leave")
    @NotBlank(message = "Leave type name is required")
    @Size(max = 100, message = "Leave type name must not exceed 100 characters")
    private String leaveTypeName;

    @Schema(description = "Description of the leave type and when it applies.", example = "Short-notice "
            + "leave for personal matters, not carried forward beyond the configured limit")
    @Size(max = 500, message = "Description must not exceed 500 characters")
    private String description;

    @Schema(description = "Total days granted per year under this policy.", example = "12.0")
    @NotNull(message = "Annual quota is required")
    @Positive(message = "Annual quota must be positive")
    private Double annualQuota;

    @Schema(description = "Days accrued per completed month, for policies that accrue monthly instead of "
            + "granting the full quota upfront.", example = "1.0")
    private Double accrualRatePerMonth;

    @Schema(description = "Maximum days that can be carried forward into the next year.", example = "5.0")
    private Double carryForwardLimit;

    @Schema(description = "Number of months into the next year before carried-forward days expire.", example = "3")
    private Integer carryForwardExpiryMonths;

    @Schema(description = "Smallest number of days that can be requested at once.", example = "0.5")
    private Double minDaysPerRequest = 0.5;

    @Schema(description = "Largest number of days that can be requested at once.", example = "15.0")
    private Double maxDaysPerRequest;

    @Schema(description = "Minimum number of days' notice required before the leave starts.", example = "2")
    private Integer advanceNoticeDays = 0;

    @Schema(description = "Whether a supporting attachment is required for requests under this policy.", example = "false")
    private Boolean requiresAttachment = false;

    @Schema(description = "Number of consecutive days after which an attachment becomes required, when "
            + "requiresAttachment is true.", example = "3")
    private Integer attachmentRequiredAfterDays;

    @Schema(description = "Genders this policy applies to.", example = "ALL")
    private String applicableGenders = "ALL";

    @Schema(description = "Minimum months of service before an employee becomes eligible.", example = "6")
    private Integer minServiceMonths = 0;

    @Schema(description = "Whether requests under this policy can be for half a day.", example = "true")
    private Boolean allowHalfDay = true;

    @Schema(description = "Whether leave taken under this policy is paid.", example = "true")
    private Boolean isPaid = true;

    @Schema(description = "Order this policy is shown in, relative to the organization's other policies.", example = "1")
    private Integer displayOrder = 0;

    @Schema(description = "Whether leave requests under this policy go through the full multi-level approval "
            + "chain (the employee's management line). Set false to opt out and let a single approval by the "
            + "direct approver finalize the request.", example = "true")
    private Boolean multiLevelApprovalEnabled = true;
}
