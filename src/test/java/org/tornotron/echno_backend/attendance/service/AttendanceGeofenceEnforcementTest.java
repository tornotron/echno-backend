package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.dto.AttendanceCheckInDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.attendance.validator.GeofenceValidator;
import org.tornotron.echno_backend.common.exception.GeofenceExceptionReasonRequiredException;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a clock event records about the geofence, and what happens when a self-marked punch falls
 * outside it.
 *
 * <p>Before this, every clock event in the system was built with two literals: {@code
 * isWithinGeofence(false)} and {@code distanceFromProject(0.0)}. Nothing measured anything. The
 * browser blocked out-of-range clock-ins and the server then stored the opposite of what the
 * browser had just verified, which is how the gap stayed invisible: the product visibly enforced a
 * geofence while the two rows on staging said an employee standing nine metres from the site was
 * outside it, and zero metres away, at the same time.
 *
 * <p>Each test below fails on the old code, and the first three fail by the assertion rather than
 * by an error, which is the point: the old values were well-formed and wrong.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceGeofenceEnforcementTest {

    private static final Long ORG_ID = 100L;
    private static final Long EMPLOYEE_ID = 7L;
    private static final Long MANAGER_ID = 55L;
    private static final Long PROJECT_ID = 12L;

    private static final double PROJECT_LAT = 13.0827;
    private static final double PROJECT_LON = 80.2707;
    /** About eight metres from the marker: on site by any reading. */
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
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** The whole happy path up to the save, with the geofence inputs all present. */
    private void givenACheckInIsPossible(Float projectLatitude, Float projectLongitude) {
        Organization organization = new Organization();
        organization.setId(ORG_ID);

        Employee manager = new Employee();
        manager.setId(MANAGER_ID);
        Employee employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        employee.setEmployeeName("Site engineer");
        employee.setShiftTiming(new ShiftTiming());
        employee.setManager(manager);

        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setProjectName("Asset Homes Kovilambakkam Phase 2");
        project.setProjectLatitude(projectLatitude);
        project.setProjectLongitude(projectLongitude);
        project.setEmployees(new ArrayList<>());

        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization));
        when(employeeRepository.findByIdAndOrganizationId(EMPLOYEE_ID, ORG_ID))
                .thenReturn(Optional.of(employee));
        when(projectRepository.findByIdAndOrganization_Id(PROJECT_ID, ORG_ID))
                .thenReturn(Optional.of(project));
        when(settingsService.resolveEffectiveSettings(ORG_ID, PROJECT_ID)).thenReturn(
                AttendanceSettings.builder()
                        .photoRequiredOnCheckIn(false)
                        .geolocationRequired(true)
                        .geofenceRadiusMeters(100)
                        .build());
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateAndProjectId(
                any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(attendanceRepository.save(any(Attendance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(attendanceMapper.toResponseDto(any(Attendance.class)))
                .thenReturn(AttendanceResponseDto.builder().build());
        lenient().when(attendanceSecurity.canRecordFor(EMPLOYEE_ID)).thenReturn(true);
    }

    private void givenACheckInIsPossible() {
        givenACheckInIsPossible((float) PROJECT_LAT, (float) PROJECT_LON);
    }

    private AttendanceCheckInDto checkInAt(Double latitude, Double longitude, String reason) {
        AttendanceCheckInDto dto = new AttendanceCheckInDto();
        dto.setEmployeeId(EMPLOYEE_ID);
        dto.setProjectId(PROJECT_ID);
        dto.setEventTimestamp(LocalDateTime.of(2026, 9, 6, 9, 2));
        dto.setLatitude(latitude);
        dto.setLongitude(longitude);
        dto.setGeofenceExceptionReason(reason);
        return dto;
    }

    private ClockEvent savedEvent() {
        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        assertThat(captor.getValue().getClockEvents()).hasSize(1);
        return captor.getValue().getClockEvents().get(0);
    }

    private Attendance savedAttendance() {
        ArgumentCaptor<Attendance> captor = ArgumentCaptor.forClass(Attendance.class);
        verify(attendanceRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void aPunchOnSiteIsRecordedAsInsideTheFence_withTheMeasuredDistance() {
        // Fails on the old code by assertion: the event was built with isWithinGeofence(false) and
        // distanceFromProject(0.0) whatever the coordinates said. This is the staging defect in
        // miniature, where a punch nine metres from the site was filed as a violation.
        givenACheckInIsPossible();
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(true);

        service.checkIn(checkInAt(ON_SITE_LAT, ON_SITE_LON, null), null);

        ClockEvent event = savedEvent();
        assertThat(event.getIsWithinGeofence()).isTrue();
        assertThat(event.getDistanceFromProject()).isCloseTo(7.8, within(2.0));
        assertThat(event.getGeofenceRadiusMeters()).isEqualTo(100);
        assertThat(event.getGeofenceExceptionReason()).isNull();
        assertThat(savedAttendance().getRequiresGeofenceApproval()).isFalse();
    }

    @Test
    void aProjectWithNoCoordinatesLeavesTheEventUnevaluated() {
        // Fails on the old code by assertion, and this is the one that could not be fixed later:
        // the old false and 0.0 were indistinguishable from a measured violation, so a null here
        // is the only thing that keeps "nobody looked" readable.
        givenACheckInIsPossible(null, null);
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(true);

        service.checkIn(checkInAt(ON_SITE_LAT, ON_SITE_LON, null), null);

        ClockEvent event = savedEvent();
        assertThat(event.getIsWithinGeofence()).isNull();
        assertThat(event.getDistanceFromProject()).isNull();
        assertThat(event.getGeofenceRadiusMeters()).isNull();
    }

    @Test
    void aSelfMarkedPunchOutsideTheFence_withNoReason_asksForOne() {
        // Fails on the old code by the check-in going through and storing a record.
        givenACheckInIsPossible();
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(true);

        // The distance and the radius travel with the exception so the prompt can tell the
        // employee how far out they are rather than repeating a generic refusal.
        assertThatThrownBy(() -> service.checkIn(checkInAt(OFF_SITE_LAT, PROJECT_LON, null), null))
                .isInstanceOf(GeofenceExceptionReasonRequiredException.class)
                .asInstanceOf(throwable(GeofenceExceptionReasonRequiredException.class))
                .satisfies(ex -> {
                    assertThat(ex.getDistanceMeters()).isCloseTo(256.0, within(5.0));
                    assertThat(ex.getRadiusMeters()).isEqualTo(100);
                });

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void aSelfMarkedPunchOutsideTheFence_withAReason_isAcceptedAndHeldForTheReportingManager() {
        // The product decision: being outside the fence does not block the mark. A site engineer
        // at head office marks attendance, says why, and their manager decides.
        givenACheckInIsPossible();
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(true);

        service.checkIn(checkInAt(OFF_SITE_LAT, PROJECT_LON,
                "  At head office for the client review  "), null);

        ClockEvent event = savedEvent();
        assertThat(event.getIsWithinGeofence()).isFalse();
        assertThat(event.getDistanceFromProject()).isCloseTo(256.0, within(5.0));
        assertThat(event.getGeofenceExceptionReason())
                .isEqualTo("At head office for the client review");

        Attendance attendance = savedAttendance();
        assertThat(attendance.getRequiresGeofenceApproval()).isTrue();
        assertThat(attendance.getGeofenceApproverId()).isEqualTo(MANAGER_ID);
        assertThat(attendance.getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void aBlankReasonIsNotAReason() {
        givenACheckInIsPossible();
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.checkIn(checkInAt(OFF_SITE_LAT, PROJECT_LON, "   "), null))
                .isInstanceOf(GeofenceExceptionReasonRequiredException.class);
    }

    @Test
    void aPunchEnteredForSomebodyElseIsLeftUnevaluated_andIsNotHeld() {
        // A supervisor marking their team sends their own device's position for somebody else's
        // day. Judging the employee by where their supervisor was standing would put a claim about
        // the wrong person in the same column as real measurements, which is the defect this whole
        // evaluation exists to remove. So the punch is recorded, unevaluated, and recordedById
        // says why. Nothing about the mark-for-team path is gated.
        givenACheckInIsPossible();
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(false);

        service.checkIn(checkInAt(OFF_SITE_LAT, PROJECT_LON, null), null);

        ClockEvent event = savedEvent();
        assertThat(event.getIsWithinGeofence()).isNull();
        assertThat(event.getDistanceFromProject()).isNull();
        assertThat(event.getGeofenceExceptionReason()).isNull();
        assertThat(savedAttendance().getRequiresGeofenceApproval()).isFalse();
        assertThat(savedAttendance().getGeofenceApproverId()).isNull();
    }

    @Test
    void thePunchRecordsWhoSubmittedIt() {
        // Without this the null verdict above is unreadable: a reader cannot tell a punch entered
        // by a supervisor from one taken against a project that has no coordinates.
        givenACheckInIsPossible();
        Employee supervisor = new Employee();
        supervisor.setId(31L);
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(false);
        when(userContextService.getCurrentUserId()).thenReturn(900L);
        when(employeeRepository.findByUserIdAndOrganizationId(900L, ORG_ID))
                .thenReturn(Optional.of(supervisor));

        service.checkIn(checkInAt(ON_SITE_LAT, ON_SITE_LON, null), null);

        assertThat(savedEvent().getRecordedById()).isEqualTo(31L);
    }
}
