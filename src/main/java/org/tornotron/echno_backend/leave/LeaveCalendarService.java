package org.tornotron.echno_backend.leave;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.leave.mapper.LeaveCalendarMapper;
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
    private final LeaveCalendarMapper leaveCalendarMapper;
    private final CurrentEmployeeService currentEmployeeService;

    public LeaveCalendarService(
            LeaveCalendarRepository calendarRepository,
            EmployeeRepository employeeRepository,
            LeaveCalendarMapper leaveCalendarMapper,
            CurrentEmployeeService currentEmployeeService) {
        this.calendarRepository = calendarRepository;
        this.employeeRepository = employeeRepository;
        this.leaveCalendarMapper = leaveCalendarMapper;
        this.currentEmployeeService = currentEmployeeService;
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

    /**
     * The current tenant's leave calendar over a date range.
     *
     * <p>The organization used to be an id the caller sent, on a path segment the guard never
     * read. It answered about whichever organization the caller named, and only the Hibernate
     * {@code orgFilter} kept that from reaching another tenant's rows: a defence in depth doing
     * the work of the check itself. The tenant is settled by the session, so it comes from
     * {@link TenantContext}.
     */
    @Transactional(readOnly = true)
    public List<LeaveCalendarDto> getCalendarByOrganization(
            LocalDate startDate,
            LocalDate endDate) {
        return calendarRepository.findByOrganizationAndDateRange(
                        TenantContext.getCurrentOrgId(), startDate, endDate)
                .stream()
                .map(leaveCalendarMapper::toDto)
                .collect(Collectors.toList());
    }

    /** One department's leave calendar within the current tenant, over a date range. */
    @Transactional(readOnly = true)
    public List<LeaveCalendarDto> getCalendarByDepartment(
            String department,
            LocalDate startDate,
            LocalDate endDate) {
        return calendarRepository.findByOrganizationAndDepartmentAndDateRange(
                        TenantContext.getCurrentOrgId(), department, startDate, endDate)
                .stream()
                .map(leaveCalendarMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveCalendarDto> getCalendarByEmployee(
            Long employeeId,
            LocalDate startDate,
            LocalDate endDate) {
        return calendarRepository.findByEmployeeAndDateRange(employeeId, startDate, endDate)
                .stream()
                .map(leaveCalendarMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * The leave calendar of the caller's own direct reports, over a date range.
     *
     * <p>A team is the caller's own by definition. Taking the manager as a query parameter under a
     * guard that only asked for a role was the same shape as the approval queue in #683: an
     * administrator read any manager's team by naming them, and a manager, who holds neither
     * system-admin nor hr-admin, could not read their own. The manager comes from the session.
     */
    @Transactional(readOnly = true)
    public List<LeaveCalendarDto> getMyTeamCalendar(
            LocalDate startDate,
            LocalDate endDate) {

        Long managerId = currentEmployeeService
                .requireCurrentEmployee("read your team's leave calendar").getId();

        List<Employee> directReports = employeeRepository.findByManager_Id(managerId);

        List<Long> employeeIds = directReports.stream()
                .map(Employee::getId)
                .collect(Collectors.toList());

        if (employeeIds.isEmpty()) {
            return List.of();
        }

        return calendarRepository.findByEmployeeIdsAndDateRange(employeeIds, startDate, endDate)
                .stream()
                .map(leaveCalendarMapper::toDto)
                .collect(Collectors.toList());
    }

    /** The current tenant's leave calendar over a date range, keyed by date for a day-by-day view. */
    @Transactional(readOnly = true)
    public Map<LocalDate, List<LeaveCalendarDto>> getCalendarGroupedByDate(
            LocalDate startDate,
            LocalDate endDate) {
        return calendarRepository.findByOrganizationAndDateRange(
                        TenantContext.getCurrentOrgId(), startDate, endDate)
                .stream()
                .map(leaveCalendarMapper::toDto)
                .collect(Collectors.groupingBy(LeaveCalendarDto::getLeaveDate));
    }

    /** How many employees in the current tenant are on leave on one date. */
    @Transactional(readOnly = true)
    public long countEmployeesOnLeave(LocalDate date) {
        return calendarRepository.countEmployeesOnLeaveByOrgAndDate(
                TenantContext.getCurrentOrgId(), date);
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
