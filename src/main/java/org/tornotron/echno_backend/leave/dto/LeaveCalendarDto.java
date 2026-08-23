package org.tornotron.echno_backend.leave.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.tornotron.echno_backend.leave.enums.HalfDayType;

import java.time.LocalDate;

@Schema(description = "One day of leave for one employee, as shown on the leave calendar.")
@Data
public class LeaveCalendarDto {
    @Schema(description = "Id of the calendar entry.", example = "9021")
    private Long id;

    @Schema(description = "Id of the organization the employee belongs to.", example = "2")
    private Long organizationId;

    @Schema(description = "Id of the employee on leave.", example = "18")
    private Long employeeId;

    @Schema(description = "Name of the employee on leave.", example = "Ravi Kumar")
    private String employeeName;

    @Schema(description = "Department of the employee on leave.", example = "Civil")
    private String department;

    @Schema(description = "Id of the leave request this entry comes from.", example = "241")
    private Long leaveRequestId;

    @Schema(description = "The date this entry covers.", example = "2026-09-15")
    private LocalDate leaveDate;

    @Schema(description = "Whether the day is a full day or a half day of leave.", example = "FULL_DAY")
    private HalfDayType dayType;

    @Schema(description = "Short code of the leave type taken.", example = "CL")
    private String leaveTypeCode;

    @Schema(description = "Display name of the leave type taken.", example = "Casual Leave")
    private String leaveTypeName;
}
