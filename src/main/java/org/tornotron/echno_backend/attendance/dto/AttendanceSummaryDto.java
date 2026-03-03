package org.tornotron.echno_backend.attendance.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSummaryDto {
    private Long employeeId;
    private String employeeName;
    private Integer month;
    private Integer year;
    private Integer totalWorkingDays;
    private Integer presentDays;
    private Integer halfDays;
    private Integer absentDays;
    private Integer leaveDays;
    private Integer weeklyOffs;
    private Integer holidays;
    private Integer lateDays;
    private Integer overtimeDays;
    private Double totalHoursWorked;
    private Double totalOvertimeHours;
    private Double averageWorkHours;
    private Double attendancePercentage;
    private Double effectiveWorkDays;
}
