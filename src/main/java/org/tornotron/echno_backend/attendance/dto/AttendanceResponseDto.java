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

/**
 * A day's attendance record as it is served. A field without {@code nullable = true} is one the
 * schema, the mapper or the entity behind it establishes as always present; see
 * {@code ReviewedResponseSchemas} for which is which.
 */
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

    @Schema(description = "Shift the employee was scheduled to work. Null on a record raised by "
            + "marking someone absent or on leave, which carries no shift. A null shift also "
            + "means the day's minutes are never computed.", nullable = true)
    private ShiftTimingDto shiftTiming;

    @Schema(description = "Clock events recorded for the day, in chronological order. Empty "
            + "rather than null on a day with no punches.")
    private List<ClockEventDto> clockEvents;

    @Schema(description = "Total minutes worked across all sessions. Zero on a day nobody "
            + "worked, including one raised by marking someone absent or on leave. Null only on "
            + "a record written before absences began storing a zero here; those rows were left "
            + "as they are, so a client reading history still has to allow for it.",
            example = "480", nullable = true)
    private Integer totalWorkMinutes;

    @Schema(description = "Minutes worked in the morning session, before the lunch break. Null "
            + "on the same terms as totalWorkMinutes.", example = "225", nullable = true)
    private Integer morningSessionMinutes;

    @Schema(description = "Minutes worked in the afternoon session, after the lunch break. Null "
            + "on the same terms as totalWorkMinutes.", example = "255", nullable = true)
    private Integer afternoonSessionMinutes;

    @Schema(description = "Minutes worked beyond the shift's overtime threshold. Zero when no "
            + "overtime was earned. Null on the same terms as totalWorkMinutes.",
            example = "30", nullable = true)
    private Integer overtimeMinutes;

    @Schema(description = "Total minutes spent on breaks during the day. Zero when no break was "
            + "punched. Null on the same terms as totalWorkMinutes.", example = "60",
            nullable = true)
    private Integer breakDurationMinutes;

    @Schema(description = "Whether the employee clocked in after the shift's grace period.", example = "false")
    private Boolean isLateArrival;

    @Schema(description = "Whether the employee clocked out before the shift's end time.", example = "false")
    private Boolean isEarlyCheckout;

    @Schema(description = "Whether the employee worked beyond the overtime threshold.", example = "false")
    private Boolean isOvertime;

    @Schema(description = "Id of the leave request this record was generated from. Null on any "
            + "day not taken as leave.", example = "9", nullable = true)
    private Long leaveId;

    @Schema(description = "Type of leave applied on this date. Null on any day not taken as "
            + "leave, and set together with leaveId.", example = "CASUAL", nullable = true)
    private String leaveType;

    @Schema(description = "Regularization requests filed against this record. Empty rather than "
            + "null where none were filed.")
    private List<AttendanceRegularizationDto> regularizations;

    @Schema(description = "Movements logged against this record. Empty rather than null where "
            + "none were logged.")
    private List<MovementRecordDto> movements;

    @Schema(description = "Approval status of the record.", example = "APPROVED")
    private ApprovalStatus approvalStatus;

    @Schema(description = "Name of the employee who approved or rejected the record. Null until a "
            + "decision is made, and null again when a later out-of-fence punch reopens a day "
            + "that had already been decided. Reads as system where the decision came from a job "
            + "with no signed-in user.", example = "Anand Rajashekar", nullable = true)
    private String approvedBy;

    @Schema(description = "Employee id of the person who approved or rejected the record, for "
            + "filtering by approver. Null before any decision, and also after a decision made "
            + "with no signed-in user, where approvedBy reads as system. Absent here does not "
            + "mean unapproved: read approvalStatus for that.", example = "42", nullable = true)
    private Long approvedById;

    @Schema(description = "Timestamp the record was approved or rejected. Null until a decision "
            + "is made, and cleared again when a later out-of-fence punch reopens a decided day.",
            example = "2026-01-16T10:15:00", nullable = true)
    private LocalDateTime approvedAt;

    @Schema(description = "Whether this day contains a punch the employee marked themselves from "
            + "outside the project's geofence, having given a reason. Such a record is held for a "
            + "decision: it is still waiting while the approval status is PENDING.",
            example = "false")
    private Boolean requiresGeofenceApproval;

    @Schema(description = "Employee id of the person expected to decide the geofence exception: "
            + "the employee's reporting manager, or a project manager assigned to the site. Null "
            + "when neither could be resolved, in which case the attendance record managers decide "
            + "as they do for every other record.",
            example = "42", nullable = true)
    private Long geofenceApproverId;

    @Schema(description = "Remarks attached to the record, for example an approval or rejection "
            + "note. Null where nobody wrote one.", example = "Confirmed with site supervisor",
            nullable = true)
    private String remarks;

    @Schema(description = "Timestamp the record was created. Populated on every insert the "
            + "application makes, but the column permits null, so a row loaded outside the "
            + "application can carry none.", example = "2026-01-15T09:02:00", nullable = true)
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp the record was last updated. Populated on insert as well as "
            + "update, but the column permits null on the same terms as createdAt.",
            example = "2026-01-15T18:05:00", nullable = true)
    private LocalDateTime updatedAt;
}
