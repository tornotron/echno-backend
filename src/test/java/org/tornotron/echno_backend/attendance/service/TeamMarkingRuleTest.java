package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.Validation;
import jakarta.validation.ValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.TeamMarkingOutsideGeofenceException;
import org.tornotron.echno_backend.attendance.dto.AttendanceCheckInDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceClockEventDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.attendance.validator.GeofenceValidator;
import org.tornotron.echno_backend.common.entity.Attachment;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.Project;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a supervisor owes when they mark attendance for somebody else, and what the entry then
 * records (#839, ClickUp 86d45jzpa).
 *
 * <p>The rule, as decided: the subordinate is not asked for the site's selfie, because the
 * supervisor cannot take it for them; the supervisor's own position is measured against the site
 * and an entry from outside the fence is refused with the distance; self-marking keeps both rules
 * as they were; and every supervisor-marked entry says who marked it, from where and when.
 *
 * <p>Each rule has a test on the check-in path and, where the clock-event path applies its own
 * copy of the rule, one there too. The self-marking cases are here as the control: the same
 * request with {@code isSelfMarking} answering true has to keep failing, or the relaxation has
 * leaked past the supervisor.
 */
@ExtendWith(MockitoExtension.class)
class TeamMarkingRuleTest {

    private static final Long ORG_ID = 100L;
    private static final Long EMPLOYEE_ID = 7L;
    private static final Long SUPERVISOR_ID = 31L;
    private static final Long SUPERVISOR_USER_ID = 900L;
    private static final Long PROJECT_ID = 12L;
    private static final Long ATTENDANCE_ID = 781L;

    private static final double PROJECT_LAT = 13.0827;
    private static final double PROJECT_LON = 80.2707;
    /** About eight metres from the marker. */
    private static final double ON_SITE_LAT = PROJECT_LAT + 0.00005;
    private static final double ON_SITE_LON = PROJECT_LON + 0.00005;
    /** About 256 metres from the marker: outside a hundred-metre fence. */
    private static final double OFF_SITE_LAT = PROJECT_LAT + 0.0023;

    @Mock private AttendanceRepository attendanceRepository;
    @Mock private ShiftTimingRepository shiftTimingRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private AttendanceSettingsService settingsService;
    @Mock private AttendanceCalculationService calculationService;
    @Mock private ClockEventSequenceValidator sequenceValidator;
    @Mock private AttendanceMapper attendanceMapper;
    @Mock private AttachmentService attachmentService;
    @Mock private FileStorageService fileStorageService;
    @Mock private UserContextService userContextService;
    @Mock private AttendanceSecurityService attendanceSecurity;

    private AttendanceService service;
    private Organization organization;
    private Employee employee;
    private Project project;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new AttendanceService(attendanceRepository, shiftTimingRepository, employeeRepository,
                organizationRepository, projectRepository, settingsService, calculationService,
                sequenceValidator, attendanceMapper, attachmentService, fileStorageService,
                userContextService,
                new PayloadValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                attendanceSecurity,
                new AttendanceGeofenceService(new GeofenceValidator()));

        organization = new Organization();
        organization.setId(ORG_ID);

        employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        employee.setEmployeeName("Ravi Kumar");
        employee.setShiftTiming(new ShiftTiming());

        project = new Project();
        project.setId(PROJECT_ID);
        project.setProjectName("Asset Homes Kovilambakkam Phase 2");
        project.setProjectLatitude((float) PROJECT_LAT);
        project.setProjectLongitude((float) PROJECT_LON);
        project.setEmployees(new ArrayList<>());

        lenient().when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        lenient().when(employeeRepository.findByIdAndOrganizationId(EMPLOYEE_ID, ORG_ID))
                .thenReturn(Optional.of(employee));
        lenient().when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.of(project));
        lenient().when(attendanceRepository.save(any(Attendance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(attendanceMapper.toResponseDto(any(Attendance.class)))
                .thenReturn(AttendanceResponseDto.builder().build());
        lenient().when(attendanceSecurity.canRecordFor(anyLong())).thenReturn(true);
        lenient().when(attachmentService.uploadAttachment(any(), anyString(), any(), anyString()))
                .thenReturn(new Attachment());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void settingsRequire(boolean photoOnCheckIn, boolean photoOnCheckOut, boolean location) {
        when(settingsService.resolveEffectiveSettings(ORG_ID, PROJECT_ID)).thenReturn(
                AttendanceSettings.builder()
                        .photoRequiredOnCheckIn(photoOnCheckIn)
                        .photoRequiredOnCheckOut(photoOnCheckOut)
                        .geolocationRequired(location)
                        .geofenceRadiusMeters(100)
                        .build());
    }

    private void noRecordExistsForTheDay() {
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateAndProjectId(
                any(), any(), any())).thenReturn(Optional.empty());
    }

    private void callerIsTheSupervisor() {
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(false);
        Employee supervisor = new Employee();
        supervisor.setId(SUPERVISOR_ID);
        supervisor.setEmployeeName("Anand Rajashekar");
        // Lenient: a request refused before the punch is built never resolves the recorder.
        lenient().when(userContextService.getCurrentUserId()).thenReturn(SUPERVISOR_USER_ID);
        lenient().when(employeeRepository.findByUserIdAndOrganizationId(SUPERVISOR_USER_ID, ORG_ID))
                .thenReturn(Optional.of(supervisor));
    }

    private void callerIsTheEmployee() {
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(true);
    }

    private AttendanceCheckInDto checkInAt(Double latitude, Double longitude) {
        AttendanceCheckInDto dto = new AttendanceCheckInDto();
        dto.setEmployeeId(EMPLOYEE_ID);
        dto.setProjectId(PROJECT_ID);
        dto.setEventTimestamp(LocalDateTime.of(2026, 9, 20, 9, 2));
        dto.setLatitude(latitude);
        dto.setLongitude(longitude);
        return dto;
    }

    private Attendance anOpenDay() {
        Attendance attendance = Attendance.builder()
                .id(ATTENDANCE_ID)
                .employeeId(EMPLOYEE_ID)
                .employeeName(employee.getEmployeeName())
                .attendanceDate(LocalDate.of(2026, 9, 20))
                .projectId(PROJECT_ID)
                .projectName(project.getProjectName())
                .status(AttendanceStatus.PENDING_REGULARIZATION)
                .approvalStatus(ApprovalStatus.PENDING)
                .organization(organization)
                .clockEvents(new ArrayList<>())
                .regularizations(new ArrayList<>())
                .movements(new ArrayList<>())
                .build();
        when(attendanceRepository.findByIdAndOrganization_Id(ATTENDANCE_ID, ORG_ID))
                .thenReturn(Optional.of(attendance));
        return attendance;
    }

    private AttendanceClockEventDto clockOutAt(Double latitude, Double longitude) {
        AttendanceClockEventDto dto = new AttendanceClockEventDto();
        dto.setAttendanceId(ATTENDANCE_ID);
        dto.setEventType(ClockEventType.EVENING_CLOCK_OUT);
        dto.setEventTimestamp(LocalDateTime.of(2026, 9, 20, 18, 0));
        dto.setLatitude(latitude);
        dto.setLongitude(longitude);
        return dto;
    }

    private static MockMultipartFile aSelfie() {
        return new MockMultipartFile("photo", "selfie.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private ClockEvent savedEvent() {
        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        assertThat(captor.getValue().getClockEvents()).hasSize(1);
        return captor.getValue().getClockEvents().get(0);
    }

    // ---- Rule 1: the selfie -------------------------------------------------------------------

    @Test
    void aSupervisorIsNotAskedForTheSubordinatesSelfie_onCheckIn() {
        settingsRequire(true, false, true);
        noRecordExistsForTheDay();
        callerIsTheSupervisor();

        service.checkIn(checkInAt(ON_SITE_LAT, ON_SITE_LON), null);

        assertThat(savedEvent().getEventType()).isEqualTo(ClockEventType.MORNING_CLOCK_IN);
    }

    @Test
    void aSupervisorIsNotAskedForTheSubordinatesSelfie_onClockOut() {
        settingsRequire(false, true, true);
        anOpenDay();
        callerIsTheSupervisor();

        service.recordClockEvent(clockOutAt(ON_SITE_LAT, ON_SITE_LON), null);

        assertThat(savedEvent().getEventType()).isEqualTo(ClockEventType.EVENING_CLOCK_OUT);
    }

    @Test
    void theEmployeeStillOwesTheSelfie_onCheckIn() {
        // The control: the same request, self-marked, is refused as before.
        settingsRequire(true, false, true);
        callerIsTheEmployee();

        assertThatThrownBy(() -> service.checkIn(checkInAt(ON_SITE_LAT, ON_SITE_LON), null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("photo is required");

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void theEmployeeStillOwesTheSelfie_onClockOut() {
        settingsRequire(false, true, true);
        anOpenDay();
        callerIsTheEmployee();

        assertThatThrownBy(() -> service.recordClockEvent(clockOutAt(ON_SITE_LAT, ON_SITE_LON), null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("photo is required");

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void aSelfieASupervisorDoesSendIsStillAttached() {
        // Not required is not the same as refused: a supervisor with a photo of the crew keeps it.
        settingsRequire(true, false, true);
        noRecordExistsForTheDay();
        callerIsTheSupervisor();

        service.checkIn(checkInAt(ON_SITE_LAT, ON_SITE_LON), aSelfie());

        verify(attachmentService).uploadAttachment(any(), anyString(), any(), anyString());
    }

    // ---- Rule 2: the supervisor's own position -------------------------------------------------

    @Test
    void aSupervisorOnSiteIsAccepted_andTheEntryRecordsThemAndTheirPosition() {
        settingsRequire(false, false, true);
        noRecordExistsForTheDay();
        callerIsTheSupervisor();

        service.checkIn(checkInAt(ON_SITE_LAT, ON_SITE_LON), null);

        ClockEvent event = savedEvent();
        // Who, and from where.
        assertThat(event.getRecordedById()).isEqualTo(SUPERVISOR_ID);
        assertThat(event.getRecordedByName()).isEqualTo("Anand Rajashekar");
        assertThat(event.getRecordedByLatitude()).isEqualTo(ON_SITE_LAT);
        assertThat(event.getRecordedByLongitude()).isEqualTo(ON_SITE_LON);
        assertThat(event.getRecordedByDistanceMeters()).isCloseTo(7.8, within(2.0));
        // The employee's own verdict stays unevaluated: the position measured is the
        // supervisor's, and these three columns describe the employee.
        assertThat(event.getIsWithinGeofence()).isNull();
        assertThat(event.getDistanceFromProject()).isNull();
        assertThat(event.getGeofenceRadiusMeters()).isNull();
    }

    @Test
    void aSupervisorOutsideTheFenceIsRefused_withTheDistance_onCheckIn() {
        settingsRequire(false, false, true);
        noRecordExistsForTheDay();
        callerIsTheSupervisor();

        assertThatThrownBy(() -> service.checkIn(checkInAt(OFF_SITE_LAT, PROJECT_LON), null))
                .isInstanceOf(TeamMarkingOutsideGeofenceException.class)
                .asInstanceOf(throwable(TeamMarkingOutsideGeofenceException.class))
                .satisfies(ex -> {
                    assertThat(ex.getDistanceMeters()).isCloseTo(256.0, within(5.0));
                    assertThat(ex.getRadiusMeters()).isEqualTo(100);
                    assertThat(ex.getMessage()).contains("256 m").contains("100 m");
                });

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void aSupervisorOutsideTheFenceIsRefused_onClockOut() {
        settingsRequire(false, false, true);
        Attendance day = anOpenDay();
        callerIsTheSupervisor();

        assertThatThrownBy(() -> service.recordClockEvent(clockOutAt(OFF_SITE_LAT, PROJECT_LON), null))
                .isInstanceOf(TeamMarkingOutsideGeofenceException.class);

        assertThat(day.getClockEvents()).isEmpty();
        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void aSupervisorOutsideTheFenceCannotTalkTheirWayIn() {
        // The self-marking escape hatch, a reason plus a held day, is not offered to a supervisor.
        settingsRequire(false, false, true);
        noRecordExistsForTheDay();
        callerIsTheSupervisor();
        AttendanceCheckInDto dto = checkInAt(OFF_SITE_LAT, PROJECT_LON);
        dto.setGeofenceExceptionReason("Marking from the site office across the road");

        assertThatThrownBy(() -> service.checkIn(dto, null))
                .isInstanceOf(TeamMarkingOutsideGeofenceException.class);
    }

    @Test
    void aSupervisorStillHasToSendAPositionWhereTheSiteRequiresOne() {
        // The GPS rule is kept and now applies to the supervisor's own device.
        settingsRequire(false, false, true);
        callerIsTheSupervisor();

        assertThatThrownBy(() -> service.checkIn(checkInAt(null, null), null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Latitude and longitude are required");
    }

    @Test
    void aProjectWithNoCoordinatesRefusesNobody() {
        // Nothing to measure against, so the supervisor is taken at their word, as an employee
        // marking themselves would be. The position is still kept; only the distance is absent.
        project.setProjectLatitude(null);
        project.setProjectLongitude(null);
        settingsRequire(false, false, true);
        noRecordExistsForTheDay();
        callerIsTheSupervisor();

        service.checkIn(checkInAt(OFF_SITE_LAT, PROJECT_LON), null);

        ClockEvent event = savedEvent();
        assertThat(event.getRecordedByLatitude()).isEqualTo(OFF_SITE_LAT);
        assertThat(event.getRecordedByDistanceMeters()).isNull();
    }

    // ---- Rule 3: self-marking is untouched --------------------------------------------------------

    @Test
    void aSelfMarkedPunchCarriesNoRecorderPosition() {
        settingsRequire(false, false, true);
        noRecordExistsForTheDay();
        callerIsTheEmployee();
        when(userContextService.getCurrentUserId()).thenReturn(SUPERVISOR_USER_ID);
        when(employeeRepository.findByUserIdAndOrganizationId(SUPERVISOR_USER_ID, ORG_ID))
                .thenReturn(Optional.of(employee));

        service.checkIn(checkInAt(ON_SITE_LAT, ON_SITE_LON), null);

        ClockEvent event = savedEvent();
        // The employee's own verdict is reached, as before.
        assertThat(event.getIsWithinGeofence()).isTrue();
        assertThat(event.getRecordedById()).isEqualTo(EMPLOYEE_ID);
        assertThat(event.getRecordedByName()).isEqualTo("Ravi Kumar");
        // And the recorder position columns stay empty: they mean "somebody else stood here".
        assertThat(event.getRecordedByLatitude()).isNull();
        assertThat(event.getRecordedByLongitude()).isNull();
        assertThat(event.getRecordedByDistanceMeters()).isNull();
    }
}
