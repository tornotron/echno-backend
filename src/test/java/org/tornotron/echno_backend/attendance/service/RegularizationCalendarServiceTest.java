package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRegularization;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.dto.ClockEventCreationDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationByDateRequestDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationCalendarDayDto;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.enums.RegularizationCalendarState;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.attendance.service.AttendanceActorResolver.Actor;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RegularizationCalendarService}: raising a request by date, with the record
 * created when the day has none, and the per-day states the calendar reads.
 */
@ExtendWith(MockitoExtension.class)
class RegularizationCalendarServiceTest {

    private static final Long ORG = 100L;
    private static final Long EMP = 18L;
    private static final Long PROJECT = 12L;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private LeaveRequestRepository leaveRequestRepository;
    @Mock private WorkingCalendarService workingCalendarService;
    @Mock private AttendanceSecurityService attendanceSecurity;
    @Mock private AttendanceActorResolver actorResolver;
    @Mock private AttendanceRegularizationService regularizationService;

    private RegularizationCalendarService service;
    private Employee employee;
    private Organization org;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        Clock clock = Clock.fixed(Instant.parse("2026-09-25T06:00:00Z"), ZoneId.of("Asia/Kolkata"));
        service = new RegularizationCalendarService(attendanceRepository, employeeRepository,
                projectRepository, organizationRepository, leaveRequestRepository,
                workingCalendarService, attendanceSecurity, actorResolver, regularizationService, clock);

        org = new Organization();
        org.setId(ORG);
        employee = new Employee();
        employee.setId(EMP);
        employee.setEmployeeName("Ravi");
        employee.setShiftTiming(new ShiftTiming());
        Project project = new Project();
        project.setId(PROJECT);
        project.setProjectName("Tower B");

        lenient().when(attendanceSecurity.canRecordFor(EMP)).thenReturn(true);
        lenient().when(organizationRepository.findById(ORG)).thenReturn(Optional.of(org));
        lenient().when(employeeRepository.findByIdAndOrganizationId(EMP, ORG)).thenReturn(Optional.of(employee));
        lenient().when(projectRepository.findByIdAndOrganization_Id(PROJECT, ORG)).thenReturn(Optional.of(project));
        lenient().when(leaveRequestRepository.findOverlappingRequests(eq(EMP), any(), any(), isNull()))
                .thenReturn(List.of());
        lenient().when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(eq(EMP), any(), any()))
                .thenReturn(List.of());
        lenient().when(actorResolver.resolveCurrentActor()).thenReturn(new Actor("Ravi", 500L, EMP));
        lenient().when(attendanceRepository.save(any(Attendance.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(regularizationService.fileRequest(any(), any(), any(), any(), anyList(), anyList()))
                .thenReturn(AttendanceRegularizationDto.builder().id(7L).build());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private RegularizationByDateRequestDto request(LocalDate date, LocalTime in, LocalTime out) {
        return new RegularizationByDateRequestDto(EMP, PROJECT, date, "Phone was dead", in, out);
    }

    private Attendance record(LocalDate date, ClockEventType... events) {
        Attendance a = Attendance.builder().id(date.getDayOfMonth() * 10L).employeeId(EMP)
                .attendanceDate(date).projectId(PROJECT).projectName("Tower B")
                .status(AttendanceStatus.PRESENT).build();
        for (ClockEventType type : events) {
            a.getClockEvents().add(ClockEvent.builder().eventType(type)
                    .eventTimestamp(date.atTime(9, 0)).build());
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    private List<ClockEventCreationDto> filedEvents(ArgumentCaptor<Attendance> attendance) {
        ArgumentCaptor<List<ClockEventCreationDto>> events = ArgumentCaptor.forClass(List.class);
        verify(regularizationService).fileRequest(attendance.capture(), eq(org), any(),
                eq("Phone was dead"), anyList(), events.capture());
        return events.getValue();
    }

    // ─── Request by date ────────────────────────────────────────────────────────────────────

    @Test
    void submitByDate_withNoRecord_createsThePendingDayAndFilesBothEvents() {
        service.submitByDate(request(DAY, LocalTime.of(9, 5), LocalTime.of(18, 10)));

        ArgumentCaptor<Attendance> attendance = ArgumentCaptor.forClass(Attendance.class);
        List<ClockEventCreationDto> events = filedEvents(attendance);

        Attendance created = attendance.getValue();
        assertThat(created.getStatus()).isEqualTo(AttendanceStatus.PENDING_REGULARIZATION);
        assertThat(created.getClockEvents()).isEmpty();
        assertThat(created.getAttendanceDate()).isEqualTo(DAY);
        assertThat(created.getProjectId()).isEqualTo(PROJECT);
        assertThat(created.getShiftTiming()).isSameAs(employee.getShiftTiming());
        assertThat(events).extracting(ClockEventCreationDto::getEventType)
                .containsExactly(ClockEventType.MORNING_CLOCK_IN, ClockEventType.EVENING_CLOCK_OUT);
        assertThat(events).extracting(ClockEventCreationDto::getEventTimestamp)
                .containsExactly(LocalDateTime.of(DAY, LocalTime.of(9, 5)),
                        LocalDateTime.of(DAY, LocalTime.of(18, 10)));
        assertThat(events).allMatch(e -> PROJECT.equals(e.getProjectId()));
    }

    @Test
    void submitByDate_onARecordWithOnlyAClockIn_reusesItAndAsksForTheClockOut() {
        Attendance existing = record(DAY, ClockEventType.MORNING_CLOCK_IN);
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(EMP, DAY, DAY))
                .thenReturn(List.of(existing));

        service.submitByDate(request(DAY, LocalTime.of(9, 5), LocalTime.of(18, 10)));

        ArgumentCaptor<Attendance> attendance = ArgumentCaptor.forClass(Attendance.class);
        List<ClockEventCreationDto> events = filedEvents(attendance);
        assertThat(attendance.getValue()).isSameAs(existing);
        assertThat(events).extracting(ClockEventCreationDto::getEventType)
                .containsExactly(ClockEventType.EVENING_CLOCK_OUT);
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void submitByDate_onACompleteRecord_isRefused() {
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(EMP, DAY, DAY))
                .thenReturn(List.of(record(DAY, ClockEventType.MORNING_CLOCK_IN, ClockEventType.EVENING_CLOCK_OUT)));

        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.submitByDate(request(DAY, LocalTime.of(9, 0), LocalTime.of(18, 0))));
        verify(regularizationService, never()).fileRequest(any(), any(), any(), any(), anyList(), anyList());
    }

    @Test
    void submitByDate_forAFutureDate_isRefused() {
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.submitByDate(request(TODAY.plusDays(1), LocalTime.of(9, 0), null)));
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void submitByDate_forToday_isAllowedInTheSiteZone() {
        service.submitByDate(request(TODAY, LocalTime.of(9, 0), null));

        ArgumentCaptor<Attendance> attendance = ArgumentCaptor.forClass(Attendance.class);
        assertThat(filedEvents(attendance)).extracting(ClockEventCreationDto::getEventType)
                .containsExactly(ClockEventType.MORNING_CLOCK_IN);
    }

    @Test
    void submitByDate_withAClockOutBeforeTheClockIn_isRefused() {
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.submitByDate(request(DAY, LocalTime.of(18, 0), LocalTime.of(9, 0))));
    }

    @Test
    void submitByDate_forSomeoneElseWithoutTheRole_isForbidden() {
        when(attendanceSecurity.canRecordFor(EMP)).thenReturn(false);

        assertThatExceptionOfType(AccessDeniedException.class)
                .isThrownBy(() -> service.submitByDate(request(DAY, LocalTime.of(9, 0), null)));
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void submitByDate_onApprovedLeave_isRefused() {
        LeaveRequest leave = leave(DAY, DAY, LeaveStatus.APPROVED);
        when(leaveRequestRepository.findOverlappingRequests(EMP, DAY, DAY, null)).thenReturn(List.of(leave));

        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.submitByDate(request(DAY, LocalTime.of(9, 0), null)));
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void submitByDate_whenTheDayAlreadyHasAPendingRequest_isRefused() {
        Attendance other = record(DAY, ClockEventType.MORNING_CLOCK_IN);
        other.setProjectId(99L);
        other.getRegularizations().add(AttendanceRegularization.builder()
                .status(RegularizationStatus.PENDING).build());
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(EMP, DAY, DAY))
                .thenReturn(List.of(other));

        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.submitByDate(request(DAY, LocalTime.of(9, 0), null)));
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void submitByDate_forAnEmployeeWithNoShift_isRefusedBeforeCreatingAnything() {
        employee.setShiftTiming(null);

        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.submitByDate(request(DAY, LocalTime.of(9, 0), null)));
        verify(attendanceRepository, never()).save(any());
    }

    // ─── Calendar ────────────────────────────────────────────────────────────────────────────

    @Test
    void calendar_readsEachDayFromItsRecordsLeaveAndTheWorkingWeek() {
        LocalDate complete = LocalDate.of(2026, 9, 1);
        LocalDate incomplete = LocalDate.of(2026, 9, 2);
        LocalDate pending = LocalDate.of(2026, 9, 3);
        LocalDate regularized = LocalDate.of(2026, 9, 4);
        LocalDate sunday = LocalDate.of(2026, 9, 6);
        LocalDate leaveDay = LocalDate.of(2026, 9, 8);
        LocalDate pendingLeave = LocalDate.of(2026, 9, 9);
        LocalDate missing = LocalDate.of(2026, 9, 10);

        Attendance pendingRecord = record(pending);
        pendingRecord.setStatus(AttendanceStatus.PENDING_REGULARIZATION);
        pendingRecord.getRegularizations().add(AttendanceRegularization.builder().id(3L)
                .status(RegularizationStatus.PENDING).build());
        Attendance regularizedRecord = record(regularized, ClockEventType.MORNING_CLOCK_IN,
                ClockEventType.EVENING_CLOCK_OUT);
        regularizedRecord.getRegularizations().add(AttendanceRegularization.builder().id(4L)
                .status(RegularizationStatus.APPROVED).build());

        when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(
                EMP, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(List.of(
                        record(complete, ClockEventType.MORNING_CLOCK_IN, ClockEventType.EVENING_CLOCK_OUT),
                        record(incomplete, ClockEventType.MORNING_CLOCK_IN),
                        pendingRecord, regularizedRecord));
        when(leaveRequestRepository.findOverlappingRequests(EMP, LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), null))
                .thenReturn(List.of(leave(leaveDay, leaveDay, LeaveStatus.APPROVED),
                        leave(pendingLeave, pendingLeave, LeaveStatus.PENDING_APPROVAL)));
        when(workingCalendarService.nonWorkingDays(any(), any())).thenReturn(Set.of(sunday));

        List<RegularizationCalendarDayDto> days = service.calendar(EMP, 2026, 9);
        Map<LocalDate, RegularizationCalendarDayDto> byDate = days.stream()
                .collect(Collectors.toMap(RegularizationCalendarDayDto::getDate, d -> d));

        assertThat(days).hasSize(30);
        assertThat(byDate.get(complete).getState()).isEqualTo(RegularizationCalendarState.COMPLETE);
        assertThat(byDate.get(complete).isActionable()).isFalse();
        assertThat(byDate.get(incomplete).getState()).isEqualTo(RegularizationCalendarState.INCOMPLETE);
        assertThat(byDate.get(incomplete).isActionable()).isTrue();
        assertThat(byDate.get(pending).getState()).isEqualTo(RegularizationCalendarState.PENDING);
        assertThat(byDate.get(pending).getRegularizationId()).isEqualTo(3L);
        assertThat(byDate.get(pending).isActionable()).isFalse();
        assertThat(byDate.get(regularized).getState()).isEqualTo(RegularizationCalendarState.REGULARIZED);
        assertThat(byDate.get(sunday).getState()).isEqualTo(RegularizationCalendarState.NON_WORKING);
        assertThat(byDate.get(leaveDay).getState()).isEqualTo(RegularizationCalendarState.LEAVE);
        assertThat(byDate.get(leaveDay).isActionable()).isFalse();
        assertThat(byDate.get(pendingLeave).getState()).isEqualTo(RegularizationCalendarState.LEAVE_PENDING);
        assertThat(byDate.get(missing).getState()).isEqualTo(RegularizationCalendarState.MISSING);
        assertThat(byDate.get(missing).isActionable()).isTrue();
        assertThat(byDate.get(TODAY).getState()).isEqualTo(RegularizationCalendarState.MISSING);
        assertThat(byDate.get(TODAY.plusDays(1)).getState()).isEqualTo(RegularizationCalendarState.FUTURE);
        assertThat(byDate.get(TODAY.plusDays(1)).isActionable()).isFalse();
    }

    @Test
    void calendar_showsTheRejectionOfTheLatestRequest() {
        Attendance rejectedDay = record(DAY);
        rejectedDay.setStatus(AttendanceStatus.ABSENT);
        rejectedDay.getRegularizations().add(AttendanceRegularization.builder().id(5L)
                .status(RegularizationStatus.REJECTED).rejectionReason("No site register entry")
                .requestedAt(LocalDateTime.of(2026, 9, 23, 10, 0)).build());
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(eq(EMP), any(), any()))
                .thenReturn(List.of(rejectedDay));
        when(workingCalendarService.nonWorkingDays(any(), any())).thenReturn(Set.of());

        RegularizationCalendarDayDto day = service.calendar(EMP, 2026, 9).get(DAY.getDayOfMonth() - 1);

        assertThat(day.getState()).isEqualTo(RegularizationCalendarState.MISSING);
        assertThat(day.isActionable()).isTrue();
        assertThat(day.getAttendanceId()).isEqualTo(rejectedDay.getId());
        assertThat(day.getRegularizationStatus()).isEqualTo(RegularizationStatus.REJECTED);
        assertThat(day.getRejectionReason()).isEqualTo("No site register entry");
    }

    @Test
    void calendar_withAnOutOfRangeMonth_isRefused() {
        assertThatExceptionOfType(ValidationException.class)
                .isThrownBy(() -> service.calendar(EMP, 2026, 13));
    }

    private LeaveRequest leave(LocalDate from, LocalDate to, LeaveStatus status) {
        LeaveRequest leave = new LeaveRequest();
        leave.setStartDate(from);
        leave.setEndDate(to);
        leave.setStatus(status);
        return leave;
    }
}
