package org.tornotron.echno_backend.leave.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.tornotron.echno_backend.leave.LeaveCalendar;
import org.tornotron.echno_backend.leave.dto.LeaveCalendarDto;

/**
 * Maps {@link LeaveCalendar} to its DTO. The denormalized fields map by name; the
 * organization, employee, and leave-request associations are flattened to their ids.
 */
@Mapper(componentModel = "spring")
public interface LeaveCalendarMapper {

    @Mapping(source = "organization.id", target = "organizationId")
    @Mapping(source = "employee.id", target = "employeeId")
    @Mapping(source = "leaveRequest.id", target = "leaveRequestId")
    LeaveCalendarDto toDto(LeaveCalendar calendar);
}
