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

    @Schema(nullable = true, description = "Description of the leave type and when it applies. Null where "
            + "the policy was created without one.", example = "Short-notice "
            + "leave for personal matters, not carried forward beyond the configured limit")
    private String description;

    @Schema(description = "Total days granted per year under this policy.", example = "12.0")
    private Double annualQuota;

    @Schema(nullable = true, description = "Days accrued per completed month. Null for policies that grant "
            + "the full quota upfront instead of accruing monthly.", example = "1.0")
    private Double accrualRatePerMonth;

    @Schema(nullable = true, description = "Maximum days that can be carried forward into the next year. "
            + "Null where the policy allows no carry forward.", example = "5.0")
    private Double carryForwardLimit;

    @Schema(nullable = true, description = "Number of months into the next year before carried-forward days "
            + "expire. Null where carried-forward days do not expire, and on any policy that allows no carry "
            + "forward at all.", example = "3")
    private Integer carryForwardExpiryMonths;

    @Schema(nullable = true, description = "Smallest number of days that can be requested at once. The "
            + "create payload defaults it to 0.5 when the field is omitted, but the column is nullable and a "
            + "null sent explicitly is written through.", example = "0.5")
    private Double minDaysPerRequest;

    @Schema(nullable = true, description = "Largest number of days that can be requested at once. Null "
            + "where the policy sets no per-request ceiling.", example = "15.0")
    private Double maxDaysPerRequest;

    @Schema(nullable = true, description = "Minimum number of days' notice required before the leave "
            + "starts. Defaults to 0 on a create payload that omits the field; a null sent explicitly is "
            + "stored as null, because the column permits it.", example = "2")
    private Integer advanceNoticeDays;

    @Schema(nullable = true, description = "Whether a supporting attachment is required for requests under "
            + "this policy. Defaults to false when the field is absent from the create payload, and stays "
            + "null if the payload sends null, since the column is nullable.", example = "false")
    private Boolean requiresAttachment;

    @Schema(nullable = true, description = "Number of consecutive days after which an attachment becomes "
            + "required. Null where the policy sets no such threshold.", example = "3")
    private Integer attachmentRequiredAfterDays;

    @Schema(nullable = true, description = "Genders this policy applies to. Defaults to ALL for a create "
            + "payload that omits the field. The column is nullable, so a policy created with an explicit "
            + "null carries none.", example = "ALL")
    private String applicableGenders;

    @Schema(nullable = true, description = "Minimum months of service before an employee becomes eligible. "
            + "Defaults to 0 when the create payload omits the field; the column is nullable, so an explicit "
            + "null survives to the response.", example = "6")
    private Integer minServiceMonths;

    @Schema(nullable = true, description = "Whether requests under this policy can be for half a day. "
            + "Defaults to true when the create payload omits the field, and is null where the payload set "
            + "it to null, which the column allows.", example = "true")
    private Boolean allowHalfDay;

    @Schema(nullable = true, description = "Whether leave taken under this policy is paid. Defaults to true "
            + "on a create payload that omits the field. The column is nullable, so an explicit null is "
            + "stored and returned.", example = "true")
    private Boolean isPaid;

    @Schema(description = "Whether the policy is currently active.", example = "true")
    private Boolean isActive;

    @Schema(description = "Whether leave requests under this policy go through the full multi-level approval "
            + "chain (the employee's management line). When false, a single approval by the direct approver "
            + "finalizes the request.", example = "true")
    private Boolean multiLevelApprovalEnabled;

    @Schema(nullable = true, description = "Order this policy is shown in, relative to the organization's "
            + "other policies. Defaults to 0 when the create payload omits the field; the nullable column "
            + "keeps an explicit null.", example = "1")
    private Integer displayOrder;

    @Schema(description = "Time the policy was created.", example = "2026-01-05T10:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "Time the policy was last updated.", example = "2026-07-01T08:30:00")
    private LocalDateTime updatedAt;
}
