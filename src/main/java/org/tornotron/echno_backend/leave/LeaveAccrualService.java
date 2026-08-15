package org.tornotron.echno_backend.leave;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.leave.enums.TransactionType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * Computes and posts a leave balance's monthly accrual. Given a {@link LeaveBalance},
 * it walks each accrual month from the employee's start month to the current month,
 * derives the monthly accrual (optionally prorated by attendance), records ACCRUAL
 * transactions for months not yet accrued, and refreshes the balance's
 * accrued / used / pending figures.
 *
 * Extracted from {@link LeaveBalanceService}, which keeps the balance-query and
 * summary responsibilities. The methods here run inside the caller's transaction
 * (they are invoked from {@code @Transactional} balance methods), so they carry no
 * transaction annotation of their own — the managed entity and flush semantics are
 * unchanged from when this logic lived in {@link LeaveBalanceService}.
 */
@Service
public class LeaveAccrualService {

    private final LeaveBalanceRepository balanceRepository;
    private final LeaveTransactionRepository transactionRepository;
    private final LeaveRequestRepository requestRepository;
    private final AttendanceRepository attendanceRepository;

    public LeaveAccrualService(
            LeaveBalanceRepository balanceRepository,
            LeaveTransactionRepository transactionRepository,
            LeaveRequestRepository requestRepository,
            AttendanceRepository attendanceRepository) {
        this.balanceRepository = balanceRepository;
        this.transactionRepository = transactionRepository;
        this.requestRepository = requestRepository;
        this.attendanceRepository = attendanceRepository;
    }

    /**
     * Recomputes and persists the accrued / used / pending figures for one balance,
     * posting any missing monthly ACCRUAL transactions along the way.
     */
    public void recalculate(LeaveBalance balance) {
        Employee employee = balance.getEmployee();
        LeavePolicy policy = balance.getLeavePolicy();
        Integer year = balance.getYear();

        LocalDate now = LocalDate.now();
        int currentMonth = now.getYear() == year ? now.getMonthValue() : 12;
        int startMonth = getStartMonth(employee, year);

        double totalAccrued = 0.0;

        for (int month = startMonth; month <= currentMonth; month++) {
            if (!hasAccrualTransaction(balance.getId(), month, year)) {
                double monthlyAccrual = calculateMonthlyAccrual(employee, policy, year, month);
                totalAccrued += monthlyAccrual;

                if (monthlyAccrual > 0) {
                    createAccrualTransaction(balance, monthlyAccrual, year, month);
                }
            } else {
                Double existingAccrual = transactionRepository.findByBalanceAndTypeAndMonthYear(
                                balance.getId(), TransactionType.ACCRUAL, month, year)
                        .stream()
                        .mapToDouble(LeaveTransaction::getDays)
                        .sum();
                totalAccrued += existingAccrual;
            }
        }

        if (policy.getAnnualQuota() != null) {
            totalAccrued = Math.min(totalAccrued, policy.getAnnualQuota());
        }

        balance.setAccrued(totalAccrued);

        Double usedDays = requestRepository.sumApprovedDaysByEmployeePolicyYear(
                employee.getId(), policy.getId(), year);
        Double pendingDays = requestRepository.sumPendingDaysByEmployeePolicyYear(
                employee.getId(), policy.getId(), year);

        balance.setUsed(usedDays != null ? usedDays : 0.0);
        balance.setPending(pendingDays != null ? pendingDays : 0.0);
        balance.setLastCalculationMonth(currentMonth);
        balance.setLastCalculatedAt(LocalDateTime.now());

        balanceRepository.save(balance);
    }

    private int getStartMonth(Employee employee, Integer year) {
        LocalDateTime joiningDate = employee.getJoiningDate();

        // If no joining date, assume employee has been here since start of year
        if (joiningDate == null) {
            return 1;
        }

        int joiningYear = joiningDate.getYear();

        // Employee joined before this year - start from January
        if (joiningYear < year) {
            return 1;
        }

        // Employee joins this year - start from their joining month
        if (joiningYear == year) {
            return joiningDate.getMonthValue();
        }

        // Employee joining date is in the future (data issue) - still allow from January
        // This is lenient to handle data entry issues
        return 1;
    }

    private double calculateMonthlyAccrual(Employee employee, LeavePolicy policy, Integer year, Integer month) {
        // Calculate the default monthly accrual
        double defaultMonthlyAccrual;
        if (policy.getAccrualRatePerMonth() != null && policy.getAccrualRatePerMonth() > 0) {
            defaultMonthlyAccrual = policy.getAccrualRatePerMonth();
        } else if (policy.getAnnualQuota() != null && policy.getAnnualQuota() > 0) {
            defaultMonthlyAccrual = policy.getAnnualQuota() / 12.0;
        } else {
            // No valid quota configured - return 0
            return 0.0;
        }

        // For past months or if attendance tracking isn't being used, return full accrual
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        LocalDate today = LocalDate.now();

        // If the month hasn't started yet, no accrual
        if (monthStart.isAfter(today)) {
            return 0.0;
        }

        try {
            List<Attendance> records = attendanceRepository.findByEmployeeIdAndAttendanceDateBetween(
                    employee.getId(), monthStart, monthEnd);

            // If no attendance data exists, give full accrual (attendance-based accrual is optional)
            if (records == null || records.isEmpty()) {
                return defaultMonthlyAccrual;
            }

            // Count days with actual attendance records (non-ABSENT status)
            long daysPresent = records.stream()
                    .filter(a -> a.getStatus() == AttendanceStatus.PRESENT ||
                                 a.getStatus() == AttendanceStatus.LATE)
                    .count();

            long halfDays = records.stream()
                    .filter(a -> a.getStatus() == AttendanceStatus.HALF_DAY)
                    .count();

            // Check if there's ANY actual attendance tracking happening
            // If all days are ABSENT, it means attendance isn't being tracked - give full accrual
            long daysWithAttendanceRecords = records.stream()
                    .filter(a -> a.getStatus() != AttendanceStatus.ABSENT)
                    .count();

            if (daysWithAttendanceRecords == 0) {
                // No attendance tracking - give full accrual
                return defaultMonthlyAccrual;
            }

            double effectiveDays = daysPresent + (halfDays * 0.5);

            long totalWorkingDays = calculateWorkingDaysInMonth(year, month);

            if (totalWorkingDays == 0) {
                return defaultMonthlyAccrual;
            }

            double attendanceRatio = effectiveDays / totalWorkingDays;
            return defaultMonthlyAccrual * attendanceRatio;

        } catch (Exception e) {
            // On any error, default to full accrual
            return defaultMonthlyAccrual;
        }
    }

    private long calculateWorkingDaysInMonth(int year, int month) {
        YearMonth yearMonth = YearMonth.of(year, month);
        long workingDays = 0;

        for (int day = 1; day <= yearMonth.lengthOfMonth(); day++) {
            LocalDate date = LocalDate.of(year, month, day);
            DayOfWeek dayOfWeek = date.getDayOfWeek();
            if (dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY) {
                workingDays++;
            }
        }

        return workingDays;
    }

    private boolean hasAccrualTransaction(Long balanceId, Integer month, Integer year) {
        return transactionRepository.existsByLeaveBalanceIdAndTransactionTypeAndReferenceMonthAndReferenceYear(
                balanceId, TransactionType.ACCRUAL, month, year);
    }

    private void createAccrualTransaction(LeaveBalance balance, double days, Integer year, Integer month) {
        double balanceBefore = balance.getOpeningBalance() + balance.getAccrued();

        LeaveTransaction transaction = new LeaveTransaction();
        transaction.setEmployee(balance.getEmployee());
        transaction.setOrganization(balance.getEmployee().getOrganization());
        transaction.setLeaveBalance(balance);
        transaction.setTransactionType(TransactionType.ACCRUAL);
        transaction.setDays(days);
        transaction.setBalanceBefore(balanceBefore);
        transaction.setBalanceAfter(balanceBefore + days);
        transaction.setTransactionDate(LocalDate.of(year, month, 1));
        transaction.setReferenceMonth(month);
        transaction.setReferenceYear(year);
        transaction.setDescription("Monthly accrual for " + YearMonth.of(year, month));

        transactionRepository.save(transaction);
    }
}
