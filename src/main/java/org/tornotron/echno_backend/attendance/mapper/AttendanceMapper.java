package org.tornotron.echno_backend.attendance.mapper;

import org.springframework.stereotype.Component;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.common.service.FileStorageService;

import java.util.stream.Collectors;

@Component
public class AttendanceMapper {

    private final ShiftTimingMapper shiftTimingMapper;
    private final ClockEventMapper clockEventMapper;
    private final MovementRecordMapper movementRecordMapper;
    private final AttendanceRegularizationMapper regularizationMapper;

    public AttendanceMapper(ShiftTimingMapper shiftTimingMapper,
                           ClockEventMapper clockEventMapper,
                           MovementRecordMapper movementRecordMapper,
                           AttendanceRegularizationMapper regularizationMapper) {
        this.shiftTimingMapper = shiftTimingMapper;
        this.clockEventMapper = clockEventMapper;
        this.movementRecordMapper = movementRecordMapper;
        this.regularizationMapper = regularizationMapper;
    }

    public AttendanceResponseDto toResponseDto(Attendance attendance, FileStorageService fileStorageService) {
        if (attendance == null) return null;
        return AttendanceResponseDto.builder()
                .id(attendance.getId())
                .employeeId(attendance.getEmployeeId())
                .employeeName(attendance.getEmployeeName())
                .attendanceDate(attendance.getAttendanceDate())
                .projectId(attendance.getProjectId())
                .projectName(attendance.getProjectName())
                .status(attendance.getStatus())
                .shiftTiming(shiftTimingMapper.toDto(attendance.getShiftTiming()))
                .clockEvents(attendance.getClockEvents() != null
                        ? attendance.getClockEvents().stream()
                            .map(clockEvent -> ClockEventMapper.toDto(clockEvent,fileStorageService))
                            .collect(Collectors.toList())
                        : null)
                .totalWorkMinutes(attendance.getTotalWorkMinutes())
                .morningSessionMinutes(attendance.getMorningSessionMinutes())
                .afternoonSessionMinutes(attendance.getAfternoonSessionMinutes())
                .overtimeMinutes(attendance.getOvertimeMinutes())
                .breakDurationMinutes(attendance.getBreakDurationMinutes())
                .isLateArrival(attendance.getIsLateArrival())
                .isEarlyCheckout(attendance.getIsEarlyCheckout())
                .isOvertime(attendance.getIsOvertime())
                .leaveId(attendance.getLeaveId())
                .leaveType(attendance.getLeaveType())
                .regularizations(attendance.getRegularizations() != null
                        ? attendance.getRegularizations().stream()
                            .map(regularizationMapper::toDto)
                            .collect(Collectors.toList())
                        : null)
                .movements(attendance.getMovements() != null
                        ? attendance.getMovements().stream()
                            .map(movementRecordMapper::toDto)
                            .collect(Collectors.toList())
                        : null)
                .approvalStatus(attendance.getApprovalStatus())
                .approvedBy(attendance.getApprovedBy())
                .approvedById(attendance.getApprovedById())
                .approvedAt(attendance.getApprovedAt())
                .remarks(attendance.getRemarks())
                .createdAt(attendance.getCreatedAt())
                .updatedAt(attendance.getUpdatedAt())
                .build();
    }
}
