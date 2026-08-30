package org.tornotron.echno_backend.leave;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.leave.mapper.LeaveBalanceMapper;
import org.tornotron.echno_backend.leave.mapper.LeaveTransactionMapper;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceAdjustmentDto;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceDto;
import org.tornotron.echno_backend.leave.dto.LeaveBalanceSummaryDto;
import org.tornotron.echno_backend.leave.dto.LeaveTransactionDto;
import org.tornotron.echno_backend.leave.enums.TransactionType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads, initializes, and adjusts employee leave balances, and exposes the transaction ledger.
 *
 * <p>Lazily creates a balance row on first access (applying prior-year carry-forward within the
 * policy limit) and recomputes accrual when a new month has passed. Manual adjustments lock the
 * balance row so concurrent read-modify-write cannot lose updates or overdraw, and every
 * adjustment writes a {@link LeaveTransaction} for audit.
 */
@Service
@Validated
public class LeaveBalanceService {

    private final LeaveBalanceRepository balanceRepository;
    private final LeavePolicyRepository policyRepository;
    private final LeaveTransactionRepository transactionRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveTransactionMapper leaveTransactionMapper;
    private final LeaveBalanceMapper leaveBalanceMapper;
    private final LeaveAccrualService leaveAccrualService;

    public LeaveBalanceService(
            LeaveBalanceRepository balanceRepository,
            LeavePolicyRepository policyRepository,
            LeaveTransactionRepository transactionRepository,
            EmployeeRepository employeeRepository,
            LeaveTransactionMapper leaveTransactionMapper,
            LeaveBalanceMapper leaveBalanceMapper,
            LeaveAccrualService leaveAccrualService) {
        this.balanceRepository = balanceRepository;
        this.policyRepository = policyRepository;
        this.transactionRepository = transactionRepository;
        this.employeeRepository = employeeRepository;
        this.leaveTransactionMapper = leaveTransactionMapper;
        this.leaveBalanceMapper = leaveBalanceMapper;
        this.leaveAccrualService = leaveAccrualService;
    }

    /**
     * Returns the balance for an employee, policy, and year, creating and recalculating it as needed.
     *
     * <p>Years before the employee joined return a transient zero balance with no row persisted.
     * Otherwise a missing row is initialized, and accrual is recomputed when a new month has passed.
     *
     * @param employeeId The employee's ID.
     * @param policyId The leave policy's ID.
     * @param year The calendar year of the balance.
     * @return The current balance.
     * @throws ResourceNotFoundException if the employee or policy is not found in this organization.
     */
    @Transactional
    public LeaveBalanceDto getOrCalculateBalance(Long employeeId, Long policyId, Integer year) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));

        if (isBeforeJoiningYear(employee, year)) {
            return leaveBalanceMapper.toDto(zeroBalance(employee, policy, year));
        }

        LeaveBalance balance = balanceRepository
                .findByEmployeeIdAndLeavePolicyIdAndYear(employeeId, policyId, year)
                .orElseGet(() -> initializeBalance(employee, policy, year));

        if (needsRecalculation(balance, year)) {
            leaveAccrualService.recalculate(balance);
        }

        return leaveBalanceMapper.toDto(balance);
    }

    /**
     * Returns balances for every leave policy applicable to an employee in a given year.
     *
     * <p>Applicability is filtered by the employee's gender and service months, so only policies
     * the employee is eligible for are returned.
     *
     * @param employeeId The employee's ID.
     * @param year The calendar year of the balances.
     * @return One balance per applicable policy.
     * @throws ResourceNotFoundException if the employee is not found in this organization.
     */
    @Transactional
    public List<LeaveBalanceDto> getAllBalancesForEmployee(Long employeeId, Integer year) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        List<LeavePolicy> policies = policyRepository.findApplicablePolicies(
                employee.getOrganization().getId(),
                employee.getGender(),
                calculateServiceMonths(employee));

        return policies.stream()
                .map(policy -> getOrCalculateBalance(employeeId, policy.getId(), year))
                .collect(Collectors.toList());
    }

    /**
     * Builds a summary of an employee's balances for a year across all applicable policies.
     *
     * <p>Initializes and recalculates each policy's balance (pre-joining years contribute a
     * transient zero balance) and rolls the results into organization-wide totals for available,
     * used, and pending days.
     *
     * @param employeeId The employee's ID.
     * @param year The calendar year to summarize.
     * @return The per-policy balances and their totals.
     * @throws ResourceNotFoundException if the employee is not found in this organization.
     */
    @Transactional
    public LeaveBalanceSummaryDto getBalanceSummary(Long employeeId, Integer year) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        // Get applicable policies and ensure balances are initialized
        List<LeavePolicy> policies = policyRepository.findApplicablePolicies(
                employee.getOrganization().getId(),
                employee.getGender(),
                calculateServiceMonths(employee));

        // Initialize balances for all applicable policies (creates if not exists).
        // Pre-joining years return a transient zero balance — no row is persisted.
        List<LeaveBalance> balances = policies.stream()
                .map(policy -> {
                    if (isBeforeJoiningYear(employee, year)) {
                        return zeroBalance(employee, policy, year);
                    }
                    LeaveBalance balance = balanceRepository
                            .findByEmployeeIdAndLeavePolicyIdAndYear(employeeId, policy.getId(), year)
                            .orElseGet(() -> initializeBalance(employee, policy, year));

                    if (needsRecalculation(balance, year)) {
                        leaveAccrualService.recalculate(balance);
                    }
                    return balance;
                })
                .collect(java.util.stream.Collectors.toList());

        LeaveBalanceSummaryDto summary = new LeaveBalanceSummaryDto();
        summary.setEmployeeId(employeeId);
        summary.setEmployeeName(employee.getEmployeeName());
        summary.setYear(year);
        summary.setBalances(balances.stream().map(leaveBalanceMapper::toDto).collect(Collectors.toList()));
        // Rounded again after the sum: adding several rounded figures can still land on a
        // value the double type cannot hold exactly, and this total goes straight to a screen.
        summary.setTotalAvailable(LeaveDays.round(
                balances.stream().mapToDouble(LeaveBalance::getAvailableBalance).sum()));
        summary.setTotalUsed(LeaveDays.round(
                balances.stream().mapToDouble(LeaveBalance::getUsed).sum()));
        summary.setTotalPending(LeaveDays.round(
                balances.stream().mapToDouble(LeaveBalance::getPending).sum()));
        return summary;
    }

    /**
     * Forces a recalculation of accrual for one balance, initializing the row first if absent.
     *
     * @param employeeId The employee's ID.
     * @param policyId The leave policy's ID.
     * @param year The calendar year of the balance.
     * @return The recalculated balance.
     * @throws ResourceNotFoundException if the employee or policy is not found in this organization.
     */
    @Transactional
    public LeaveBalanceDto recalculateBalance(Long employeeId, Long policyId, Integer year) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(policyId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + policyId + " was not found in this organization"));

        LeaveBalance balance = balanceRepository
                .findByEmployeeIdAndLeavePolicyIdAndYear(employeeId, policyId, year)
                .orElseGet(() -> initializeBalance(employee, policy, year));

        leaveAccrualService.recalculate(balance);
        return leaveBalanceMapper.toDto(balance);
    }

    /**
     * Applies a manual balance adjustment for the current year and records it in the ledger.
     *
     * <p>Locks the balance row so concurrent adjustments serialize. A positive day count increases
     * accrued days; a negative count increases used days. The change is captured as an
     * {@link LeaveTransaction} with the before and after balances and the supplied reason.
     *
     * @param dto The employee, policy, signed day count, reason, and adjusting user.
     * @return The recorded adjustment transaction.
     * @throws ResourceNotFoundException if the employee or policy is not found in this organization.
     */
    @Transactional
    public LeaveTransactionDto adjustBalance(LeaveBalanceAdjustmentDto dto) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(dto.getEmployeeId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + dto.getEmployeeId() + " was not found in this organization"));

        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(dto.getLeavePolicyId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + dto.getLeavePolicyId() + " was not found in this organization"));

        int year = LocalDate.now().getYear();
        // Lock the balance row so concurrent adjustments serialize (read-modify-write
        // on accrued/used would otherwise lose updates and could overdraw).
        LeaveBalance balance = balanceRepository
                .lockByEmployeeIdAndLeavePolicyIdAndYear(dto.getEmployeeId(), dto.getLeavePolicyId(), year)
                .orElseGet(() -> initializeBalance(employee, policy, year));

        double balanceBefore = balance.getAvailableBalance();
        double balanceAfter = LeaveDays.round(balanceBefore + dto.getDays());

        if (dto.getDays() > 0) {
            balance.setAccrued(LeaveDays.round(balance.getAccrued() + dto.getDays()));
        } else {
            balance.setUsed(LeaveDays.round(balance.getUsed() + Math.abs(dto.getDays())));
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
        return leaveTransactionMapper.toDto(saved);
    }

    /**
     * Lists an employee's leave ledger entries, newest first.
     *
     * @param employeeId The employee's ID.
     * @return The transaction history.
     */
    @Transactional(readOnly = true)
    public List<LeaveTransactionDto> getTransactionHistory(Long employeeId) {
        return transactionRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId)
                .stream()
                .map(leaveTransactionMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists the ledger entries tied to a single balance, newest first.
     *
     * @param balanceId The leave balance's ID.
     * @return The transactions for that balance.
     */
    @Transactional(readOnly = true)
    public List<LeaveTransactionDto> getTransactionsByBalance(Long balanceId) {
        return transactionRepository.findByLeaveBalanceIdOrderByCreatedAtDesc(balanceId)
                .stream()
                .map(leaveTransactionMapper::toDto)
                .collect(Collectors.toList());
    }

    private boolean isBeforeJoiningYear(Employee employee, Integer year) {
        LocalDateTime joining = employee.getJoiningDate();
        return joining != null && year < joining.getYear();
    }

    private LeaveBalance zeroBalance(Employee employee, LeavePolicy policy, Integer year) {
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
        return balance;
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

    private int calculateServiceMonths(Employee employee) {
        LocalDateTime joiningDate = employee.getJoiningDate();
        if (joiningDate == null) {
            return 0;
        }
        return (int) java.time.temporal.ChronoUnit.MONTHS.between(
                joiningDate.toLocalDate(), LocalDate.now());
    }
}
