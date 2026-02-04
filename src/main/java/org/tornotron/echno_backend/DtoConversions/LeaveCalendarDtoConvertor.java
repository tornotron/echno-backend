package org.tornotron.echno_backend.DtoConversions;

import org.tornotron.echno_backend.leave.LeaveCalendar;
import org.tornotron.echno_backend.leave.dto.LeaveCalendarDto;

public class LeaveCalendarDtoConvertor {

    public static LeaveCalendarDto convertToDto(LeaveCalendar calendar) {
        if (calendar == null) return null;

        LeaveCalendarDto dto = new LeaveCalendarDto();
        dto.setId(calendar.getId());
        dto.setOrganizationId(calendar.getOrganization().getId());
        dto.setEmployeeId(calendar.getEmployee().getId());
        dto.setEmployeeName(calendar.getEmployeeName());
        dto.setDepartment(calendar.getDepartment());
        dto.setLeaveRequestId(calendar.getLeaveRequest().getId());
        dto.setLeaveDate(calendar.getLeaveDate());
        dto.setDayType(calendar.getDayType());
        dto.setLeaveTypeCode(calendar.getLeaveTypeCode());
        dto.setLeaveTypeName(calendar.getLeaveTypeName());
        return dto;
    }
}
