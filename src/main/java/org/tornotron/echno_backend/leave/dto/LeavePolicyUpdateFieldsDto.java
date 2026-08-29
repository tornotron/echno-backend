package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * The fields a partial leave-policy update may carry, and the type each one is read as.
 *
 * <p>See {@code org.tornotron.echno_backend.task.dto.TaskUpdateFieldsDto} for why the endpoint
 * keeps the map at runtime and publishes this as its schema. Nothing deserializes into this class.
 *
 * <p>Its field list is kept honest by {@code PartialUpdateSchemaContractTest}, which reads the keys
 * {@code LeavePolicyService.updatePolicy} actually accepts out of that method's source. Note that
 * {@code leaveTypeCode} is deliberately absent: a policy's code is fixed at creation and the update
 * path does not accept it.
 */
@Schema(description = "Fields a partial leave-policy update may change. Every field is optional; "
        + "only the fields present in the request are applied. Keys not listed here are ignored.")
@Data
public class LeavePolicyUpdateFieldsDto {

    @Schema(description = "Display name of the leave type.", example = "Casual Leave")
    private String leaveTypeName;

    @Schema(description = "Description of the leave type and when it applies.",
            example = "Short-notice leave for personal matters.")
    private String description;

    @Schema(description = "Total days granted per year under this policy. Must be a number; null is "
            + "rejected.", example = "12.0")
    private Double annualQuota;

    @Schema(description = "Days accrued per completed month.", example = "1.0")
    private Double accrualRatePerMonth;

    @Schema(description = "Maximum days that can be carried forward into the next year.", example = "5.0")
    private Double carryForwardLimit;

    @Schema(description = "Months into the next year before carried-forward days expire.", example = "3")
    private Integer carryForwardExpiryMonths;

    @Schema(description = "Smallest number of days that can be requested at once.", example = "0.5")
    private Double minDaysPerRequest;

    @Schema(description = "Largest number of days that can be requested at once.", example = "15.0")
    private Double maxDaysPerRequest;

    @Schema(description = "Minimum days of notice required before the leave starts.", example = "2")
    private Integer advanceNoticeDays;

    @Schema(description = "Whether a supporting attachment is required.", example = "false")
    private Boolean requiresAttachment;

    @Schema(description = "Consecutive days after which an attachment becomes required.", example = "3")
    private Integer attachmentRequiredAfterDays;

    @Schema(description = "Genders this policy applies to.", example = "ALL")
    private String applicableGenders;

    @Schema(description = "Months of service before an employee becomes eligible.", example = "6")
    private Integer minServiceMonths;

    @Schema(description = "Whether requests under this policy can be for half a day.", example = "true")
    private Boolean allowHalfDay;

    @Schema(description = "Whether leave taken under this policy is paid.", example = "true")
    private Boolean isPaid;

    @Schema(description = "Whether the policy is available for new requests.", example = "true")
    private Boolean isActive;

    @Schema(description = "Whether requests go through the full multi-level approval chain.",
            example = "true")
    private Boolean multiLevelApprovalEnabled;

    @Schema(description = "Order this policy is shown in, relative to the organization's others.",
            example = "1")
    private Integer displayOrder;
}
