package org.tornotron.echno_backend.leave;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.DtoConversions.LeaveRequestDtoConvertor;
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

    public LeaveApprovalService(
            LeaveApprovalRepository approvalRepository,
            LeaveRequestRepository requestRepository,
            LeaveBalanceRepository balanceRepository,
            LeaveTransactionRepository transactionRepository,
            EmployeeRepository employeeRepository,
            @Lazy LeaveCalendarService calendarService,
            @Lazy NotificationService notificationService) {
        this.approvalRepository = approvalRepository;
        this.requestRepository = requestRepository;
        this.balanceRepository = balanceRepository;
        this.transactionRepository = transactionRepository;
        this.employeeRepository = employeeRepository;
        this.calendarService = calendarService;
        this.notificationService = notificationService;
    }

    @Transactional
    public void initializeApprovalChain(LeaveRequest request) {
        List<Employee> approvers = resolveApprovalChain(request.getEmployee());

        if (approvers.isEmpty()) {
            finalizeApproval(request);
            return;
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

    @Transactional
    public LeaveRequestDto approve(Long requestId, LeaveApprovalActionDto dto) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request not found with id: " + requestId));

        validateApprover(request, dto.getApproverId());

        LeaveApproval approval = approvalRepository
                .findByLeaveRequestIdAndApprovalLevel(requestId, request.getCurrentApprovalLevel())
                .orElseThrow(() -> new ResourceNotFoundException("Approval record not found"));

        approval.setAction(ApprovalAction.APPROVED);
        approval.setComments(dto.getComments());
        approval.setActionAt(LocalDateTime.now());
        approvalRepository.save(approval);

        if (request.getCurrentApprovalLevel() < request.getMaxApprovalLevel()) {
            advanceToNextLevel(request);
        } else {
            finalizeApproval(request);
        }

        return LeaveRequestDtoConvertor.convertToDto(request);
    }

    @Transactional
    public LeaveRequestDto reject(Long requestId, LeaveApprovalActionDto dto) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request not found with id: " + requestId));

        validateApprover(request, dto.getApproverId());

        LeaveApproval approval = approvalRepository
                .findByLeaveRequestIdAndApprovalLevel(requestId, request.getCurrentApprovalLevel())
                .orElseThrow(() -> new ResourceNotFoundException("Approval record not found"));

        approval.setAction(ApprovalAction.REJECTED);
        approval.setComments(dto.getComments());
        approval.setActionAt(LocalDateTime.now());
        approvalRepository.save(approval);

        request.setStatus(LeaveStatus.REJECTED);
        request.setCurrentApprover(null);
        requestRepository.save(request);

        restorePendingBalance(request);

        notificationService.sendLeaveDecisionNotification(request, ApprovalAction.REJECTED);

        return LeaveRequestDtoConvertor.convertToDto(request);
    }

    @Transactional
    public LeaveRequestDto delegate(Long requestId, LeaveApprovalActionDto dto) {
        if (dto.getDelegateToId() == null) {
            throw new InvalidRequestException("Delegate to ID is required");
        }

        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request not found with id: " + requestId));

        validateApprover(request, dto.getApproverId());

        Employee delegateTo = employeeRepository.findByIdAndOrganizationId(dto.getDelegateToId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee not found with id: " + dto.getDelegateToId()));

        LeaveApproval currentApproval = approvalRepository
                .findByLeaveRequestIdAndApprovalLevel(requestId, request.getCurrentApprovalLevel())
                .orElseThrow();

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

        return LeaveRequestDtoConvertor.convertToDto(request);
    }

    @Transactional(readOnly = true)
    public List<LeaveApprovalDto> getApprovalHistory(Long requestId) {
        return approvalRepository.findByLeaveRequestIdOrderByApprovalLevelAsc(requestId)
                .stream()
                .map(LeaveRequestDtoConvertor::convertApprovalToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveApprovalDto> getApprovalChain(Long requestId) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request not found with id: " + requestId));

        return approvalRepository.findByLeaveRequestIdOrderByApprovalLevelAsc(requestId)
                .stream()
                .map(LeaveRequestDtoConvertor::convertApprovalToDto)
                .collect(Collectors.toList());
    }

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
            throw new InvalidRequestException("Request is not pending approval");
        }

        if (request.getCurrentApprover() == null ||
            !request.getCurrentApprover().getId().equals(approverId)) {
            throw new InvalidRequestException("You are not the current approver for this request");
        }
    }

    private void advanceToNextLevel(LeaveRequest request) {
        int nextLevel = request.getCurrentApprovalLevel() + 1;

        LeaveApproval nextApproval = approvalRepository
                .findByLeaveRequestIdAndApprovalLevel(request.getId(), nextLevel)
                .orElseThrow(() -> new ResourceNotFoundException("Next approval level not found"));

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
}
