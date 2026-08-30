package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.leave.enums.TransactionType;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LeaveAccrualService}. All four repositories are mocked and the
 * entity graph is built in memory. The focus is the accrual arithmetic this service owns:
 * choosing the start month from the joining date, deriving the monthly accrual from the
 * policy (explicit rate vs annual-quota / 12 vs nothing), prorating by attendance, capping
 * at the annual quota, skipping months that already have an ACCRUAL transaction, and not
 * accruing for months that have not started yet.
 *
 * A past year (2020) is used for the deterministic cases: when the balance year is not the
 * current year the service walks all twelve months, so the assertions do not drift with the
 * calendar. Rates are chosen so the prorated figure lands on a round number.
 */
@ExtendWith(MockitoExtension.class)
class LeaveAccrualServiceTest {

    private static final int PAST_YEAR = 2020;
    private static final Long BALANCE_ID = 55L;
    private static final Long EMPLOYEE_ID = 7L;
    private static final Long POLICY_ID = 3L;

    @Mock private LeaveBalanceRepository balanceRepository;
    @Mock private LeaveTransactionRepository transactionRepository;
    @Mock private LeaveRequestRepository requestRepository;
    @Mock private AttendanceRepository attendanceRepository;

    private LeaveAccrualService service;

    @BeforeEach
    void setUp() {
        service = new LeaveAccrualService(
                balanceRepository, transactionRepository, requestRepository, attendanceRepository);
    }

    private Employee employee(LocalDateTime joiningDate) {
        Organization org = new Organization();
        org.setId(100L);
        Employee employee = new Employee();
        employee.setId(EMPLOYEE_ID);
        employee.setOrganization(org);
        employee.setJoiningDate(joiningDate);
        return employee;
    }

    private LeavePolicy policy(Double ratePerMonth, Double annualQuota) {
        LeavePolicy policy = new LeavePolicy();
        policy.setId(POLICY_ID);
        policy.setAccrualRatePerMonth(ratePerMonth);
        policy.setAnnualQuota(annualQuota);
        return policy;
    }

    private LeaveBalance balance(Employee employee, LeavePolicy policy, int year) {
        LeaveBalance balance = new LeaveBalance();
        balance.setId(BALANCE_ID);
        balance.setEmployee(employee);
        balance.setLeavePolicy(policy);
        balance.setYear(year);
        balance.setOpeningBalance(0.0);
        balance.setAccrued(0.0);
        return balance;
    }

    /** No month already accrued, and no attendance records anywhere. */
    private void stubNoPriorAccrualsNoAttendance() {
        lenient().when(transactionRepository
                .existsByLeaveBalanceIdAndTransactionTypeAndReferenceMonthAndReferenceYear(
                        anyLong(), eq(TransactionType.ACCRUAL), any(), any()))
                .thenReturn(false);
        lenient().when(attendanceRepository
                .findByEmployeeIdAndAttendanceDateBetween(anyLong(), any(), any()))
                .thenReturn(new ArrayList<>());
    }

    @Test
    void recalculate_joinedBeforeYear_accruesEveryMonthAtFlatRate() {
        Employee employee = employee(LocalDateTime.of(2018, 1, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(1.5, null), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();

        service.recalculate(balance);

        // 12 months x 1.5 = 18.0, no quota cap
        assertThat(balance.getAccrued()).isEqualTo(18.0);
        verify(transactionRepository, times(12)).save(any(LeaveTransaction.class));
        verify(balanceRepository).save(balance);
        assertThat(balance.getLastCalculationMonth()).isEqualTo(12);
    }

    @Test
    void recalculate_joinedMidYear_startsFromJoiningMonth() {
        Employee employee = employee(LocalDateTime.of(PAST_YEAR, 6, 15, 0, 0));
        LeaveBalance balance = balance(employee, policy(1.0, null), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();

        service.recalculate(balance);

        // Months 6..12 inclusive = 7 accruals x 1.0
        assertThat(balance.getAccrued()).isEqualTo(7.0);
        verify(transactionRepository, times(7)).save(any(LeaveTransaction.class));
    }

    @Test
    void recalculate_nullJoiningDate_startsFromJanuary() {
        Employee employee = employee(null);
        LeaveBalance balance = balance(employee, policy(1.0, null), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();

        service.recalculate(balance);

        assertThat(balance.getAccrued()).isEqualTo(12.0);
    }

    @Test
    void recalculate_annualQuota_capsTotalAccrued() {
        Employee employee = employee(LocalDateTime.of(2018, 1, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(5.0, 20.0), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();

        service.recalculate(balance);

        // 12 x 5 = 60 raw, capped at the annual quota of 20
        assertThat(balance.getAccrued()).isEqualTo(20.0);
    }

    @Test
    void recalculate_noExplicitRate_derivesMonthlyFromAnnualQuota() {
        Employee employee = employee(LocalDateTime.of(2018, 1, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(null, 24.0), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();

        service.recalculate(balance);

        // 24 / 12 = 2 per month x 12 = 24, cap min(24, 24) = 24
        assertThat(balance.getAccrued()).isEqualTo(24.0);
        verify(transactionRepository, times(12)).save(any(LeaveTransaction.class));
    }

    @Test
    void recalculate_noQuotaConfigured_accruesNothing() {
        Employee employee = employee(LocalDateTime.of(2018, 1, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(null, null), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();

        service.recalculate(balance);

        assertThat(balance.getAccrued()).isEqualTo(0.0);
        verify(transactionRepository, never()).save(any(LeaveTransaction.class));
    }

    @Test
    void recalculate_proratesByAttendanceRatio() {
        // Joined December 2020 so exactly one month (Dec) is walked.
        Employee employee = employee(LocalDateTime.of(PAST_YEAR, 12, 1, 0, 0));
        // December 2020 has 23 weekdays; a rate of 23 makes accrual == effective days.
        LeaveBalance balance = balance(employee, policy(23.0, null), PAST_YEAR);
        lenient().when(transactionRepository
                .existsByLeaveBalanceIdAndTransactionTypeAndReferenceMonthAndReferenceYear(
                        anyLong(), eq(TransactionType.ACCRUAL), any(), any()))
                .thenReturn(false);

        List<Attendance> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            records.add(attendanceWith(AttendanceStatus.PRESENT));
        }
        records.add(attendanceWith(AttendanceStatus.HALF_DAY));
        records.add(attendanceWith(AttendanceStatus.HALF_DAY));
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(anyLong(), any(), any()))
                .thenReturn(records);

        service.recalculate(balance);

        // effective days = 10 + 2 * 0.5 = 11; ratio 11/23; accrual = 23 * 11/23 = 11
        assertThat(balance.getAccrued()).isEqualTo(11.0);
    }

    @Test
    void recalculate_allAbsentRecords_treatedAsUntracked_givesFullAccrual() {
        Employee employee = employee(LocalDateTime.of(PAST_YEAR, 12, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(5.0, null), PAST_YEAR);
        lenient().when(transactionRepository
                .existsByLeaveBalanceIdAndTransactionTypeAndReferenceMonthAndReferenceYear(
                        anyLong(), eq(TransactionType.ACCRUAL), any(), any()))
                .thenReturn(false);

        List<Attendance> records = new ArrayList<>();
        records.add(attendanceWith(AttendanceStatus.ABSENT));
        records.add(attendanceWith(AttendanceStatus.ABSENT));
        when(attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(anyLong(), any(), any()))
                .thenReturn(records);

        service.recalculate(balance);

        // Every record ABSENT means attendance is not being tracked -> full monthly accrual
        assertThat(balance.getAccrued()).isEqualTo(5.0);
    }

    @Test
    void recalculate_existingAccrualTransaction_isCountedNotRecreated() {
        Employee employee = employee(LocalDateTime.of(PAST_YEAR, 12, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(5.0, null), PAST_YEAR);
        when(transactionRepository
                .existsByLeaveBalanceIdAndTransactionTypeAndReferenceMonthAndReferenceYear(
                        anyLong(), eq(TransactionType.ACCRUAL), any(), any()))
                .thenReturn(true);
        LeaveTransaction existing = new LeaveTransaction();
        existing.setDays(3.5);
        when(transactionRepository.findByBalanceAndTypeAndMonthYear(
                eq(BALANCE_ID), eq(TransactionType.ACCRUAL), eq(12), eq(PAST_YEAR)))
                .thenReturn(List.of(existing));

        service.recalculate(balance);

        assertThat(balance.getAccrued()).isEqualTo(3.5);
        verify(transactionRepository, never()).save(any(LeaveTransaction.class));
    }

    @Test
    void recalculate_futureYear_accruesNothing() {
        int futureYear = LocalDate.now().getYear() + 1;
        Employee employee = employee(LocalDateTime.now());
        LeaveBalance balance = balance(employee, policy(5.0, null), futureYear);
        lenient().when(transactionRepository
                .existsByLeaveBalanceIdAndTransactionTypeAndReferenceMonthAndReferenceYear(
                        anyLong(), eq(TransactionType.ACCRUAL), any(), any()))
                .thenReturn(false);

        service.recalculate(balance);

        // Every month of next year has not started yet -> zero accrual, no transactions
        assertThat(balance.getAccrued()).isEqualTo(0.0);
        verify(transactionRepository, never()).save(any(LeaveTransaction.class));
    }

    @Test
    void recalculate_usedAndPending_defaultToZeroWhenRepositoryReturnsNull() {
        Employee employee = employee(LocalDateTime.of(2018, 1, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(1.0, null), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();
        when(requestRepository.sumApprovedDaysByEmployeePolicyYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(null);
        when(requestRepository.sumPendingDaysByEmployeePolicyYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(null);

        service.recalculate(balance);

        assertThat(balance.getUsed()).isEqualTo(0.0);
        assertThat(balance.getPending()).isEqualTo(0.0);
    }

    @Test
    void recalculate_usedAndPending_takenFromRepositoryWhenPresent() {
        Employee employee = employee(LocalDateTime.of(2018, 1, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(1.0, null), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();
        when(requestRepository.sumApprovedDaysByEmployeePolicyYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(4.0);
        when(requestRepository.sumPendingDaysByEmployeePolicyYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(1.5);

        service.recalculate(balance);

        assertThat(balance.getUsed()).isEqualTo(4.0);
        assertThat(balance.getPending()).isEqualTo(1.5);
    }

    @Test
    void recalculate_quotaThatDoesNotDivideByTwelve_isReportedRounded() {
        // Joined in May, so months 5..12 of the past year accrue: eight twelfths of a
        // 91-day quota. That is 60.666666666666664 as a double, which is what reached the
        // My Leaves screen.
        Employee employee = employee(LocalDateTime.of(PAST_YEAR, 5, 4, 0, 0));
        LeaveBalance balance = balance(employee, policy(null, 91.0), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();

        service.recalculate(balance);

        assertThat(balance.getAccrued()).isEqualTo(60.67);
        verify(transactionRepository, times(8)).save(any(LeaveTransaction.class));
    }

    @Test
    void recalculate_manualCredit_survivesTheRecalculation() {
        // A manual adjustment is applied to the balance and recorded as an ADJUSTMENT
        // transaction. Rebuilding accrued from the accrual ledger alone used to drop it the
        // first time a new month rolled over.
        Employee employee = employee(LocalDateTime.of(2018, 1, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(1.0, null), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();
        when(transactionRepository.sumAdjustmentCredits(BALANCE_ID)).thenReturn(3.0);

        service.recalculate(balance);

        // 12 months x 1.0 accrued, plus the 3 days granted by hand
        assertThat(balance.getAccrued()).isEqualTo(15.0);
    }

    @Test
    void recalculate_manualDebit_survivesTheRecalculation() {
        Employee employee = employee(LocalDateTime.of(2018, 1, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(1.0, null), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();
        when(requestRepository.sumApprovedDaysByEmployeePolicyYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(4.0);
        when(transactionRepository.sumAdjustmentDebits(BALANCE_ID)).thenReturn(2.5);

        service.recalculate(balance);

        // 4 days of approved leave plus the 2.5 days taken back by hand
        assertThat(balance.getUsed()).isEqualTo(6.5);
    }

    @Test
    void recalculate_manualCredit_isNotCappedByTheAnnualQuota() {
        // The cap belongs to accrual. A grant above the quota is a deliberate act.
        Employee employee = employee(LocalDateTime.of(2018, 1, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(5.0, 20.0), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();
        when(transactionRepository.sumAdjustmentCredits(BALANCE_ID)).thenReturn(4.0);

        service.recalculate(balance);

        assertThat(balance.getAccrued()).isEqualTo(24.0);
    }

    @Test
    void recalculate_halfDaysUsedAndPending_areCarriedExactly() {
        Employee employee = employee(LocalDateTime.of(2018, 1, 1, 0, 0));
        LeaveBalance balance = balance(employee, policy(1.0, null), PAST_YEAR);
        stubNoPriorAccrualsNoAttendance();
        when(requestRepository.sumApprovedDaysByEmployeePolicyYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(3.5);
        when(requestRepository.sumPendingDaysByEmployeePolicyYear(anyLong(), anyLong(), anyInt()))
                .thenReturn(0.5);

        service.recalculate(balance);

        assertThat(balance.getUsed()).isEqualTo(3.5);
        assertThat(balance.getPending()).isEqualTo(0.5);
        assertThat(balance.getAvailableBalance()).isEqualTo(8.5);
        assertThat(balance.getBookableBalance()).isEqualTo(8.0);
    }

    private Attendance attendanceWith(AttendanceStatus status) {
        return Attendance.builder().status(status).build();
    }
}
