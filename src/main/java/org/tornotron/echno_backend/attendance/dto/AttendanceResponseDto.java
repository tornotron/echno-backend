package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "A single day's attendance record for an employee, with its clock events, movements, regularizations and approval state.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceResponseDto {

    @Schema(description = "Id of the attendance record.", example = "781")
    private Long id;

    @Schema(description = "Id of the employee.", example = "42")
    private Long employeeId;

    @Schema(description = "Full name of the employee.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(description = "Calendar date the record covers.", example = "2026-01-15")
    private LocalDate attendanceDate;

    @Schema(description = "Id of the project the employee was assigned to on this date.", example = "12")
    private Long projectId;

    @Schema(description = "Name of the project.", example = "Asset Homes Kovilambakkam Phase 2")
    private String projectName;

    @Schema(description = "Computed attendance status for the day.", example = "PRESENT")
    private AttendanceStatus status;

    @Schema(description = "Shift the employee was scheduled to work.")
    private ShiftTimingDto shiftTiming;

    @Schema(description = "Clock events recorded for the day, in chronological order.")
    private List<ClockEventDto> clockEvents;

    @Schema(description = "Total minutes worked across all sessions.", example = "480")
    private Integer totalWorkMinutes;

    @Schema(description = "Minutes worked in the morning session, before the lunch break.", example = "225")
    private Integer morningSessionMinutes;

    @Schema(description = "Minutes worked in the afternoon session, after the lunch break.", example = "255")
    private Integer afternoonSessionMinutes;

    @Schema(description = "Minutes worked beyond the shift's overtime threshold.", example = "30")
    private Integer overtimeMinutes;

    @Schema(description = "Total minutes spent on breaks during the day.", example = "60")
    private Integer breakDurationMinutes;

    @Schema(description = "Whether the employee clocked in after the shift's grace period.", example = "false")
    private Boolean isLateArrival;

    @Schema(description = "Whether the employee clocked out before the shift's end time.", example = "false")
    private Boolean isEarlyCheckout;

    @Schema(description = "Whether the employee worked beyond the overtime threshold.", example = "false")
    private Boolean isOvertime;

    @Schema(description = "Id of the leave request this record was generated from, if the day is on leave.", example = "9")
    private Long leaveId;

    @Schema(description = "Type of leave applied on this date, if any.", example = "CASUAL")
    private String leaveType;

    @Schema(description = "Regularization requests filed against this record.")
    private List<AttendanceRegularizationDto> regularizations;

    @Schema(description = "Movements logged against this record.")
    private List<MovementRecordDto> movements;

    @Schema(description = "Approval status of the record.", example = "APPROVED")
    private ApprovalStatus approvalStatus;

    @Schema(description = "Name or id of the person who approved or rejected the record.", example = "Anand Rajashekar")
    private String approvedBy;

    @Schema(description = "Timestamp the record was approved or rejected.", example = "2026-01-16T10:15:00")
    private LocalDateTime approvedAt;

    @Schema(description = "Remarks attached to the record, for example an approval or rejection note.", example = "Confirmed with site supervisor")
    private String remarks;

    @Schema(description = "Timestamp the record was created.", example = "2026-01-15T09:02:00")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp the record was last updated.", example = "2026-01-15T18:05:00")
    private LocalDateTime updatedAt;
}
