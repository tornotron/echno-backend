package org.tornotron.echno_backend.leave;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalActionDto;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.enums.NotificationType;
import org.tornotron.echno_backend.leave.enums.TransactionType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Drives the multi-level approval workflow for leave requests and the balance moves it triggers.
 *
 * <p>Builds the approver chain from the employee's management line, advances a request level by
 * level on approval, and on the final approval transfers days from pending to used and posts a
 * deduction ledger entry. Rejection releases the pending hold. Approve and reject take a
 * pessimistic lock on the request so two concurrent decisions cannot both finalize and deduct twice.
 */
@Service
@Validated
public class LeaveApprovalService {

    private final LeaveApprovalRepository approvalRepository;
    private final LeaveRequestRepository requestRepository;
    private final LeaveBalanceRepository balanceRepository;
    private final LeaveTransactionRepository transactionRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveCalendarService calendarService;
    private final NotificationService notificationService;
    private final LeaveRequestMapper leaveRequestMapper;

    public LeaveApprovalService(
            LeaveApprovalRepository approvalRepository,
            LeaveRequestRepository requestRepository,
            LeaveBalanceRepository balanceRepository,
            LeaveTransactionRepository transactionRepository,
            EmployeeRepository employeeRepository,
            @Lazy LeaveCalendarService calendarService,
            @Lazy NotificationService notificationService,
            LeaveRequestMapper leaveRequestMapper) {
        this.approvalRepository = approvalRepository;
        this.requestRepository = requestRepository;
        this.balanceRepository = balanceRepository;
        this.transactionRepository = transactionRepository;
        this.employeeRepository = employeeRepository;
        this.calendarService = calendarService;
        this.notificationService = notificationService;
        this.leaveRequestMapper = leaveRequestMapper;
    }

    /**
     * Builds and stores the approval chain for a submitted request and notifies the first approver.
     *
     * <p>The applicable leave policy (the one the request was raised under) decides the shape of the
     * chain. When {@code multiLevelApprovalEnabled} is true (the default, preserving today's
     * behaviour) the full management line is used, so the request advances level by level. When an
     * organization opts out by setting it false, only the direct approver is enrolled
     * ({@code maxApprovalLevel = 1}), so a single approval finalizes the request.
     *
     * <p>When the employee has no resolvable approvers, the request is finalized immediately
     * instead of waiting for an approval, regardless of the toggle.
     *
     * @param request The submitted leave request to route for approval.
     */
    @Transactional
    public void initializeApprovalChain(LeaveRequest request) {
        List<Employee> approvers = resolveApprovalChain(request.getEmployee());

        if (approvers.isEmpty()) {
            finalizeApproval(request);
            return;
        }

        if (!isMultiLevelApprovalEnabled(request)) {
            // Organization opted out of multi-level approval: only the direct
            // approver decides, so one approval finalizes the request.
            approvers = approvers.subList(0, 1);
        }

        request.setMaxApprovalLevel(approvers.size());
        request.setCurrentApprovalLevel(1);
        request.setCurrentApprover(approvers.get(0));
        requestRepository.save(request);

        for (int i = 0; i < approvers.size(); i++) {
            LeaveApproval approval = new LeaveApproval();
            approval.setLeaveRequest(request);
            approval.setOrganization(request.getOrganization());
            approval.setApprover(approvers.get(i));
            approval.setApprovalLevel(i + 1);
            approval.setAction(ApprovalAction.PENDING);
            approvalRepository.save(approval);
        }

        notificationService.sendApprovalRequiredNotification(request, approvers.get(0));
    }

    /**
     * Walks up the employee's management line to build the ordered list of approvers.
     *
     * <p>Stops at five levels and guards against self-referencing or cyclic manager links.
     *
     * @param employee The employee whose approval chain is being resolved.
     * @return The approvers in order from immediate manager upward; empty if there is none.
     */
    @Transactional(readOnly = true)
    public List<Employee> resolveApprovalChain(Employee employee) {
        List<Employee> chain = new ArrayList<>();
        Employee currentManager = employee.getManager();

        int maxDepth = 5;
        int depth = 0;

        while (currentManager != null && depth < maxDepth) {
            if (currentManager.getId().equals(employee.getId())) {
                break; // Avoid self-referencing loops if any
            }

            Employee finalCurrentManager = currentManager;
            if (chain.stream().anyMatch(e -> e.getId().equals(finalCurrentManager.getId()))) {
                break; // Avoid loops
            }

            chain.add(currentManager);
            currentManager = currentManager.getManager();
            depth++;
        }

        return chain;
    }

    /**
     * Resolves whether the request's leave policy opts in to multi-level approval.
     *
     * <p>Reads the toggle from the policy the request was raised under (already resolved and attached
     * to the request when it was submitted, the same policy the rest of the leave flow uses). Defaults
     * to {@code true} when no policy or no explicit value is present, so the historical full-chain
     * behaviour is preserved.
     *
     * @param request The leave request being routed for approval.
     * @return {@code true} to build the full management-line chain; {@code false} for single-level.
     */
    private boolean isMultiLevelApprovalEnabled(LeaveRequest request) {
        LeavePolicy policy = request.getLeavePolicy();
        if (policy == null || policy.getMultiLevelApprovalEnabled() == null) {
            return true;
        }
        return policy.getMultiLevelApprovalEnabled();
    }

    /**
     * Records the current approver's approval and advances or finalizes the request.
     *
     * <p>Takes a pessimistic lock on the request to serialize concurrent decisions. If more
     * levels remain the next approver is notified; otherwise the request is approved, pending days
     * are moved to used, a deduction ledger entry is posted, and calendar entries are created.
     *
     * @param requestId The ID of the leave request being approved.
     * @param dto The approver's ID and optional comments.
     * @return The updated leave request.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     * @throws InvalidRequestException if the request is not pending approval or the caller is not the current approver.
     */
    @Transactional
    public LeaveRequestDto approve(Long requestId, LeaveApprovalActionDto dto) {
        // Pessimistic lock: concurrent approve/reject on the same request must
        // serialize, or both read a stale PENDING_APPROVAL status and finalize
        // twice (double balance deduction / advancing the chain twice).
        LeaveRequest request = requestRepository.lockByIdAndOrganizationId(requestId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        validateApprover(request, dto.getApproverId());

        LeaveApproval approval = getPendingApproval(request.getId(), request.getCurrentApprovalLevel());

        approval.setAction(ApprovalAction.APPROVED);
        approval.setComments(dto.getComments());
        approval.setActionAt(LocalDateTime.now());
        approvalRepository.save(approval);

        if (request.getCurrentApprovalLevel() < request.getMaxApprovalLevel()) {
            advanceToNextLevel(request);
        } else {
            finalizeApproval(request);
        }

        return leaveRequestMapper.toDto(request);
    }

    /**
     * Records the current approver's rejection, ends the workflow, and releases the pending hold.
     *
     * <p>Takes a pessimistic lock on the request so it cannot be approved and rejected at once.
     * The request moves to rejected, its current approver is cleared, the pending days are
     * restored to the balance, and the employee is notified.
     *
     * @param requestId The ID of the leave request being rejected.
     * @param dto The approver's ID and optional comments.
     * @return The updated leave request.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     * @throws InvalidRequestException if the request is not pending approval or the caller is not the current approver.
     */
    @Transactional
    public LeaveRequestDto reject(Long requestId, LeaveApprovalActionDto dto) {
        LeaveRequest request = requestRepository.lockByIdAndOrganizationId(requestId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        validateApprover(request, dto.getApproverId());

        LeaveApproval approval = getPendingApproval(request.getId(), request.getCurrentApprovalLevel());

        approval.setAction(ApprovalAction.REJECTED);
        approval.setComments(dto.getComments());
        approval.setActionAt(LocalDateTime.now());
        approvalRepository.save(approval);

        request.setStatus(LeaveStatus.REJECTED);
        request.setCurrentApprover(null);
        requestRepository.save(request);

        restorePendingBalance(request);

        notificationService.sendLeaveDecisionNotification(request, ApprovalAction.REJECTED);

        return leaveRequestMapper.toDto(request);
    }

    /**
     * Delegates the current approval level to another employee.
     *
     * <p>Marks the current approver's record as delegated and creates a fresh pending record for
     * the delegate at the same level, with a back-reference to the delegating approver. The
     * request's current approver becomes the delegate, who is notified.
     *
     * @param requestId The ID of the leave request being delegated.
     * @param dto The delegating approver's ID, the target delegate ID, and optional comments.
     * @return The updated leave request.
     * @throws InvalidRequestException if no delegate ID is supplied, the request is not pending approval, or the caller is not the current approver.
     * @throws ResourceNotFoundException if the request or the delegate is not found in this organization.
     */
    @Transactional
    public LeaveRequestDto delegate(Long requestId, LeaveApprovalActionDto dto) {
        if (dto.getDelegateToId() == null) {
            throw new InvalidRequestException("A delegateToId is required to delegate a leave request");
        }

        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        validateApprover(request, dto.getApproverId());

        Employee delegateTo = employeeRepository.findByIdAndOrganizationId(dto.getDelegateToId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Delegate (employee) with ID " + dto.getDelegateToId() + " was not found in this organization"));

        LeaveApproval currentApproval = getPendingApproval(request.getId(), request.getCurrentApprovalLevel());

        currentApproval.setAction(ApprovalAction.DELEGATED);
        currentApproval.setComments(dto.getComments());
        currentApproval.setActionAt(LocalDateTime.now());
        approvalRepository.save(currentApproval);

        LeaveApproval delegatedApproval = new LeaveApproval();
        delegatedApproval.setLeaveRequest(request);
        delegatedApproval.setOrganization(request.getOrganization());
        delegatedApproval.setApprover(delegateTo);
        delegatedApproval.setApprovalLevel(request.getCurrentApprovalLevel());
        delegatedApproval.setAction(ApprovalAction.PENDING);
        delegatedApproval.setDelegatedFromId(dto.getApproverId());
        approvalRepository.save(delegatedApproval);

        request.setCurrentApprover(delegateTo);
        requestRepository.save(request);

        notificationService.sendDelegationNotification(request, delegateTo, dto.getApproverId());

        return leaveRequestMapper.toDto(request);
    }

    /**
     * Lists all approval records for a request, ordered by approval level.
     *
     * @param requestId The ID of the leave request.
     * @return The approval records in level order.
     */
    @Transactional(readOnly = true)
    public List<LeaveApprovalDto> getApprovalHistory(Long requestId) {
        return approvalRepository.findByLeaveRequestIdOrderByApprovalLevelAsc(requestId)
                .stream()
                .map(leaveRequestMapper::toApprovalDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists the approval records for a request after verifying the request exists in this organization.
     *
     * @param requestId The ID of the leave request.
     * @return The approval records in level order.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     */
    @Transactional(readOnly = true)
    public List<LeaveApprovalDto> getApprovalChain(Long requestId) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        return approvalRepository.findByLeaveRequestIdOrderByApprovalLevelAsc(requestId)
                .stream()
                .map(leaveRequestMapper::toApprovalDto)
                .collect(Collectors.toList());
    }

    /**
     * Checks whether an employee is the current approver of a request that is pending approval.
     *
     * @param requestId The ID of the leave request.
     * @param employeeId The ID of the employee to check.
     * @return {@code true} if the employee may act on the request now; {@code false} otherwise.
     */
    @Transactional(readOnly = true)
    public boolean canApprove(Long requestId, Long employeeId) {
        return requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .map(request -> request.getCurrentApprover() != null &&
                               request.getCurrentApprover().getId().equals(employeeId) &&
                               request.getStatus() == LeaveStatus.PENDING_APPROVAL)
                .orElse(false);
    }

    private void validateApprover(LeaveRequest request, Long approverId) {
        if (request.getStatus() != LeaveStatus.PENDING_APPROVAL) {
            throw new InvalidRequestException(
                    "Leave request with ID " + request.getId() + " is not pending approval (current status: " + request.getStatus() + ")");
        }

        if (request.getCurrentApprover() == null ||
            !request.getCurrentApprover().getId().equals(approverId)) {
            throw new InvalidRequestException(
                    "Employee with ID " + approverId + " is not the current approver for leave request " + request.getId());
        }
    }

    private void advanceToNextLevel(LeaveRequest request) {
        int nextLevel = request.getCurrentApprovalLevel() + 1;

        LeaveApproval nextApproval = getPendingApproval(request.getId(), nextLevel);

        request.setCurrentApprovalLevel(nextLevel);
        request.setCurrentApprover(nextApproval.getApprover());
        requestRepository.save(request);

        notificationService.sendApprovalRequiredNotification(request, nextApproval.getApprover());
    }

    private void finalizeApproval(LeaveRequest request) {
        request.setStatus(LeaveStatus.APPROVED);
        request.setCurrentApprover(null);
        requestRepository.save(request);

        transferPendingToUsed(request);

        createDeductionTransaction(request);

        calendarService.createCalendarEntries(request);

        notificationService.sendLeaveDecisionNotification(request, ApprovalAction.APPROVED);
    }

    private void transferPendingToUsed(LeaveRequest request) {
        int year = request.getStartDate().getYear();

        balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(
                        request.getEmployee().getId(),
                        request.getLeavePolicy().getId(),
                        year)
                .ifPresent(balance -> {
                    balance.setPending(Math.max(0, balance.getPending() - request.getTotalDays()));
                    balance.setUsed(balance.getUsed() + request.getTotalDays());
                    balanceRepository.save(balance);
                });
    }

    private void restorePendingBalance(LeaveRequest request) {
        int year = request.getStartDate().getYear();

        balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(
                        request.getEmployee().getId(),
                        request.getLeavePolicy().getId(),
                        year)
                .ifPresent(balance -> {
                    balance.setPending(Math.max(0, balance.getPending() - request.getTotalDays()));
                    balanceRepository.save(balance);
                });
    }

    private void createDeductionTransaction(LeaveRequest request) {
        int year = request.getStartDate().getYear();

        balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(
                        request.getEmployee().getId(),
                        request.getLeavePolicy().getId(),
                        year)
                .ifPresent(balance -> {
                    double balanceBefore = balance.getAvailableBalance() + request.getTotalDays();
                    double balanceAfter = balance.getAvailableBalance();

                    LeaveTransaction transaction = new LeaveTransaction();
                    transaction.setEmployee(request.getEmployee());
                    transaction.setOrganization(request.getOrganization());
                    transaction.setLeaveBalance(balance);
                    transaction.setLeaveRequest(request);
                    transaction.setTransactionType(TransactionType.DEDUCTION);
                    transaction.setDays(-request.getTotalDays());
                    transaction.setBalanceBefore(balanceBefore);
                    transaction.setBalanceAfter(balanceAfter);
                    transaction.setTransactionDate(LocalDate.now());
                    transaction.setDescription("Leave approved: " + request.getRequestNumber());

                    transactionRepository.save(transaction);
                });
    }

    private LeaveApproval getPendingApproval(Long requestId, Integer approvalLevel) {
        return approvalRepository
                .findFirstByLeaveRequestIdAndApprovalLevelAndActionOrderByCreatedAtDesc(
                        requestId,
                        approvalLevel,
                        ApprovalAction.PENDING)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No pending approval record was found for leave request " + requestId +
                        " at approval level " + approvalLevel));
    }
}
