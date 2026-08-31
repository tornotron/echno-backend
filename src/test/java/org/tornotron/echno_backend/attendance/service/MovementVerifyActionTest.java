package org.tornotron.echno_backend.attendance.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.MovementRecord;
import org.tornotron.echno_backend.attendance.MovementRecordRepository;
import org.tornotron.echno_backend.attendance.mapper.MovementRecordMapper;
import org.tornotron.echno_backend.common.approval.SelfApprovalPolicy;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.AttendanceSecurityService;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.organization.OrganizationRepository;
import org.tornotron.echno_backend.user.UserContextService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the verify action stamps, now that it stamps it from the session.
 *
 * <p>The counterpart to {@code MovementVerifierIsNotTakenFromTheRequestTest}, which covers the
 * wire. These cases run the real {@link SelfApprovalPolicy} and the real
 * {@link AttendanceActorResolver} rather than mocks, because the behaviour being pinned is the
 * decision those two make together and a stubbed policy would only pin the stubbing.
 *
 * <p>The self-approval half is new behaviour rather than a stamp being moved: a movement record is
 * the employee's own account of where they went during a work day, so the check on it has to come
 * from somebody else. The record already carries the employee id, so unlike the payment voucher in
 * #631 there was nothing to add to the row before the rule could be applied.
 */
@ExtendWith(MockitoExtension.class)
class MovementVerifyActionTest {

    private static final Long ORG = 1L;
    private static final Long MOVEMENT_ID = 7L;

    private static final Long TRAVELLER_USER_ID = 100L;
    private static final Long TRAVELLER_EMP_ID = 10L;
    private static final Long CHECKER_USER_ID = 200L;
    private static final Long CHECKER_EMP_ID = 20L;

    @Mock private MovementRecordRepository movementRecordRepository;
    @Mock private AttendanceRepository attendanceRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private AttendanceSettingsService settingsService;
    @Mock private MovementRecordMapper movementRecordMapper;
    @Mock private AttendanceSecurityService attendanceSecurity;
    @Mock private UserContextService userContextService;
    @Mock private OrganizationSecurityService orgSecurity;

    private MovementRecordService service;

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentOrgId(ORG);
        service = new MovementRecordService(movementRecordRepository, attendanceRepository,
                employeeRepository, organizationRepository, settingsService, movementRecordMapper,
                attendanceSecurity,
                new AttendanceActorResolver(userContextService, employeeRepository),
                new SelfApprovalPolicy(orgSecurity));
        lenient().when(movementRecordRepository.save(any(MovementRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("stamps the signed-in checker's name and employee id, and the clock")
    void stampsTheSessionCheckerAndTheClock() {
        storedMovement(unverified());
        signedInAs(CHECKER_USER_ID, CHECKER_EMP_ID, "Anand Rajashekar");
        LocalDateTime before = LocalDateTime.now();

        service.verifyMovement(MOVEMENT_ID);

        MovementRecord saved = savedRecord();
        assertThat(saved.getIsVerified()).isTrue();
        assertThat(saved.getVerifiedBy()).isEqualTo("Anand Rajashekar");
        assertThat(saved.getVerifiedById()).isEqualTo(CHECKER_EMP_ID);
        assertThat(saved.getVerifiedAt()).isBetween(before, LocalDateTime.now());
    }

    @Test
    @DisplayName("refuses the employee whose movement it is, so the check is somebody else's")
    void refusesTheEmployeeTheMovementBelongsTo() {
        storedMovement(unverified());
        signedInAs(TRAVELLER_USER_ID, TRAVELLER_EMP_ID, "Aneesh Johny");
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(false);

        assertThatThrownBy(() -> service.verifyMovement(MOVEMENT_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("second pair of eyes");

        verify(movementRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("admits a system administrator verifying their own movement, as the recorded exception")
    void admitsTheBreakGlassRole() {
        storedMovement(unverified());
        signedInAs(TRAVELLER_USER_ID, TRAVELLER_EMP_ID, "Aneesh Johny");
        when(orgSecurity.hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE))
                .thenReturn(true);

        service.verifyMovement(MOVEMENT_ID);

        // Verifying that the role was consulted, not only that the stamp landed: without it this
        // case would still pass with the self-approval rule deleted altogether.
        verify(orgSecurity).hasAnyOrgRoleForCurrentTenant(SelfApprovalPolicy.BREAK_GLASS_ROLE);
        assertThat(savedRecord().getVerifiedById()).isEqualTo(TRAVELLER_EMP_ID);
    }

    @Test
    @DisplayName("verifies where the checker has no employee record, and records their username")
    void verifiesWhereTheCheckerHasNoEmployeeRecord() {
        storedMovement(unverified());
        when(userContextService.getCurrentUserId()).thenReturn(CHECKER_USER_ID);
        when(employeeRepository.findByUserIdAndOrganizationId(CHECKER_USER_ID, ORG))
                .thenReturn(Optional.empty());
        when(userContextService.getCurrentUsername()).thenReturn("admin@echno.com");

        service.verifyMovement(MOVEMENT_ID);

        MovementRecord saved = savedRecord();
        assertThat(saved.getVerifiedBy()).isEqualTo("admin@echno.com");
        assertThat(saved.getVerifiedById()).isNull();
    }

    @Test
    @DisplayName("refuses a session that resolves to no user, rather than stamping nobody")
    void refusesASessionThatResolvesToNobody() {
        storedMovement(unverified());
        when(userContextService.getCurrentUserId()).thenReturn(null);
        when(userContextService.getCurrentUsername()).thenReturn(null);

        assertThatThrownBy(() -> service.verifyMovement(MOVEMENT_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("resolves to no user");

        verify(movementRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("does not replace a verification somebody else's check produced")
    void doesNotReplaceAnExistingVerification() {
        MovementRecord alreadyVerified = unverified();
        alreadyVerified.setIsVerified(true);
        alreadyVerified.setVerifiedBy("Anand Rajashekar");
        alreadyVerified.setVerifiedById(CHECKER_EMP_ID);
        alreadyVerified.setVerifiedAt(LocalDateTime.of(2026, 8, 30, 9, 0));
        storedMovement(alreadyVerified);

        assertThatThrownBy(() -> service.verifyMovement(MOVEMENT_ID))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("already been verified");

        verify(movementRecordRepository, never()).save(any());
        assertThat(alreadyVerified.getVerifiedBy()).isEqualTo("Anand Rajashekar");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private MovementRecord unverified() {
        MovementRecord record = new MovementRecord();
        record.setId(MOVEMENT_ID);
        record.setEmployeeId(TRAVELLER_EMP_ID);
        record.setEmployeeName("Aneesh Johny");
        record.setIsVerified(false);
        return record;
    }

    private void storedMovement(MovementRecord record) {
        when(movementRecordRepository.findByIdAndOrganization_Id(MOVEMENT_ID, ORG))
                .thenReturn(Optional.of(record));
    }

    private void signedInAs(Long userId, Long employeeId, String name) {
        Employee employee = new Employee();
        employee.setId(employeeId);
        employee.setEmployeeName(name);
        when(userContextService.getCurrentUserId()).thenReturn(userId);
        when(employeeRepository.findByUserIdAndOrganizationId(userId, ORG))
                .thenReturn(Optional.of(employee));
    }

    private MovementRecord savedRecord() {
        ArgumentCaptor<MovementRecord> saved = ArgumentCaptor.captor();
        verify(movementRecordRepository).save(saved.capture());
        return saved.getValue();
    }
}
