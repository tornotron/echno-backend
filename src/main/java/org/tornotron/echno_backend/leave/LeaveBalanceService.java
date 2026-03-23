package org.tornotron.echno_backend.leave;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.DtoConversions.LeaveBalanceDtoConvertor;
import org.tornotron.echno_backend.DtoConversions.LeaveTransactionDtoConvertor;
import org.tornotron.echno_backend.attendance.Attendance;
import org.tornotron.echno_backend.attendance.AttendanceRepository;
import org.tornotron.echno_backend.attendance.enums.AttendanceStatus;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceAdjustmentDto;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceDto;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceSummaryDto;
import org.tornotron.echno_backend.leave.dto.LeaveTransactionDto;
import org.tornotron.echno_backend.leave.enums.TransactionType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Validated
public class LeaveBalanceService {

    private final LeaveBalanceRepository balanceRepository;
    private final LeavePolicyRepository policyRepository;
    private final LeaveTransactionRepository transactionRepository;
    private final LeaveRequestRepository requestRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;

    public LeaveBalanceService(
            LeaveBalanceRepository balanceRepository,
            LeavePolicyRepository policyRepository,
            LeaveTransactionRepository transactionRepository,
            LeaveRequestRepository requestRepository,
            EmployeeRepository employeeRepository,
            AttendanceRepository attendanceRepository) {
        this.balanceRepository = balanceRepository;
        this.policyRepository = policyRepository;
        this.transactionRepository = transactionRepository;
        this.requestRepository = requestRepository;
        this.employeeRepository = employeeRepository;
        this.attendanceRepository = attendanceRepository;
    }

    @Transactional
    public LeaveBalanceDto getOrCalculateBalance(Long employeeId, Long policyId, Integer year) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found with id: " + employeeId));

        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy not found with id: " + policyId));

        LeaveBalance balance = balanceRepository
                .findByEmployeeIdAndLeavePolicyIdAndYear(employeeId, policyId, year)
                .orElseGet(() -> initializeBalance(employee, policy, year));

        if (needsRecalculation(balance, year)) {
            recalculateBalance(balance);
        }

        return LeaveBalanceDtoConvertor.convertToDto(balance);
    }

    @Transactional
    public List<LeaveBalanceDto> getAllBalancesForEmployee(Long employeeId, Integer year) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found with id: " + employeeId));

        List<LeavePolicy> policies = policyRepository.findApplicablePolicies(
                employee.getOrganization().getId(),
                employee.getGender(),
                calculateServiceMonths(employee));

        return policies.stream()
                .map(policy -> getOrCalculateBalance(employeeId, policy.getId(), year))
                .collect(Collectors.toList());
    }

    @Transactional
    public LeaveBalanceSummaryDto getBalanceSummary(Long employeeId, Integer year) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found with id: " + employeeId));

        // Get applicable policies and ensure balances are initialized
        List<LeavePolicy> policies = policyRepository.findApplicablePolicies(
                employee.getOrganization().getId(),
                employee.getGender(),
                calculateServiceMonths(employee));

        // Initialize balances for all applicable policies (creates if not exists)
        List<LeaveBalance> balances = policies.stream()
                .map(policy -> {
                    LeaveBalance balance = balanceRepository
                            .findByEmployeeIdAndLeavePolicyIdAndYear(employeeId, policy.getId(), year)
                            .orElseGet(() -> initializeBalance(employee, policy, year));

                    if (needsRecalculation(balance, year)) {
                        recalculateBalance(balance);
                    }
                    return balance;
                })
                .collect(java.util.stream.Collectors.toList());

        return LeaveBalanceDtoConvertor.convertToSummaryDto(
                employeeId,
                employee.getEmployeeName(),
                year,
                balances);
    }

    @Transactional
    public LeaveBalanceDto recalculateBalance(Long employeeId, Long policyId, Integer year) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found with id: " + employeeId));

        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy not found with id: " + policyId));

        LeaveBalance balance = balanceRepository
                .findByEmployeeIdAndLeavePolicyIdAndYear(employeeId, policyId, year)
                .orElseGet(() -> initializeBalance(employee, policy, year));

        recalculateBalance(balance);
        return LeaveBalanceDtoConvertor.convertToDto(balance);
    }

    @Transactional
    public LeaveTransactionDto adjustBalance(LeaveBalanceAdjustmentDto dto) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(dto.getEmployeeId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found with id: " + dto.getEmployeeId()));

        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(dto.getLeavePolicyId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy not found with id: " + dto.getLeavePolicyId()));

        int year = LocalDate.now().getYear();
        LeaveBalance balance = balanceRepository
                .findByEmployeeIdAndLeavePolicyIdAndYear(dto.getEmployeeId(), dto.getLeavePolicyId(), year)
                .orElseGet(() -> initializeBalance(employee, policy, year));

        double balanceBefore = balance.getAvailableBalance();
        double balanceAfter = balanceBefore + dto.getDays();

        if (dto.getDays() > 0) {
            balance.setAccrued(balance.getAccrued() + dto.getDays());
        } else {
            balance.setUsed(balance.getUsed() + Math.abs(dto.getDays()));
        }

        balance = balanceRepository.save(balance);

        LeaveTransaction transaction = new LeaveTransaction();
        transaction.setEmployee(employee);
        transaction.setOrganization(employee.getOrganization());
        transaction.setLeaveBalance(balance);
        transaction.setTransactionType(TransactionType.ADJUSTMENT);
        transaction.setDays(dto.getDays());
        transaction.setBalanceBefore(balanceBefore);
        transaction.setBalanceAfter(balanceAfter);
        transaction.setTransactionDate(LocalDate.now());
        transaction.setDescription(dto.getReason());
        transaction.setCreatedById(dto.getAdjustedById());

        LeaveTransaction saved = transactionRepository.save(transaction);
        return LeaveTransactionDtoConvertor.convertToDto(saved);
    }

    @Transactional(readOnly = true)
    public List<LeaveTransactionDto> getTransactionHistory(Long employeeId) {
        return transactionRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId)
                .stream()
                .map(LeaveTransactionDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveTransactionDto> getTransactionsByBalance(Long balanceId) {
        return transactionRepository.findByLeaveBalanceIdOrderByCreatedAtDesc(balanceId)
                .stream()
                .map(LeaveTransactionDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    private LeaveBalance initializeBalance(Employee employee, LeavePolicy policy, Integer year) {
        LeaveBalance balance = new LeaveBalance();
        balance.setEmployee(employee);
        balance.setOrganization(employee.getOrganization());
        balance.setLeavePolicy(policy);
        balance.setYear(year);
        balance.setOpeningBalance(0.0);
        balance.setAccrued(0.0);
        balance.setUsed(0.0);
        balance.setPending(0.0);
        balance.setCarryForwardFromPrevious(0.0);

        // Handle carry forward from previous year (only if employee joined before this year)
        LocalDateTime joiningDate = employee.getJoiningDate();
        if (joiningDate != null && year > joiningDate.getYear()) {
            processCarryForward(balance, year - 1);
        }

        return balanceRepository.save(balance);
    }

    private void processCarryForward(LeaveBalance balance, int previousYear) {
        LeavePolicy policy = balance.getLeavePolicy();

        balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(
                        balance.getEmployee().getId(),
                        policy.getId(),
                        previousYear)
                .ifPresent(previousBalance -> {
                    double available = previousBalance.getAvailableBalance();
                    double carryForward = available;

                    if (policy.getCarryForwardLimit() != null) {
                        carryForward = Math.min(available, policy.getCarryForwardLimit());
                    }

                    if (carryForward > 0) {
                        balance.setCarryForwardFromPrevious(carryForward);
                        balance.setOpeningBalance(carryForward);

                        if (policy.getCarryForwardExpiryMonths() != null) {
                            balance.setCarryForwardExpiryDate(
                                    LocalDate.of(balance.getYear(), 1, 1)
                                            .plusMonths(policy.getCarryForwardExpiryMonths()));
                        }
                    }
                });
    }

    private boolean needsRecalculation(LeaveBalance balance, Integer year) {
        if (balance.getLastCalculatedAt() == null) {
            return true;
        }

        LocalDate now = LocalDate.now();
        if (now.getYear() != year) {
            return false;
        }

        int currentMonth = now.getMonthValue();
        Integer lastMonth = balance.getLastCalculationMonth();

        return lastMonth == null || lastMonth < currentMonth;
    }

    private void recalculateBalance(LeaveBalance balance) {
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

    private int calculateServiceMonths(Employee employee) {
        LocalDateTime joiningDate = employee.getJoiningDate();
        if (joiningDate == null) {
            return 0;
        }
        return (int) java.time.temporal.ChronoUnit.MONTHS.between(
                joiningDate.toLocalDate(), LocalDate.now());
    }
}
