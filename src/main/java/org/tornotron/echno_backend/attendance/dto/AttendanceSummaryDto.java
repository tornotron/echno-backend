package org.tornotron.echno_backend.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Aggregated attendance figures for one employee over a calendar month.")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttendanceSummaryDto {

    @Schema(description = "Id of the employee.", example = "42")
    private Long employeeId;

    @Schema(description = "Full name of the employee.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(description = "Month the summary covers, 1 to 12.", example = "1")
    private Integer month;

    @Schema(description = "Year the summary covers.", example = "2026")
    private Integer year;

    @Schema(description = "Total working days in the month, excluding weekly offs and holidays.", example = "26")
    private Integer totalWorkingDays;

    @Schema(description = "Number of full days present.", example = "22")
    private Integer presentDays;

    @Schema(description = "Number of half days.", example = "1")
    private Integer halfDays;

    @Schema(description = "Number of days absent.", example = "1")
    private Integer absentDays;

    @Schema(description = "Number of days on approved leave.", example = "2")
    private Integer leaveDays;

    @Schema(description = "Number of weekly off days in the month.", example = "4")
    private Integer weeklyOffs;

    @Schema(description = "Number of holidays in the month.", example = "1")
    private Integer holidays;

    @Schema(description = "Number of days the employee arrived late.", example = "3")
    private Integer lateDays;

    @Schema(description = "Number of days the employee worked overtime.", example = "5")
    private Integer overtimeDays;

    @Schema(description = "Total hours worked in the month.", example = "182.5")
    private Double totalHoursWorked;

    @Schema(description = "Total overtime hours worked in the month.", example = "6.0")
    private Double totalOvertimeHours;

    @Schema(description = "Average hours worked per working day.", example = "8.3")
    private Double averageWorkHours;

    @Schema(description = "Attendance percentage for the month.", example = "92.3")
    private Double attendancePercentage;

    @Schema(description = "Effective work days, counting half days as 0.5.", example = "22.5")
    private Double effectiveWorkDays;
}
