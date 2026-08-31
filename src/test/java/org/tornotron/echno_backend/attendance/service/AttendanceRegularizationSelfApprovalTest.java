package org.tornotron.echno_backend.attendance.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRegularization;
import org.tornotron.echno_backend.attendance.AttendanceRegularizationRepository;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.dto.AttendanceRegularizationDto;
import org.tornotron.echno_backend.attendance.dto.ClockEventCreationDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationActionDto;
import org.tornotron.echno_backend.attendance.dto.RegularizationRequestDto;
import org.tornotron.echno_backend.attendance.enums.ClockEventType;
import org.tornotron.echno_backend.attendance.enums.RegularizationStatus;
import org.tornotron.echno_backend.attendance.mapper.AttendanceRegularizationMapper;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.Organization;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The segregation-of-duties rule on regularizations, run through the real
 * {@link SelfApprovalPolicy} rather than a mock of it, for the caller shape that used to slip past
 * it entirely.
 *
 * <p>A member who holds a decision role in the tenant but has no employee record in it yet, which
 * is what a Keycloak group assigned before HR creates the record produces, was recorded on the
 * request with no employee id. The rule compared employee ids alone and both sides were null, so
 * it returned without comparing anything: the same account could raise a request and approve it,
 * and even a break-glass approval left the corrected clock events looking like an ordinary one.
 * The request now carries the raiser's platform user id as well, which is the identity every
 * authenticated caller has, and the rule compares whichever identity the two sides share.
 */
@ExtendWith(MockitoExtension.class)
class AttendanceRegularizationSelfApprovalTest {

    private static final Long ORG = 100L;
    private static final Long ATT_ID = 42L;
    private static final Long REG_ID = 7L;
    private static final String DOCUMENT = "Regularization request with ID " + REG_ID;

    /** The member with a decision role and no employee record in this tenant. */
    private static final Long ROLE_HOLDER_USER_ID = 900L;
    private static final String ROLE_HOLDER = "hr.admin@echno.in";

    /** An ordinary employee of the tenant, who is somebody else. */
    private static final Long MANAGER_USER_ID = 901L;
    private static final Long MANAGER_EMP_ID = 12L;
    private static final String MANAGER = "manager-xyz";

    @Mock private AttendanceRegularizationRepository regularizationRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private AttendanceSettingsService settingsService;
    @Mock private AttendanceCalculationService calculationService;
    @Mock private AttendanceRegularizationMapper regularizationMapper;
    @Mock private UserContextService userContextService;
    @Mock private OrganizationSecurityService orgSecurity;
    @Mock private AttendanceSecurityService attendanceSecurity;

    private AttendanceRegularizationService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new AttendanceRegularizationService(regularizationRepository, attendanceRepository,
                organizationRepository, settingsService, calculationService,
                regularizationMapper, new AttendanceActorResolver(userContextService, employeeRepository),
                new SelfApprovalPolicy(orgSecurity), attendanceSecurity);
        // Not the concern here: these tests exercise the self-approval rule, not who may raise.
        org.mockito.Mockito.lenient().when(attendanceSecurity.canRecordFor(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * The defect this test exists for. The role holder raises the request and comes straight back
     * to approve it. Before the raiser's user id was stored there was nothing on either side to
     * compare, the rule returned without firing, and the correction was written.
     */
    @Test
    void anEmployeeLessMemberCannotRaiseAndThenApproveTheirOwnRequest() {
        signedInWithNoEmployeeRecord(ROLE_HOLDER_USER_ID, ROLE_HOLDER);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(false);
        Attendance attendance = attendance();
        AttendanceRegularization pending = pendingRaisedBy(null, ROLE_HOLDER_USER_ID);
        pending.setAttendance(attendance);
        when(regularizationRepository.findByIdAndOrganization_Id(REG_ID, ORG))
                .thenReturn(Optional.of(pending));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.processRegularization(REG_ID, action(RegularizationStatus.APPROVED)))
                .withMessageContaining(DOCUMENT)
                .withMessageContaining("someone other than whoever raised the document");

        assertThat(pending.getStatus()).isEqualTo(RegularizationStatus.PENDING);
        assertThat(attendance.getClockEvents()).isEmpty();
        verify(calculationService, never()).recalculate(any(), any());
        verify(regularizationRepository, never()).save(any());
    }

    /**
     * The same pair under the break-glass role goes through, and the clock events it writes say
     * the correction was self-approved. That note was being skipped too, which left the row
     * indistinguishable from one raised before any of this was recorded.
     */
    @Test
    void anEmployeeLessSystemAdministratorSelfApprovesAndTheClockEventsSaySo() {
        signedInWithNoEmployeeRecord(ROLE_HOLDER_USER_ID, ROLE_HOLDER);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(true);
        Attendance attendance = attendance();
        AttendanceRegularization pending = pendingRaisedBy(null, ROLE_HOLDER_USER_ID);
        pending.setAttendance(attendance);
        pending.setOrganization(organization());
        pending.setRequestedEvents("[stored]");
        stubApprovalWrites();
        when(regularizationRepository.findByIdAndOrganization_Id(REG_ID, ORG))
                .thenReturn(Optional.of(pending));
        when(regularizationMapper.deserializeRequestedEvents("[stored]"))
                .thenReturn(List.of(correctedEvent(ClockEventType.EVENING_CLOCK_OUT)));

        service.processRegularization(REG_ID, action(RegularizationStatus.APPROVED));

        assertThat(pending.getStatus()).isEqualTo(RegularizationStatus.APPROVED);
        assertThat(attendance.getClockEvents())
                .singleElement()
                .extracting(ClockEvent::getRegularizationReason)
                .isEqualTo("Self-regularized (self-approved: raised and approved by the same person)");
    }

    /** Negative control: an employee-less approver deciding somebody else's request is unaffected. */
    @Test
    void anEmployeeLessApproverDecidingSomeoneElsesRequestIsAnOrdinaryApproval() {
        signedInWithNoEmployeeRecord(ROLE_HOLDER_USER_ID, ROLE_HOLDER);
        Attendance attendance = attendance();
        AttendanceRegularization pending = pendingRaisedBy(MANAGER_EMP_ID, MANAGER_USER_ID);
        pending.setAttendance(attendance);
        pending.setOrganization(organization());
        pending.setRequestedEvents("[stored]");
        stubApprovalWrites();
        when(regularizationRepository.findByIdAndOrganization_Id(REG_ID, ORG))
                .thenReturn(Optional.of(pending));
        when(regularizationMapper.deserializeRequestedEvents("[stored]"))
                .thenReturn(List.of(correctedEvent(ClockEventType.EVENING_CLOCK_OUT)));

        service.processRegularization(REG_ID, action(RegularizationStatus.APPROVED));

        assertThat(pending.getStatus()).isEqualTo(RegularizationStatus.APPROVED);
        assertThat(attendance.getClockEvents())
                .singleElement()
                .extracting(ClockEvent::getRegularizationReason)
                .isEqualTo("Self-regularized");
    }

    /**
     * The employee side still settles it on its own. An employee raising a request and approving
     * it is the case the rule always caught, and it is caught the same way now that the comparison
     * can also run on user ids.
     */
    @Test
    void anEmployeeStillCannotApproveTheirOwnRequest() {
        signedInAs(MANAGER_USER_ID, MANAGER_EMP_ID, MANAGER);
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(false);
        AttendanceRegularization pending = pendingRaisedBy(MANAGER_EMP_ID, MANAGER_USER_ID);
        pending.setAttendance(attendance());
        when(regularizationRepository.findByIdAndOrganization_Id(REG_ID, ORG))
                .thenReturn(Optional.of(pending));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.processRegularization(REG_ID, action(RegularizationStatus.APPROVED)));

        assertThat(pending.getStatus()).isEqualTo(RegularizationStatus.PENDING);
    }

    /**
     * A request stored before the user id was kept, decided by an approver with no employee record,
     * shares no identity with them. It is let through rather than becoming unapprovable, which is
     * the one case the rule still cannot check and now logs as such.
     */
    @Test
    void aLegacyRequestIsStillApprovableByAnApproverItCannotBeComparedWith() {
        signedInWithNoEmployeeRecord(ROLE_HOLDER_USER_ID, ROLE_HOLDER);
        AttendanceRegularization pending = pendingRaisedBy(MANAGER_EMP_ID, null);
        pending.setAttendance(attendance());
        pending.setOrganization(organization());
        stubApprovalWrites();
        when(regularizationRepository.findByIdAndOrganization_Id(REG_ID, ORG))
                .thenReturn(Optional.of(pending));

        service.processRegularization(REG_ID, action(RegularizationStatus.APPROVED));

        assertThat(pending.getStatus()).isEqualTo(RegularizationStatus.APPROVED);
    }

    /**
     * The one place the rule fails closed. A session that resolves to no user and no employee
     * would post a correction with a null approver on it, so it is refused instead of being
     * compared against nothing and let past.
     */
    @Test
    void anApproverThatResolvesToNoIdentityAtAllIsRefused() {
        when(userContextService.getCurrentUserId()).thenReturn(null);
        when(userContextService.getCurrentUsername()).thenReturn(null);
        AttendanceRegularization pending = pendingRaisedBy(MANAGER_EMP_ID, MANAGER_USER_ID);
        pending.setAttendance(attendance());
        when(regularizationRepository.findByIdAndOrganization_Id(REG_ID, ORG))
                .thenReturn(Optional.of(pending));

        assertThatExceptionOfType(InvalidRequestException.class)
                .isThrownBy(() -> service.processRegularization(REG_ID, action(RegularizationStatus.APPROVED)))
                .withMessageContaining("resolves to no user of this organization");

        assertThat(pending.getStatus()).isEqualTo(RegularizationStatus.PENDING);
        verify(regularizationRepository, never()).save(any());
    }

    /** The stamping the rest of this depends on: the raiser's user id reaches the stored request. */
    @Test
    void submitRequestStampsTheRaisersUserIdEvenWithNoEmployeeRecord() {
        signedInWithNoEmployeeRecord(ROLE_HOLDER_USER_ID, ROLE_HOLDER);
        when(organizationRepository.findById(ORG)).thenReturn(Optional.of(organization()));
        when(attendanceRepository.findByIdAndOrganization_Id(ATT_ID, ORG))
                .thenReturn(Optional.of(attendance()));
        when(settingsService.resolveEffectiveSettings(eq(ORG), any())).thenReturn(AttendanceSettings.builder()
                .allowSelfRegularization(true)
                .regularizationApprovalRequired(true)
                .maxRegularizationDaysPerMonth(3)
                .build());
        when(regularizationRepository.countApprovedRegularizationsInMonth(eq(ROLE_HOLDER), any(), any()))
                .thenReturn(0L);
        when(regularizationRepository.findByAttendanceId(ATT_ID)).thenReturn(Optional.empty());
        when(regularizationRepository.save(any(AttendanceRegularization.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(regularizationMapper.toDto(any())).thenReturn(AttendanceRegularizationDto.builder().build());

        RegularizationRequestDto dto = new RegularizationRequestDto();
        dto.setAttendanceId(ATT_ID);
        dto.setReason("Forgot to clock out");
        service.submitRequest(dto);

        ArgumentCaptor<AttendanceRegularization> captor =
                ArgumentCaptor.forClass(AttendanceRegularization.class);
        verify(regularizationRepository).save(captor.capture());
        assertThat(captor.getValue().getRequestedById()).isNull();
        assertThat(captor.getValue().getRequestedByUserId()).isEqualTo(ROLE_HOLDER_USER_ID);
    }

    private void signedInAs(Long userId, Long employeeId, String employeeName) {
        Employee employee = new Employee();
        employee.setId(employeeId);
        employee.setEmployeeName(employeeName);
        lenient().when(userContextService.getCurrentUserId()).thenReturn(userId);
        lenient().when(employeeRepository.findByUserIdAndOrganizationId(userId, ORG))
                .thenReturn(Optional.of(employee));
    }

    private void signedInWithNoEmployeeRecord(Long userId, String username) {
        lenient().when(userContextService.getCurrentUserId()).thenReturn(userId);
        lenient().when(employeeRepository.findByUserIdAndOrganizationId(userId, ORG))
                .thenReturn(Optional.empty());
        lenient().when(userContextService.getCurrentUsername()).thenReturn(username);
    }

    private void stubApprovalWrites() {
        lenient().when(regularizationRepository.save(any(AttendanceRegularization.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(regularizationMapper.toDto(any()))
                .thenReturn(AttendanceRegularizationDto.builder().build());
    }

    private AttendanceRegularization pendingRaisedBy(Long employeeId, Long userId) {
        return AttendanceRegularization.builder()
                .status(RegularizationStatus.PENDING)
                .requestedBy(employeeId == null ? ROLE_HOLDER : MANAGER)
                .requestedById(employeeId)
                .requestedByUserId(userId)
                .build();
    }

    private Organization organization() {
        Organization org = new Organization();
        org.setId(ORG);
        return org;
    }

    private Attendance attendance() {
        Attendance attendance = Attendance.builder()
                .id(ATT_ID)
                .attendanceDate(LocalDate.of(2024, 5, 10))
                .projectId(9L)
                .build();
        attendance.setClockEvents(new ArrayList<>());
        return attendance;
    }

    private ClockEventCreationDto correctedEvent(ClockEventType type) {
        ClockEventCreationDto event = new ClockEventCreationDto();
        event.setEventType(type);
        event.setEventTimestamp(LocalDateTime.of(2024, 5, 10, 18, 0));
        return event;
    }

    private RegularizationActionDto action(RegularizationStatus status) {
        RegularizationActionDto action = new RegularizationActionDto();
        action.setStatus(status);
        return action;
    }
}
