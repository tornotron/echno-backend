package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.RegularizationCalendarState;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;

import java.time.LocalDate;

@Schema(description = "One day of an employee's regularization calendar: what the day's "
        + "attendance looks like and whether a regularization or a leave can be raised for it.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegularizationCalendarDayDto {

    @Schema(description = "The day.", example = "2026-08-27")
    private LocalDate date;

    @Schema(description = "What the calendar shows for the day.", example = "MISSING")
    private RegularizationCalendarState state;

    @Schema(description = "Whether the employee can act on the day: raise a regularization or "
            + "apply for leave. True for missing and incomplete days, and for non-working days, "
            + "where the employee may have worked a weekly off.", example = "true")
    private boolean actionable;

    @Schema(description = "The day's attendance record, when there is one. Where the employee "
            + "has records on more than one project, the one the state was read from.",
            example = "781", nullable = true)
    private Long attendanceId;

    @Schema(description = "Project of that record.", example = "12", nullable = true)
    private Long projectId;

    @Schema(description = "Name of that project.", example = "Asset Homes Tower B", nullable = true)
    private String projectName;

    @Schema(description = "The most recent regularization request for the day, when there is one.",
            example = "7", nullable = true)
    private Long regularizationId;

    @Schema(description = "Status of that request.", example = "REJECTED", nullable = true)
    private RegularizationStatus regularizationStatus;

    @Schema(description = "Reason given when that request was rejected.", nullable = true,
            example = "Clock-in time does not match the site register")
    private String rejectionReason;

    @Schema(description = "Leave type on a leave day, when known.", example = "Casual Leave",
            nullable = true)
    private String leaveType;
}
