package org.tornotron.echno_backend.leave.dto;

import lombok.Data;
import org.tornotron.echno_backend.leave.enums.HalfDayType;

import java.time.LocalDate;

@Data
public class LeaveCalendarDto {
    private Long id;
    private Long organizationId;
    private Long employeeId;
    private String employeeName;
    private String department;
    private Long leaveRequestId;
    private LocalDate leaveDate;
    private HalfDayType dayType;
    private String leaveTypeCode;
    private String leaveTypeName;
}
