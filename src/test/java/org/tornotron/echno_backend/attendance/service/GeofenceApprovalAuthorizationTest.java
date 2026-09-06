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
import org.tornotron.echno_backend.attendance.ShiftTimingRepository;
import org.tornotron.echno_backend.attendance.dto.AttendanceApprovalDto;
import org.tornotron.echno_backend.attendance.dto.AttendanceResponseDto;
import org.tornotron.echno_backend.attendance.enums.ApprovalStatus;
import org.tornotron.echno_backend.attendance.mapper.AttendanceMapper;
import org.tornotron.echno_backend.attendance.validator.ClockEventSequenceValidator;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.payload.PayloadValidator;
import org.tornotron.echno_backend.common.service.AttachmentService;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.FileStorageService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.project.ProjectRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may decide a geofence exception.
 *
 * <p>The product decision is that the employee's immediate reporting manager approves it. That
 * manager is usually not one of the organization-wide record-management roles, which is what the
 * approve endpoint was gated on, so gating on the role alone would have sent every exception to HR
 * and the project managers regardless of who the employee actually reports to. The approver named
 * on the record is added to the roles rather than replacing them, because the relation is set on a
 * minority of employees and a record nobody can approve is worse than a broad one.
 *
 * <p>The one thing narrowed rather than widened: an employee does not decide their own exception,
 * whatever roles they hold. The decision exists so that somebody else vouches for the absence.
 */
@ExtendWith(MockitoExtension.class)
class GeofenceApprovalAuthorizationTest {

    private static final Long ORG_ID = 100L;
    private static final Long ATTENDANCE_ID = 781L;
    private static final Long EMPLOYEE_ID = 7L;
    private static final Long MANAGER_ID = 55L;

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
    @Mock private AttendanceGeofenceService geofenceService;

    private AttendanceService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG_ID);
        service = new AttendanceService(attendanceRepository, shiftTimingRepository, employeeRepository,
                organizationRepository, projectRepository, settingsService, calculationService,
                sequenceValidator, attendanceMapper, attachmentService, fileStorageService,
                userContextService,
                new PayloadValidator(Validation.buildDefaultValidatorFactory().getValidator()),
                attendanceSecurity, geofenceService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Attendance heldForGeofenceApproval() {
        Attendance attendance = new Attendance();
        attendance.setId(ATTENDANCE_ID);
        attendance.setEmployeeId(EMPLOYEE_ID);
        attendance.setRequiresGeofenceApproval(true);
        attendance.setGeofenceApproverId(MANAGER_ID);
        return attendance;
    }

    private void givenTheRecordIsFound(Attendance attendance) {
        when(attendanceRepository.findByIdAndOrganization_Id(ATTENDANCE_ID, ORG_ID))
                .thenReturn(Optional.of(attendance));
    }

    private void givenTheDecisionIsSaved() {
        lenient().when(attendanceRepository.save(any(Attendance.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(attendanceMapper.toResponseDto(any(Attendance.class)))
                .thenReturn(AttendanceResponseDto.builder().build());
    }

    private AttendanceApprovalDto approval() {
        AttendanceApprovalDto dto = new AttendanceApprovalDto();
        dto.setApprovalStatus(ApprovalStatus.APPROVED);
        return dto;
    }

    @Test
    void theReportingManagerNamedOnTheRecordMayApprove_withoutAManagementRole() {
        givenTheRecordIsFound(heldForGeofenceApproval());
        givenTheDecisionIsSaved();
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(false);
        when(attendanceSecurity.canDecideApproval(MANAGER_ID)).thenReturn(true);

        service.approveAttendance(ATTENDANCE_ID, approval());

        verify(attendanceRepository).save(any(Attendance.class));
    }

    @Test
    void somebodyWhoIsNeitherTheNamedApproverNorARecordManagerIsRefused() {
        givenTheRecordIsFound(heldForGeofenceApproval());
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(false);
        when(attendanceSecurity.canDecideApproval(MANAGER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.approveAttendance(ATTENDANCE_ID, approval()))
                .isInstanceOf(AccessDeniedException.class);

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void theEmployeeDoesNotApproveTheirOwnGeofenceException() {
        // Even a project manager or an HR admin, who may decide every other attendance record,
        // does not sign off their own absence from the site.
        givenTheRecordIsFound(heldForGeofenceApproval());
        when(attendanceSecurity.isSelfMarking(EMPLOYEE_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.approveAttendance(ATTENDANCE_ID, approval()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("someone other than the employee");

        verify(attendanceRepository, never()).save(any());
    }

    @Test
    void anOrdinaryRecordIsStillDecidedByTheRecordManagementRoles() {
        // Nothing about who approves a record with no geofence exception changes: the self-check
        // is scoped to flagged records, and an unflagged one falls through to the role policy.
        Attendance ordinary = new Attendance();
        ordinary.setId(ATTENDANCE_ID);
        ordinary.setEmployeeId(EMPLOYEE_ID);
        givenTheRecordIsFound(ordinary);
        givenTheDecisionIsSaved();
        when(attendanceSecurity.canDecideApproval(null)).thenReturn(true);

        service.approveAttendance(ATTENDANCE_ID, approval());

        verify(attendanceRepository).save(any(Attendance.class));
        verify(attendanceSecurity, never()).isSelfMarking(any());
    }

    /**
     * The policy itself: the record managers, plus the approver the record names, and nobody else.
     * An id of null must not open the decision to everyone.
     */
    @Test
    void canDecideApproval_isTheRecordManagersPlusTheNamedApprover() {
        OrganizationSecurityService orgSecurity =
                org.mockito.Mockito.mock(OrganizationSecurityService.class);
        AttendanceSecurityService security = new AttendanceSecurityService(
                orgSecurity,
                new String[] {"system-admin", "hr-admin"},
                new String[] {"system-admin", "hr-admin", "project-manager"});

        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(
                "system-admin", "hr-admin", "project-manager")).thenReturn(false);
        when(orgSecurity.isSelfInCurrentTenant(MANAGER_ID)).thenReturn(true);

        assertThat(security.canDecideApproval(MANAGER_ID)).isTrue();
        assertThat(security.canDecideApproval(null)).isFalse();
    }
}
