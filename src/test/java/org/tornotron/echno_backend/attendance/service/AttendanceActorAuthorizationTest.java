package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRegularizationRepository;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.MovementRecordRepository;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.dto.AttendanceCheckInDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceClockEventDto;
import org.tornotron.echno_backend.attendance.dto.MovementRecordCreationDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationRequestDto;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.enums.MovementType;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.mapper.AttendanceRegularizationMapper;
import org.tornotron.echno_backend.attendance.mapper.MovementRecordMapper;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who may record attendance, and against whose record.
 *
 * <p>Check-in, clock events and movements were guarded by tenant membership alone while the
 * employee, or the attendance record, arrived as an id on the request. The services loaded
 * whatever the id named and never asked who was calling, so any member of a tenant could
 * fabricate a colleague's check-in, clock them out, log movements on their trail, or file a
 * regularization against their record; on a project configured to auto-approve regularizations
 * the corrections were applied on the spot. The same shape the leave family had
 * ({@code LeaveRequestActorAuthorizationTest}), in the module that feeds worked hours.
 *
 * <p>What settles it now is the record (or, on a check-in, the employee the payload names),
 * checked in the service against the caller: the employee themselves, or a holder of an
 * attendance record-management role. These tests fail on the old code by the write going
 * through.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceActorAuthorizationTest {

    private static final Long ORG_ID = 100L;
    private static final Long CALLER_EMPLOYEE_ID = 7L;
    private static final Long COLLEAGUE_EMPLOYEE_ID = 8L;
    private static final Long ATTENDANCE_ID = 781L;

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
    @Mock private MovementRecordRepository movementRecordRepository;
    @Mock private MovementRecordMapper movementRecordMapper;

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private AttendanceService attendanceService() {
        return new AttendanceService(attendanceRepository, shiftTimingRepository, employeeRepository,
                organizationRepository, projectRepository, settingsService, calculationService,
                sequenceValidator, attendanceMapper, attachmentService, fileStorageService,
                userContextService,
                new PayloadValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                attendanceSecurity);
    }

    private MovementRecordService movementRecordService() {
        return new MovementRecordService(movementRecordRepository, attendanceRepository,
                employeeRepository, organizationRepository, settingsService, movementRecordMapper,
                attendanceSecurity);
    }

    private Organization organization() {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        return organization;
    }

    /** A colleague's attendance for the day, the record the caller has no business writing to. */
    private Attendance colleaguesAttendance() {
        Attendance attendance = new Attendance();
        attendance.setId(ATTENDANCE_ID);
        attendance.setEmployeeId(COLLEAGUE_EMPLOYEE_ID);
        attendance.setEmployeeName("Colleague");
        attendance.setProjectId(12L);
        attendance.setOrganization(organization());
        return attendance;
    }

    private AttendanceCheckInDto checkInDto(Long employeeId) {
        AttendanceCheckInDto dto = new AttendanceCheckInDto();
        dto.setEmployeeId(employeeId);
        dto.setProjectId(12L);
        dto.setEventTimestamp(LocalDateTime.of(2026, 8, 31, 9, 2));
        return dto;
    }

    private AttendanceClockEventDto clockEventDto() {
        AttendanceClockEventDto dto = new AttendanceClockEventDto();
        dto.setAttendanceId(ATTENDANCE_ID);
        dto.setEventType(ClockEventType.LUNCH_BREAK_START);
        dto.setEventTimestamp(LocalDateTime.of(2026, 8, 31, 13, 0));
        return dto;
    }

    private MovementRecordCreationDto movementDto() {
        MovementRecordCreationDto dto = new MovementRecordCreationDto();
        dto.setAttendanceId(ATTENDANCE_ID);
        dto.setMovementType(MovementType.SITE_TRAVEL);
        dto.setFromLocation("Block A");
        dto.setStartTime(LocalDateTime.of(2026, 8, 31, 11, 0));
        dto.setPurpose("Material delivery");
        return dto;
    }

    /** Nobody in particular: a plain member of the tenant, no record-management role. */
    private void callerMayRecordOnlyFor(Long employeeId) {
        lenient().when(attendanceSecurity.canRecordFor(any(Long.class)))
                .thenAnswer(invocation -> invocation.getArgument(0).equals(employeeId));
    }

    @Test
    void checkIn_isRefused_forSomebodyElse() {
        // The hole as it stood: any member of the tenant naming a colleague's employee id in the
        // multipart data part fabricated a day of attendance for them.
        callerMayRecordOnlyFor(CALLER_EMPLOYEE_ID);

        assertThatThrownBy(() -> attendanceService().checkIn(checkInDto(COLLEAGUE_EMPLOYEE_ID), null))
                .isInstanceOf(AccessDeniedException.class);

        // Refused before anything is looked up, so nothing is written.
        verifyNoInteractions(organizationRepository, employeeRepository, attendanceRepository);
    }

    @Test
    void checkIn_isAllowed_forYourself() {
        // The ordinary case keeps working. Stops at the tenant lookup, mocked empty, which is
        // past the guard: what matters is that the refusal is not an authorization one.
        callerMayRecordOnlyFor(CALLER_EMPLOYEE_ID);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> attendanceService().checkIn(checkInDto(CALLER_EMPLOYEE_ID), null))
                .isNotInstanceOf(AccessDeniedException.class);
    }

    @Test
    void recordClockEvent_isRefused_onSomebodyElsesRecord() {
        // The sharper half: the payload names only an attendance id, so the old guard had no
        // employee to check at all and any member could clock a colleague out.
        callerMayRecordOnlyFor(CALLER_EMPLOYEE_ID);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization()));
        when(attendanceRepository.findByIdAndOrganization_Id(ATTENDANCE_ID, ORG_ID))
                .thenReturn(Optional.of(colleaguesAttendance()));

        assertThatThrownBy(() -> attendanceService().recordClockEvent(clockEventDto(), null))
                .isInstanceOf(AccessDeniedException.class);

        verify(attendanceRepository, never()).save(any());
        verifyNoInteractions(sequenceValidator);
    }

    @Test
    void recordClockEvent_isAllowed_onYourOwnRecord() {
        callerMayRecordOnlyFor(CALLER_EMPLOYEE_ID);
        Attendance own = colleaguesAttendance();
        own.setEmployeeId(CALLER_EMPLOYEE_ID);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization()));
        when(attendanceRepository.findByIdAndOrganization_Id(ATTENDANCE_ID, ORG_ID))
                .thenReturn(Optional.of(own));
        when(settingsService.resolveEffectiveSettings(ORG_ID, 12L))
                .thenReturn(AttendanceSettings.builder().geolocationRequired(false).build());
        when(attendanceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        attendanceService().recordClockEvent(clockEventDto(), null);

        verify(attendanceRepository).save(any());
    }

    @Test
    void addMovement_isRefused_forSomebodyElse() {
        callerMayRecordOnlyFor(CALLER_EMPLOYEE_ID);

        assertThatThrownBy(() -> movementRecordService().addMovement(movementDto(), COLLEAGUE_EMPLOYEE_ID))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(organizationRepository, employeeRepository, attendanceRepository,
                movementRecordRepository);
    }

    @Test
    void addMovement_isRefused_whenYourIdIsPassedWithSomebodyElsesAttendance() {
        // Naming yourself does not help when the attendance trail being written to is not yours:
        // the record's own employee is read back off the store and checked too.
        callerMayRecordOnlyFor(CALLER_EMPLOYEE_ID);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization()));
        when(employeeRepository.findByIdAndOrganizationId(CALLER_EMPLOYEE_ID, ORG_ID))
                .thenReturn(Optional.of(new Employee()));
        when(attendanceRepository.findByIdAndOrganization_Id(ATTENDANCE_ID, ORG_ID))
                .thenReturn(Optional.of(colleaguesAttendance()));

        assertThatThrownBy(() -> movementRecordService().addMovement(movementDto(), CALLER_EMPLOYEE_ID))
                .isInstanceOf(AccessDeniedException.class);

        verify(movementRecordRepository, never()).save(any());
    }

    @Test
    void submitRegularization_isRefused_onSomebodyElsesRecord() {
        // A regularization rewrites clock events; on a project set to auto-approve it does so
        // immediately. So filing one against a colleague's record is the same hole as the
        // clock-event one, and it is settled the same way, off the stored record.
        callerMayRecordOnlyFor(CALLER_EMPLOYEE_ID);
        AttendanceRegularizationRepository regularizationRepository =
                org.mockito.Mockito.mock(AttendanceRegularizationRepository.class);
        AttendanceRegularizationMapper regularizationMapper =
                org.mockito.Mockito.mock(AttendanceRegularizationMapper.class);
        SelfApprovalPolicy selfApprovalPolicy = org.mockito.Mockito.mock(SelfApprovalPolicy.class);
        AttendanceRegularizationService regularizationService = new AttendanceRegularizationService(
                regularizationRepository, attendanceRepository, organizationRepository,
                employeeRepository, settingsService, calculationService, regularizationMapper,
                userContextService, selfApprovalPolicy, attendanceSecurity);
        when(organizationRepository.findById(ORG_ID)).thenReturn(Optional.of(organization()));
        when(attendanceRepository.findByIdAndOrganization_Id(ATTENDANCE_ID, ORG_ID))
                .thenReturn(Optional.of(colleaguesAttendance()));
        RegularizationRequestDto dto = new RegularizationRequestDto();
        dto.setAttendanceId(ATTENDANCE_ID);
        dto.setReason("Forgot to clock out");

        assertThatThrownBy(() -> regularizationService.submitRequest(dto))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(regularizationRepository);
        verify(attendanceRepository, never()).save(any());
    }

    /**
     * The wiring that keeps HR and managers able to act for others: {@code canRecordFor} is the
     * same self-or-role policy as viewing, over the configured record-management roles.
     */
    @Test
    void canRecordFor_asksTheSelfOrRolePolicy_withTheRecordManagementRoles() {
        OrganizationSecurityService orgSecurity = org.mockito.Mockito.mock(OrganizationSecurityService.class);
        AttendanceSecurityService security = new AttendanceSecurityService(
                orgSecurity,
                new String[] {"system-admin", "hr-admin"},
                new String[] {"system-admin", "hr-admin", "project-manager"});
        when(orgSecurity.isSelfOrHasAnyOrgRole(COLLEAGUE_EMPLOYEE_ID,
                "system-admin", "hr-admin", "project-manager")).thenReturn(true);

        org.assertj.core.api.Assertions.assertThat(security.canRecordFor(COLLEAGUE_EMPLOYEE_ID)).isTrue();
        verify(orgSecurity).isSelfOrHasAnyOrgRole(COLLEAGUE_EMPLOYEE_ID,
                "system-admin", "hr-admin", "project-manager");
    }
}
