package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceDto;
import org.tornotron.echno_backend.leave.mapper.LeaveBalanceMapper;
import org.tornotron.echno_backend.leave.mapper.LeaveTransactionMapper;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the recalculate endpoint has to do that the balance read does not.
 *
 * <p>Reading a balance recomputes it only when {@code needsRecalculation} says a month has turned
 * since the row was last touched. That is the right rule for a read: it keeps the figures current
 * without rebuilding them on every page load. It is the wrong rule for a caller who has come to
 * this endpoint precisely because they believe the stored figures are wrong, because the case they
 * are reaching for is the one the guard answers "no" to.
 *
 * <p>The row below is deliberately fresh: calculated this month, in the current year, so the read
 * path leaves it alone. Both twins of the recalculate route must still rebuild it.
 *
 * <p>The second pair of cases is the constraint that comes with forcing. The read path refuses to
 * persist a row for a year the employee had not joined in, returning a transient zero balance
 * instead, and a forced rebuild must not lose that: recalculating 2019 for somebody who joined in
 * 2024 would otherwise create rows for years that never existed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveBalanceRecalculationTest {

    private static final long ORG = 42L;
    private static final long EMPLOYEE = 7L;
    private static final long POLICY = 3L;

    @Mock private LeaveBalanceRepository balanceRepository;
    @Mock private LeavePolicyRepository policyRepository;
    @Mock private LeaveTransactionRepository transactionRepository;
    @Mock private EmployeeRepository employeeRepository;
    @Mock private LeaveTransactionMapper leaveTransactionMapper;
    @Mock private LeaveBalanceMapper leaveBalanceMapper;
    @Mock private LeaveAccrualService leaveAccrualService;

    private LeaveBalanceService service;
    private LeaveBalanceController controller;
    private LeaveBalanceControllerWeb webController;

    private LeaveBalance freshBalance;

    @BeforeEach
    void setUp() {
        service = new LeaveBalanceService(balanceRepository, policyRepository, transactionRepository,
                employeeRepository, leaveTransactionMapper, leaveBalanceMapper, leaveAccrualService);
        controller = new LeaveBalanceController(service);
        webController = new LeaveBalanceControllerWeb(service);

        TenantContext.setCurrentOrgId(ORG);

        Organization organization = new Organization();
        organization.setId(ORG);

        Employee employee = new Employee();
        employee.setId(EMPLOYEE);
        employee.setOrganization(organization);
        employee.setGender("Male");
        employee.setJoiningDate(LocalDateTime.of(2024, 1, 15, 9, 0));

        LeavePolicy policy = new LeavePolicy();
        policy.setId(POLICY);
        policy.setOrganization(organization);
        policy.setAnnualQuota(12.0);

        LocalDate today = LocalDate.now();
        freshBalance = new LeaveBalance();
        freshBalance.setEmployee(employee);
        freshBalance.setOrganization(organization);
        freshBalance.setLeavePolicy(policy);
        freshBalance.setYear(today.getYear());
        freshBalance.setOpeningBalance(0.0);
        freshBalance.setAccrued(5.0);
        freshBalance.setUsed(0.0);
        freshBalance.setPending(0.0);
        freshBalance.setCarryForwardFromPrevious(0.0);
        // Already recomputed this month, which is exactly what makes the read path skip it.
        freshBalance.setLastCalculatedAt(LocalDateTime.now());
        freshBalance.setLastCalculationMonth(today.getMonthValue());

        when(employeeRepository.findByIdAndOrganizationId(EMPLOYEE, ORG))
                .thenReturn(Optional.of(employee));
        when(policyRepository.findByIdAndOrganization_Id(POLICY, ORG))
                .thenReturn(Optional.of(policy));
        when(policyRepository.findApplicablePolicies(eq(ORG), anyString(), anyInt()))
                .thenReturn(List.of(policy));
        when(balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(EMPLOYEE, POLICY, today.getYear()))
                .thenReturn(Optional.of(freshBalance));
        when(leaveBalanceMapper.toDto(any(LeaveBalance.class))).thenReturn(new LeaveBalanceDto());
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void theReadPathLeavesAFreshBalanceAlone() {
        // Not the defect, the baseline. The read is allowed to skip; the endpoint below is not.
        controller.getEmployeeBalances(EMPLOYEE, null);

        verify(leaveAccrualService, never()).recalculate(any());
    }

    @Test
    void recalculateRebuildsABalanceTheReadPathWouldHaveSkipped() {
        controller.recalculateBalances(EMPLOYEE, null);

        verify(leaveAccrualService).recalculate(freshBalance);
    }

    @Test
    void theWebTwinRebuildsItToo() {
        webController.recalculateBalances(EMPLOYEE, null);

        verify(leaveAccrualService).recalculate(freshBalance);
    }

    @Test
    void recalculatingAYearBeforeTheEmployeeJoinedPersistsNothing() {
        // The employee joined in 2024. 2019 has no row and must not gain one.
        controller.recalculateBalances(EMPLOYEE, 2019);

        verify(balanceRepository, never()).save(any());
        verify(leaveAccrualService, never()).recalculate(any());
    }

    @Test
    void aYearBeforeTheEmployeeJoinedStillAnswersWithABalancePerPolicy() {
        List<LeaveBalanceDto> balances = controller.recalculateBalances(EMPLOYEE, 2019).getBody();

        assertThat(balances).hasSize(1);
    }
}
