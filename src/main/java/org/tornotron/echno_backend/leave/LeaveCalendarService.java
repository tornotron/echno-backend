package org.tornotron.echno_backend.leave;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.DtoConversions.LeaveCalendarDtoConvertor;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveCalendarDto;
import org.tornotron.echno_backend.leave.enums.HalfDayType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Validated
public class LeaveCalendarService {

    private final LeaveCalendarRepository calendarRepository;
    private final EmployeeRepository employeeRepository;

    public LeaveCalendarService(
            LeaveCalendarRepository calendarRepository,
            EmployeeRepository employeeRepository) {
        this.calendarRepository = calendarRepository;
        this.employeeRepository = employeeRepository;
    }

    @Transactional
    public void createCalendarEntries(LeaveRequest request) {
        calendarRepository.deleteByLeaveRequestId(request.getId());

        List<LeaveCalendar> entries = new ArrayList<>();
        LocalDate current = request.getStartDate();

        while (!current.isAfter(request.getEndDate())) {
            HalfDayType dayType = determineDayType(request, current);

            LeaveCalendar entry = new LeaveCalendar();
            entry.setOrganization(request.getOrganization());
            entry.setEmployee(request.getEmployee());
            entry.setLeaveRequest(request);
            entry.setLeaveDate(current);
            entry.setDayType(dayType);
            entry.setLeaveTypeCode(request.getLeavePolicy().getLeaveTypeCode());
            entry.setLeaveTypeName(request.getLeavePolicy().getLeaveTypeName());
            entry.setEmployeeName(request.getEmployee().getEmployeeName());
            entry.setDepartment(request.getEmployee().getDepartment());

            entries.add(entry);
            current = current.plusDays(1);
        }

        calendarRepository.saveAll(entries);
    }

    @Transactional
    public void deleteCalendarEntries(Long requestId) {
        calendarRepository.deleteByLeaveRequestId(requestId);
    }

    @Transactional(readOnly = true)
    public List<LeaveCalendarDto> getCalendarByOrganization(
            Long organizationId,
            LocalDate startDate,
            LocalDate endDate) {
        return calendarRepository.findByOrganizationAndDateRange(organizationId, startDate, endDate)
                .stream()
                .map(LeaveCalendarDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveCalendarDto> getCalendarByDepartment(
            Long organizationId,
            String department,
            LocalDate startDate,
            LocalDate endDate) {
        return calendarRepository.findByOrganizationAndDepartmentAndDateRange(
                        organizationId, department, startDate, endDate)
                .stream()
                .map(LeaveCalendarDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveCalendarDto> getCalendarByEmployee(
            Long employeeId,
            LocalDate startDate,
            LocalDate endDate) {
        return calendarRepository.findByEmployeeAndDateRange(employeeId, startDate, endDate)
                .stream()
                .map(LeaveCalendarDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveCalendarDto> getTeamCalendar(
            Long managerId,
            LocalDate startDate,
            LocalDate endDate) {
        
        List<Employee> directReports = employeeRepository.findByManager_Id(managerId);

        List<Long> employeeIds = directReports.stream()
                .map(Employee::getId)
                .collect(Collectors.toList());

        if (employeeIds.isEmpty()) {
            return List.of();
        }

        return calendarRepository.findByEmployeeIdsAndDateRange(employeeIds, startDate, endDate)
                .stream()
                .map(LeaveCalendarDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Map<LocalDate, List<LeaveCalendarDto>> getCalendarGroupedByDate(
            Long organizationId,
            LocalDate startDate,
            LocalDate endDate) {
        return calendarRepository.findByOrganizationAndDateRange(organizationId, startDate, endDate)
                .stream()
                .map(LeaveCalendarDtoConvertor::convertToDto)
                .collect(Collectors.groupingBy(LeaveCalendarDto::getLeaveDate));
    }

    @Transactional(readOnly = true)
    public long countEmployeesOnLeave(Long organizationId, LocalDate date) {
        return calendarRepository.countEmployeesOnLeaveByOrgAndDate(organizationId, date);
    }

    private HalfDayType determineDayType(LeaveRequest request, LocalDate date) {
        if (date.equals(request.getStartDate()) && request.getStartHalfDayType() != null) {
            return request.getStartHalfDayType();
        }
        if (date.equals(request.getEndDate()) && request.getEndHalfDayType() != null) {
            return request.getEndHalfDayType();
        }
        return HalfDayType.FULL_DAY;
    }
}
