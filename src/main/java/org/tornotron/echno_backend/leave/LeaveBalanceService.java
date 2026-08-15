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
        summary.setTotalAvailable(balances.stream().mapToDouble(LeaveBalance::getAvailableBalance).sum());
        summary.setTotalUsed(balances.stream().mapToDouble(LeaveBalance::getUsed).sum());
        summary.setTotalPending(balances.stream().mapToDouble(LeaveBalance::getPending).sum());
        return summary;
    }

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
        return leaveTransactionMapper.toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<LeaveTransactionDto> getTransactionHistory(Long employeeId) {
        return transactionRepository.findByEmployeeIdOrderByCreatedAtDesc(employeeId)
                .stream()
                .map(leaveTransactionMapper::toDto)
                .collect(Collectors.toList());
    }

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
