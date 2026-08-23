package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "An employee's leave balance under one policy for one year.")
@Data
public class LeaveBalanceDto {
    @Schema(description = "Id of the balance record.", example = "88")
    private Long id;

    @Schema(description = "Id of the employee this balance belongs to.", example = "18")
    private Long employeeId;

    @Schema(description = "Name of the employee this balance belongs to.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(description = "Leave policy this balance is tracked under.")
    private LeavePolicySimpleDto leavePolicy;

    @Schema(description = "Calendar year this balance applies to.", example = "2026")
    private Integer year;

    @Schema(description = "Balance carried into the year before any accrual.", example = "2.0")
    private Double openingBalance;

    @Schema(description = "Days accrued so far this year.", example = "9.0")
    private Double accrued;

    @Schema(description = "Days already used this year.", example = "3.5")
    private Double used;

    @Schema(description = "Days locked in pending, not-yet-approved requests.", example = "1.0")
    private Double pending;

    @Schema(description = "Days available, after used and pending are deducted.", example = "6.5")
    private Double available;

    @Schema(description = "Days that can still be booked, respecting per-request limits.", example = "6.5")
    private Double bookable;

    @Schema(description = "Days carried forward from the previous year.", example = "1.5")
    private Double carryForwardFromPrevious;

    @Schema(description = "Date the carried-forward days expire, if the policy sets one.", example = "2026-03-31")
    private LocalDate carryForwardExpiryDate;

    @Schema(description = "Time this balance was last recalculated.", example = "2026-08-20T02:00:00")
    private LocalDateTime lastCalculatedAt;
}
