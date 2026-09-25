package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRegularization;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.dto.ClockEventCreationDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationByDateRequestDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationCalendarDayDto;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.enums.RegularizationCalendarState;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.attendance.service.AttendanceActorResolver.Actor;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.holiday.WorkingCalendarService;
import org.tornotron.echno_backend.leave.LeaveRequest;
import org.tornotron.echno_backend.leave.LeaveRequestRepository;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The calendar side of regularization: what each day of an employee's month needs, and raising a
 * request for a day by its date.
 *
 * <p>The request against a stored attendance record needs a record to point at, and the days an
 * employee most needs to regularize are the ones where they never clocked in, which have none. A
 * request by date closes that gap. The employee names the date, the project, the reason and the
 * times, and the server finds the record for that day and project or creates it, then files the
 * request against it through {@link AttendanceRegularizationService#fileRequest}, so both paths
 * pass the same settings, cap and duplicate checks.
 *
 * <p>A created record carries no clock events and the status {@code PENDING_REGULARIZATION}. The
 * employee never writes attendance directly: the requested times sit on the request and reach the
 * record only when a manager approves it.
 */
@Service
public class RegularizationCalendarService {

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final ProjectRepository projectRepository;
    private final OrganizationRepository organizationRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final WorkingCalendarService workingCalendarService;
    private final AttendanceSecurityService attendanceSecurity;
    private final AttendanceActorResolver actorResolver;
    private final AttendanceRegularizationService regularizationService;
    private final Clock clock;

    /**
     * The production constructor. "Today", which decides what counts as a future date, is read in
     * the sites' zone rather than the server's: the server runs in UTC, and read there an employee
     * in India could not regularize the current day until 05:30 local time.
     */
    @Autowired
    public RegularizationCalendarService(AttendanceRepository attendanceRepository,
                                         EmployeeRepository employeeRepository,
                                         ProjectRepository projectRepository,
                                         OrganizationRepository organizationRepository,
                                         LeaveRequestRepository leaveRequestRepository,
                                         WorkingCalendarService workingCalendarService,
                                         AttendanceSecurityService attendanceSecurity,
                                         AttendanceActorResolver actorResolver,
                                         AttendanceRegularizationService regularizationService,
                                         @Value("${echno.attendance.regularization-zone:Asia/Kolkata}") String zone) {
        this(attendanceRepository, employeeRepository, projectRepository, organizationRepository,
                leaveRequestRepository, workingCalendarService, attendanceSecurity, actorResolver,
                regularizationService, Clock.system(ZoneId.of(zone)));
    }

    RegularizationCalendarService(AttendanceRepository attendanceRepository,
                                  EmployeeRepository employeeRepository,
                                  ProjectRepository projectRepository,
                                  OrganizationRepository organizationRepository,
                                  LeaveRequestRepository leaveRequestRepository,
                                  WorkingCalendarService workingCalendarService,
                                  AttendanceSecurityService attendanceSecurity,
                                  AttendanceActorResolver actorResolver,
                                  AttendanceRegularizationService regularizationService,
                                  Clock clock) {
        this.attendanceRepository = attendanceRepository;
        this.employeeRepository = employeeRepository;
        this.projectRepository = projectRepository;
        this.organizationRepository = organizationRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.workingCalendarService = workingCalendarService;
        this.attendanceSecurity = attendanceSecurity;
        this.actorResolver = actorResolver;
        this.regularizationService = regularizationService;
        this.clock = clock;
    }

    /**
     * Raises a regularization for a day by its date, creating the day's record when there is none.
     *
     * <p>Refused, in this order, when: the caller may not record for the employee; the date is
     * after today; the clock-out is not after the clock-in; the employee has no shift and a record
     * would have to be created; the day is covered by an approved or pending leave; any of the
     * employee's records for that day already has a pending request; or the record for that
     * project already has both a clock-in and a clock-out, so there is nothing to regularize.
     *
     * @param dto The employee, project, date, reason and requested times.
     * @return The filed request.
     * @throws AccessDeniedException if the caller is neither the employee nor a holder of an
     *         attendance record-management role.
     * @throws ResourceNotFoundException if the employee or project is not in this organization.
     * @throws ValidationException if the request is refused for any reason above, or by the
     *         project's regularization settings.
     */
    @Transactional
    public AttendanceRegularizationDto submitByDate(RegularizationByDateRequestDto dto) {
        if (!attendanceSecurity.canRecordFor(dto.getEmployeeId())) {
            throw new AccessDeniedException(
                    "A regularization can only be raised on your own attendance, unless you hold "
                            + "an attendance record-management role");
        }
        LocalDate date = dto.getAttendanceDate();
        if (date.isAfter(LocalDate.now(clock))) {
            throw new ValidationException("A regularization cannot be raised for a future date");
        }
        if (dto.getClockOutTime() != null && !dto.getClockOutTime().isAfter(dto.getClockInTime())) {
            throw new ValidationException("The clock-out time must be after the clock-in time");
        }

        Long orgId = TenantContext.getCurrentOrgId();
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization with ID " + orgId + " was not found"));
        Employee employee = employeeRepository.findByIdAndOrganizationId(dto.getEmployeeId(), orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + dto.getEmployeeId() + " was not found in this organization"));
        Project project = projectRepository.findByIdAndOrganization_Id(dto.getProjectId(), orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Project with ID " + dto.getProjectId() + " was not found in this organization"));

        List<LeaveRequest> leaves = leaveRequestRepository
                .findOverlappingRequests(employee.getId(), date, date, null);
        if (leaves.stream().anyMatch(l -> l.getStatus() == LeaveStatus.APPROVED)) {
            throw new ValidationException(
                    "The employee is on approved leave on " + date + ", so it cannot be regularized");
        }
        if (leaves.stream().anyMatch(l -> l.getStatus() == LeaveStatus.PENDING_APPROVAL)) {
            throw new ValidationException(
                    "A leave request covering " + date + " is awaiting a decision. Withdraw it "
                            + "before regularizing the day");
        }

        List<Attendance> dayRecords = attendanceRepository
                .findByEmployeeIdAndAttendanceDateBetween(employee.getId(), date, date);
        if (dayRecords.stream().anyMatch(RegularizationCalendarService::hasPendingRequest)) {
            throw new ValidationException(
                    "A regularization request for " + date + " is already awaiting a decision");
        }
        if (dayRecords.stream().anyMatch(RegularizationCalendarService::isLeave)) {
            throw new ValidationException(
                    "The employee is on leave on " + date + ", so it cannot be regularized");
        }

        Optional<Attendance> existing = dayRecords.stream()
                .filter(a -> project.getId().equals(a.getProjectId()))
                .findFirst();

        Set<ClockEventType> present = existing
                .map(a -> a.getClockEvents().stream()
                        .map(ClockEvent::getEventType)
                        .collect(Collectors.toSet()))
                .orElse(Set.of());

        List<ClockEventCreationDto> corrected = new ArrayList<>();
        if (!present.contains(ClockEventType.MORNING_CLOCK_IN)) {
            corrected.add(event(ClockEventType.MORNING_CLOCK_IN, date.atTime(dto.getClockInTime()),
                    project.getId()));
        }
        if (dto.getClockOutTime() != null && !present.contains(ClockEventType.EVENING_CLOCK_OUT)) {
            corrected.add(event(ClockEventType.EVENING_CLOCK_OUT, date.atTime(dto.getClockOutTime()),
                    project.getId()));
        }
        if (corrected.isEmpty()) {
            throw new ValidationException(
                    "The attendance for " + date + " on this project already has the clock events "
                            + "asked for, so there is nothing to regularize");
        }
        List<String> missing = corrected.stream().map(e -> e.getEventType().name()).toList();

        Attendance attendance = existing.orElseGet(() -> createPlaceholder(employee, project, date, org));
        Actor requester = actorResolver.resolveCurrentActor();
        return regularizationService.fileRequest(attendance, org, requester, dto.getReason(),
                missing, corrected);
    }

    /**
     * Every day of one month for an employee, with what the calendar shows and whether the
     * employee can act on it.
     *
     * @param employeeId The employee, already authorized by the endpoint.
     * @param year       The calendar year.
     * @param month      The month, 1 to 12.
     * @return One entry per day of the month, in date order.
     * @throws ResourceNotFoundException if the employee is not in this organization.
     * @throws ValidationException if the month is out of range.
     */
    @Transactional(readOnly = true)
    public List<RegularizationCalendarDayDto> calendar(Long employeeId, int year, int month) {
        if (month < 1 || month > 12) {
            throw new ValidationException("month must be between 1 and 12");
        }
        Long orgId = TenantContext.getCurrentOrgId();
        employeeRepository.findByIdAndOrganizationId(employeeId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        YearMonth ym = YearMonth.of(year, month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        LocalDate today = LocalDate.now(clock);

        Map<LocalDate, List<Attendance>> byDate = attendanceRepository
                .findByEmployeeIdAndAttendanceDateBetween(employeeId, from, to).stream()
                .collect(Collectors.groupingBy(Attendance::getAttendanceDate));
        List<LeaveRequest> leaves = leaveRequestRepository.findOverlappingRequests(employeeId, from, to, null);
        Set<LocalDate> nonWorking = workingCalendarService.nonWorkingDays(from, to);

        List<RegularizationCalendarDayDto> days = new ArrayList<>(ym.lengthOfMonth());
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            days.add(day(date, today, byDate.getOrDefault(date, List.of()), leaves, nonWorking));
        }
        return days;
    }

    private RegularizationCalendarDayDto day(LocalDate date,
                                             LocalDate today,
                                             List<Attendance> records,
                                             List<LeaveRequest> leaves,
                                             Set<LocalDate> nonWorking) {
        RegularizationCalendarDayDto.RegularizationCalendarDayDtoBuilder out =
                RegularizationCalendarDayDto.builder().date(date).actionable(false);

        // The record the day is described by: one with a pending request first, then the most
        // complete one, so an employee who worked two sites is judged by the site they finished.
        Attendance shown = records.stream()
                .max(Comparator.comparing(RegularizationCalendarService::hasPendingRequest)
                        .thenComparing(RegularizationCalendarService::isComplete)
                        .thenComparing(a -> a.getClockEvents().size()))
                .orElse(null);
        if (shown != null) {
            out.attendanceId(shown.getId()).projectId(shown.getProjectId()).projectName(shown.getProjectName());
            latestRequest(shown).ifPresent(r -> out.regularizationId(r.getId())
                    .regularizationStatus(r.getStatus())
                    .rejectionReason(r.getRejectionReason()));
        }

        if (date.isAfter(today)) {
            return out.state(RegularizationCalendarState.FUTURE).build();
        }
        Optional<LeaveRequest> leave = leaves.stream()
                .filter(l -> !date.isBefore(l.getStartDate()) && !date.isAfter(l.getEndDate()))
                .filter(l -> l.getStatus() == LeaveStatus.APPROVED || l.getStatus() == LeaveStatus.PENDING_APPROVAL)
                .max(Comparator.comparing(l -> l.getStatus() == LeaveStatus.APPROVED));
        Attendance leaveRecord = records.stream().filter(RegularizationCalendarService::isLeave).findFirst().orElse(null);
        if (leaveRecord != null || leave.filter(l -> l.getStatus() == LeaveStatus.APPROVED).isPresent()) {
            String type = leaveRecord != null && leaveRecord.getLeaveType() != null
                    ? leaveRecord.getLeaveType()
                    : leave.map(RegularizationCalendarService::leaveTypeName).orElse(null);
            return out.state(RegularizationCalendarState.LEAVE).leaveType(type).build();
        }
        if (leave.isPresent()) {
            return out.state(RegularizationCalendarState.LEAVE_PENDING)
                    .leaveType(leaveTypeName(leave.get())).build();
        }
        if (records.stream().anyMatch(RegularizationCalendarService::hasPendingRequest)) {
            return out.state(RegularizationCalendarState.PENDING).build();
        }
        if (shown != null && isComplete(shown)) {
            boolean regularized = shown.getRegularizations().stream()
                    .anyMatch(r -> r.getStatus() == RegularizationStatus.APPROVED);
            return out.state(regularized ? RegularizationCalendarState.REGULARIZED
                    : RegularizationCalendarState.COMPLETE).build();
        }
        if (shown != null && !shown.getClockEvents().isEmpty()) {
            return out.state(RegularizationCalendarState.INCOMPLETE).actionable(true).build();
        }
        if (nonWorking.contains(date)) {
            return out.state(RegularizationCalendarState.NON_WORKING).actionable(true).build();
        }
        return out.state(RegularizationCalendarState.MISSING).actionable(true).build();
    }

    private Attendance createPlaceholder(Employee employee, Project project, LocalDate date, Organization org) {
        if (employee.getShiftTiming() == null) {
            throw new ValidationException(
                    "No shift timing is assigned to this employee, so the day cannot be regularized. "
                            + "Ask an administrator to assign one");
        }
        Attendance draft = Attendance.builder()
                .employeeId(employee.getId())
                .employeeName(employee.getEmployeeName())
                .attendanceDate(date)
                .projectId(project.getId())
                .projectName(project.getProjectName())
                .shiftTiming(employee.getShiftTiming())
                .status(AttendanceStatus.PENDING_REGULARIZATION)
                .approvalStatus(ApprovalStatus.PENDING)
                .organization(org)
                .clockEvents(new ArrayList<>())
                .regularizations(new ArrayList<>())
                .movements(new ArrayList<>())
                .build();
        return attendanceRepository.save(draft);
    }

    private static ClockEventCreationDto event(ClockEventType type, LocalDateTime at, Long projectId) {
        ClockEventCreationDto e = new ClockEventCreationDto();
        e.setEventType(type);
        e.setEventTimestamp(at);
        e.setProjectId(projectId);
        return e;
    }

    private static Optional<AttendanceRegularization> latestRequest(Attendance attendance) {
        return attendance.getRegularizations().stream()
                .max(Comparator.comparing(AttendanceRegularization::getRequestedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(AttendanceRegularization::getId,
                                Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private static boolean hasPendingRequest(Attendance attendance) {
        return attendance.getRegularizations().stream()
                .anyMatch(r -> r.getStatus() == RegularizationStatus.PENDING);
    }

    private static boolean isLeave(Attendance attendance) {
        return attendance.getStatus() == AttendanceStatus.LEAVE || attendance.getLeaveId() != null;
    }

    private static boolean isComplete(Attendance attendance) {
        Set<ClockEventType> types = attendance.getClockEvents().stream()
                .map(ClockEvent::getEventType)
                .collect(Collectors.toSet());
        return types.contains(ClockEventType.MORNING_CLOCK_IN) && types.contains(ClockEventType.EVENING_CLOCK_OUT);
    }

    private static String leaveTypeName(LeaveRequest leave) {
        return leave.getLeavePolicy() == null ? null : leave.getLeavePolicy().getLeaveTypeName();
    }
}
