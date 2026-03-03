package org.tornotron.echno_backend.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceResponseDto {
    private Long id;
    private Long employeeId;
    private String employeeName;
    private LocalDate attendanceDate;
    private Long projectId;
    private String projectName;
    private AttendanceStatus status;
    private ShiftTimingDto shiftTiming;

    private List<ClockEventDto> clockEvents;

    private Integer totalWorkMinutes;
    private Integer morningSessionMinutes;
    private Integer afternoonSessionMinutes;
    private Integer overtimeMinutes;
    private Integer breakDurationMinutes;
    private Boolean isLateArrival;
    private Boolean isEarlyCheckout;
    private Boolean isOvertime;

    private Long leaveId;
    private String leaveType;

    private List<AttendanceRegularizationDto> regularizations;
    private List<MovementRecordDto> movements;

    private ApprovalStatus approvalStatus;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String remarks;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
