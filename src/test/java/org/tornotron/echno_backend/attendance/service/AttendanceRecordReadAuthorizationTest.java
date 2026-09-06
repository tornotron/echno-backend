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
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.AttendanceService;
import org.tornotron.echno_backend.attendance.MovementRecord;
import org.tornotron.echno_backend.attendance.MovementRecordRepository;
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.mapper.MovementRecordMapper;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Who may read a stored attendance record, and the movement trail hanging off it.
 *
 * <p>The by-id reads were guarded by tenant membership alone. Every one of them is tenant scoped
 * and none was employee scoped, so any member of an organization could walk integer ids and read
 * every colleague's day: the response is the same {@code AttendanceResponseDto} the employee-scoped
 * listing returns, carrying the employee, the check-in and check-out coordinates, the geofence
 * state and the attachment, which for this product is a photograph and a position. The policy that
 * should have decided it, {@code canViewEmployeeRecords}, was already written and already applied
 * to the route directly beside them.
 *
 * <p>The id on these requests names a record rather than a person, so an annotation has nothing to
 * check. The employee and the designated approver are columns the record carries, which is why the
 * decision is taken in the service against the loaded row, the way the approval path already reads
 * its approver off the record.
 *
 * <p>The policy bean here is the real one over a mocked {@link OrganizationSecurityService}, so
 * these exercise the composition rather than a stub of it, and the refusals are red on the old code
 * by the read going through.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceRecordReadAuthorizationTest {

    private static final Long ORG_ID = 100L;
    private static final Long COLLEAGUE_EMPLOYEE_ID = 8L;
    private static final Long APPROVER_EMPLOYEE_ID = 9L;
    private static final Long ATTENDANCE_ID = 781L;
    private static final Long MOVEMENT_ID = 4410L;

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
    @Mock private AttendanceGeofenceService geofenceService;
    @Mock private MovementRecordRepository movementRecordRepository;
    @Mock private MovementRecordMapper movementRecordMapper;
    @Mock private SelfApprovalPolicy selfApprovalPolicy;

    /** The one thing stubbed: what the caller's token says about them. */
    @Mock private OrganizationSecurityService orgSecurity;

    @BeforeEach
    void setTenant() {
        TenantContext.setCurrentOrgId(ORG_ID);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private AttendanceSecurityService attendanceSecurity() {
        return new AttendanceSecurityService(
                orgSecurity,
                new String[]{"system-admin", "hr-admin"},
                new String[]{"system-admin", "hr-admin", "project-manager"});
    }

    private AttendanceService attendanceService() {
        return new AttendanceService(attendanceRepository, shiftTimingRepository, employeeRepository,
                organizationRepository, projectRepository, settingsService, calculationService,
                sequenceValidator, attendanceMapper, attachmentService, fileStorageService,
                userContextService,
                new PayloadValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                attendanceSecurity(), geofenceService);
    }

    private MovementRecordService movementRecordService() {
        return new MovementRecordService(movementRecordRepository, attendanceRepository,
                employeeRepository, organizationRepository, settingsService, movementRecordMapper,
                attendanceSecurity(), new AttendanceActorResolver(userContextService, employeeRepository),
                selfApprovalPolicy);
    }

    private Organization organization() {
        Organization organization = new Organization();
        organization.setId(ORG_ID);
        return organization;
    }

    /** A colleague's day, flagged for a geofence decision and naming who is to take it. */
    private Attendance colleaguesAttendance() {
        Attendance attendance = new Attendance();
        attendance.setId(ATTENDANCE_ID);
        attendance.setEmployeeId(COLLEAGUE_EMPLOYEE_ID);
        attendance.setEmployeeName("Colleague");
        attendance.setProjectId(12L);
        attendance.setRequiresGeofenceApproval(true);
        attendance.setGeofenceApproverId(APPROVER_EMPLOYEE_ID);
        attendance.setOrganization(organization());
        return attendance;
    }

    private MovementRecord colleaguesMovement() {
        return MovementRecord.builder()
                .id(MOVEMENT_ID)
                .attendance(colleaguesAttendance())
                .employeeId(COLLEAGUE_EMPLOYEE_ID)
                .employeeName("Colleague")
                .organization(organization())
                .build();
    }

    /**
     * A plain member of the tenant: no record-management role, and not the employee whose record
     * this is. Membership alone is what the old guard asked for.
     */
    private void callerIsAnOrdinaryMember() {
        lenient().when(orgSecurity.isSelfOrHasAnyOrgRole(any(Long.class), any(String[].class)))
                .thenReturn(false);
        lenient().when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class)))
                .thenReturn(false);
        lenient().when(orgSecurity.isSelfInCurrentTenant(any(Long.class))).thenReturn(false);
    }

    /** The employee the record belongs to. */
    private void callerIsTheEmployee() {
        lenient().when(orgSecurity.isSelfOrHasAnyOrgRole(eq(COLLEAGUE_EMPLOYEE_ID), any(String[].class)))
                .thenReturn(true);
        lenient().when(orgSecurity.isSelfInCurrentTenant(any(Long.class))).thenReturn(false);
    }

    /**
     * The reporting manager the record names as its approver, holding no organization-wide role.
     * The case the fix must not shut out: somebody asked to decide a record has to see it.
     */
    private void callerIsTheDesignatedApprover() {
        lenient().when(orgSecurity.isSelfOrHasAnyOrgRole(any(Long.class), any(String[].class)))
                .thenReturn(false);
        lenient().when(orgSecurity.hasAnyOrgRoleForCurrentTenant(any(String[].class)))
                .thenReturn(false);
        lenient().when(orgSecurity.isSelfInCurrentTenant(eq(APPROVER_EMPLOYEE_ID))).thenReturn(true);
    }

    private void attendanceRecordExists() {
        lenient().when(attendanceRepository.findByIdAndOrganization_Id(ATTENDANCE_ID, ORG_ID))
                .thenReturn(Optional.of(colleaguesAttendance()));
    }

    @Test
    void attendanceById_isRefused_toAMemberWhoIsNeitherTheEmployeeNorAManager() {
        callerIsAnOrdinaryMember();
        attendanceRecordExists();

        assertThatThrownBy(() -> attendanceService().getAttendanceById(ATTENDANCE_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void attendanceById_isAllowed_toTheEmployeeItBelongsTo() {
        callerIsTheEmployee();
        attendanceRecordExists();

        assertThatCode(() -> attendanceService().getAttendanceById(ATTENDANCE_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void attendanceById_isAllowed_toTheApproverTheRecordNames() {
        callerIsTheDesignatedApprover();
        attendanceRecordExists();

        assertThatCode(() -> attendanceService().getAttendanceById(ATTENDANCE_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void movementById_isRefused_toAMemberWhoMayNotReadTheAttendanceItHangsOff() {
        callerIsAnOrdinaryMember();
        lenient().when(movementRecordRepository.findByIdAndOrganization_Id(MOVEMENT_ID, ORG_ID))
                .thenReturn(Optional.of(colleaguesMovement()));

        assertThatThrownBy(() -> movementRecordService().getMovementById(MOVEMENT_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void movementsByAttendance_areRefused_toAMemberWhoMayNotReadTheAttendance() {
        callerIsAnOrdinaryMember();
        attendanceRecordExists();

        assertThatThrownBy(() -> movementRecordService().getMovementsByAttendance(ATTENDANCE_ID))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void movementsByAttendance_areAllowed_toTheEmployeeTheAttendanceBelongsTo() {
        callerIsTheEmployee();
        attendanceRecordExists();

        assertThatCode(() -> movementRecordService().getMovementsByAttendance(ATTENDANCE_ID))
                .doesNotThrowAnyException();
    }
}
