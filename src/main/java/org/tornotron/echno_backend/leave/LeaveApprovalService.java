package org.tornotron.echno_backend.leave;

import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.CurrentEmployeeService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalActionDto;
import org.tornotron.echno_backend.leave.dto.LeaveApprovalDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.enums.ApprovalAction;
import org.tornotron.echno_backend.leave.enums.LeaveApproverRole;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.leave.enums.NotificationType;
import org.tornotron.echno_backend.leave.enums.TransactionType;
import org.tornotron.echno_backend.modules.access.api.AccessPermission;
import org.tornotron.echno_backend.modules.access.api.AccessRoleHolders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Drives the approval of leave requests and the balance moves it triggers.
 *
 * <p>A request has one approver: the employee's immediate manager, or the one person the policy's
 * approver role names. That approver decides it. When they need somebody more senior to decide,
 * they can transfer it to a manager above them in their own reporting line, who then decides it
 * instead. There is no chain of approvals to climb. On approval the days move from pending to
 * used and a deduction ledger entry is posted; rejection releases the pending hold. Approve and
 * reject take a pessimistic lock on the request so two concurrent decisions cannot both finalize
 * and deduct twice.
 */
@Service
@Validated
public class LeaveApprovalService {

    private final LeaveApprovalRepository approvalRepository;
    private final LeaveRequestRepository requestRepository;
    private final LeaveBalanceRepository balanceRepository;
    private final LeaveTransactionRepository transactionRepository;
    private final EmployeeRepository employeeRepository;
    private final CurrentEmployeeService currentEmployeeService;
    private final LeaveAccess leaveAccess;
    private final LeaveCalendarService calendarService;
    private final NotificationService notificationService;
    private final LeaveRequestMapper leaveRequestMapper;
    private final LeaveRequestValidator leaveRequestValidator;
    private final AccessRoleHolders roleHolders;

    public LeaveApprovalService(
            LeaveApprovalRepository approvalRepository,
            LeaveRequestRepository requestRepository,
            LeaveBalanceRepository balanceRepository,
            LeaveTransactionRepository transactionRepository,
            EmployeeRepository employeeRepository,
            CurrentEmployeeService currentEmployeeService,
            LeaveAccess leaveAccess,
            @Lazy LeaveCalendarService calendarService,
            @Lazy NotificationService notificationService,
            LeaveRequestMapper leaveRequestMapper,
            @Lazy LeaveRequestValidator leaveRequestValidator,
            AccessRoleHolders roleHolders) {
        this.approvalRepository = approvalRepository;
        this.requestRepository = requestRepository;
        this.balanceRepository = balanceRepository;
        this.transactionRepository = transactionRepository;
        this.employeeRepository = employeeRepository;
        this.currentEmployeeService = currentEmployeeService;
        this.leaveAccess = leaveAccess;
        this.calendarService = calendarService;
        this.notificationService = notificationService;
        this.leaveRequestMapper = leaveRequestMapper;
        this.leaveRequestValidator = leaveRequestValidator;
        this.roleHolders = roleHolders;
    }

    /**
     * Routes a submitted request to its one approver and notifies them.
     *
     * <p>The approver is the person the policy's approver role names, or, for a policy that routes
     * to the reporting manager, the employee's immediate manager. Nobody further up the line is
     * enrolled: one approval decides the request ({@code maxApprovalLevel = 1}). The product owner
     * settled this on 8 Nov 2026 (ClickUp 14zdkkvzx68): leave goes to the manager one level above
     * and no further, and that manager can hand it upward by {@link #delegate} when it needs a
     * more senior decision. The per-policy multi-level toggle that used to switch the full chain
     * on is gone.
     *
     * <p>When the employee has no resolvable approver, the request is finalized immediately
     * instead of waiting for an approval.
     *
     * @param request The submitted leave request to route for approval.
     */
    @Transactional
    public void initializeApprovalChain(LeaveRequest request) {
        Optional<Employee> resolved = resolveRoleApprover(request);
        if (resolved.isEmpty()) {
            resolved = resolveApprovalChain(request.getEmployee()).stream().findFirst();
        }

        if (resolved.isEmpty()) {
            // Finalized before the submitting service has taken the pending hold, so there
            // is no hold of this request's to release: releasing its figure here would eat
            // into a hold that belongs to another pending request of the same employee.
            finalizeApproval(request, false);
            return;
        }

        Employee approver = resolved.get();
        request.setMaxApprovalLevel(1);
        request.setCurrentApprovalLevel(1);
        request.setCurrentApprover(approver);
        requestRepository.save(request);

        LeaveApproval approval = new LeaveApproval();
        approval.setLeaveRequest(request);
        approval.setOrganization(request.getOrganization());
        approval.setApprover(approver);
        approval.setApprovalLevel(1);
        approval.setAction(ApprovalAction.PENDING);
        approvalRepository.save(approval);

        notificationService.sendApprovalRequiredNotification(request, approver);
    }

    /**
     * The single approver a policy's {@code approverRole} names, when it names a tier rather than
     * the management line.
     *
     * <p>{@link LeaveApproverRole#REPORTING_MANAGER} answers empty so the caller walks the
     * management line as it always has. {@link LeaveApproverRole#HR_ADMIN} and
     * {@link LeaveApproverRole#SYSTEM_ADMIN} answer the lowest-id employee of the organization
     * holding that starting role across it who is not the requester. When nobody holds the role
     * the answer is empty too, and the management line decides rather than the request sitting
     * with no approver at all: a policy configured for a tier the organization has not staffed
     * should still be approvable by somebody.
     *
     * @param request The submitted request, with its policy attached.
     * @return The one approver, or empty when the management line applies.
     */
    private Optional<Employee> resolveRoleApprover(LeaveRequest request) {
        LeavePolicy policy = request.getLeavePolicy();
        if (policy == null || policy.getApproverRole() == null) {
            return Optional.empty();
        }
        String role = policy.getApproverRole().getStartingRole();
        if (role == null) {
            return Optional.empty();
        }
        Long requesterId = request.getEmployee().getId();
        return roleHolders.acrossOrganization(request.getOrganization().getId(), List.of(role))
                .stream()
                .filter(candidate -> !candidate.equals(requesterId))
                .min(java.util.Comparator.naturalOrder())
                .flatMap(employeeRepository::findById);
    }

    /**
     * Walks up the employee's management line, immediate manager first.
     *
     * <p>The first entry is who a reporting-manager policy routes a request to; the whole list is
     * who an approver may transfer a request to (see {@link #delegate}). Stops at five levels and
     * guards against self-referencing or cyclic manager links.
     *
     * @param employee The employee whose management line is being resolved.
     * @return The managers in order from immediate manager upward; empty if there is none.
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
     * Records the current approver's approval and finalizes the request.
     *
     * <p>Takes a pessimistic lock on the request to serialize concurrent decisions. One approval
     * decides a request: it is approved, pending days are moved to used, a deduction ledger entry
     * is posted, and calendar entries are created. A request raised before single approval, still
     * waiting on a higher level of its old chain, is decided by whoever it is waiting on now;
     * changeset 135 removed the levels above that.
     *
     * <p>The approver is the signed-in caller, not an id on the payload: see
     * {@link #resolveActingApprover}.
     *
     * @param requestId The ID of the leave request being approved.
     * @param dto Optional comments on the decision.
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

        validateApprover(request, resolveActingApprover());

        LeaveApproval approval = getPendingApproval(request.getId(), request.getCurrentApprovalLevel());

        approval.setAction(ApprovalAction.APPROVED);
        approval.setComments(dto.getComments());
        approval.setActionAt(LocalDateTime.now());
        approvalRepository.save(approval);

        finalizeApproval(request, true);

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
     * @param dto Optional comments on the decision.
     * @return The updated leave request.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     * @throws InvalidRequestException if the request is not pending approval or the caller is not the current approver.
     */
    @Transactional
    public LeaveRequestDto reject(Long requestId, LeaveApprovalActionDto dto) {
        LeaveRequest request = requestRepository.lockByIdAndOrganizationId(requestId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        validateApprover(request, resolveActingApprover());

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
     * Transfers the request to a manager above the current approver, who then decides it.
     *
     * <p>This is the "transfer to anyone above his level, if and only if needed" of ClickUp
     * 14zdkkvzx68: the immediate manager either decides the request or hands it upward. So the
     * target has to be in the current approver's own reporting line above them (their manager,
     * that manager's manager, and so on), and nobody else: a peer, a subordinate or the
     * requester is refused. The endpoint keeps its {@code delegate} name and payload so existing
     * clients keep working.
     *
     * <p>Marks the current approver's record as delegated and creates a fresh pending record for
     * the target at the same level, with a back-reference to the transferring approver. The
     * request's current approver becomes the target, who is notified.
     *
     * @param requestId The ID of the leave request being transferred.
     * @param dto The target employee ID, and optional comments.
     * @return The updated leave request.
     * @throws InvalidRequestException if no target ID is supplied, the request is not pending
     *     approval, the caller is not the current approver, or the target is not above the
     *     current approver in their reporting line.
     * @throws ResourceNotFoundException if the request or the target is not found in this organization.
     */
    @Transactional
    public LeaveRequestDto delegate(Long requestId, LeaveApprovalActionDto dto) {
        if (dto.getDelegateToId() == null) {
            throw new InvalidRequestException("A delegateToId is required to delegate a leave request");
        }

        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        Long actingApproverId = resolveActingApprover();
        validateApprover(request, actingApproverId);

        Employee delegateTo = employeeRepository.findByIdAndOrganizationId(dto.getDelegateToId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Delegate (employee) with ID " + dto.getDelegateToId() + " was not found in this organization"));

        requireAboveCurrentApprover(request, delegateTo);

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
        delegatedApproval.setDelegatedFromId(actingApproverId);
        approvalRepository.save(delegatedApproval);

        request.setCurrentApprover(delegateTo);
        requestRepository.save(request);

        notificationService.sendDelegationNotification(request, delegateTo, actingApproverId);

        return leaveRequestMapper.toDto(request);
    }

    /**
     * Refuses a transfer to anyone who is not above the current approver in their reporting line.
     *
     * @param request The request being transferred, with its current approver set.
     * @param target The employee the request would go to.
     * @throws InvalidRequestException if the target is not one of the current approver's managers,
     *     or is the employee the leave belongs to.
     */
    private void requireAboveCurrentApprover(LeaveRequest request, Employee target) {
        Long requesterId = request.getEmployee() != null ? request.getEmployee().getId() : null;
        boolean aboveInLine = resolveApprovalChain(request.getCurrentApprover()).stream()
                .anyMatch(manager -> manager.getId().equals(target.getId()));
        if (!aboveInLine || target.getId().equals(requesterId)) {
            throw new InvalidRequestException(
                    "A leave request can only be transferred to a manager above you in your reporting "
                            + "line. Employee " + target.getId() + " is not one of them.");
        }
    }

    /**
     * The trail of decisions on a request: who acted, at which level, with what comment and when.
     *
     * <p>This is the audit answer, so who may read it is settled against the record rather than
     * against a role alone. An approver has to be able to read the trail to decide: the request
     * arrives at them at level two knowing nothing about what level one said, and refusing them
     * the trail is refusing them the decision. So the employee the leave belongs to, everybody
     * named anywhere in its chain, and the leave administrators can read it, and nobody else can.
     *
     * @param requestId The ID of the leave request.
     * @return The approval records in level order.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     * @throws AccessDeniedException if the caller takes no part in this request and holds neither
     *     leave-administrator role.
     */
    @Transactional(readOnly = true)
    public List<LeaveApprovalDto> getApprovalHistory(Long requestId) {
        return readApprovalTrail(requestId);
    }

    /**
     * The same trail, including the levels nobody has acted on yet.
     *
     * <p>Kept as a separate endpoint because the two answer different questions for a caller, and
     * gated identically because they read the same rows.
     *
     * @param requestId The ID of the leave request.
     * @return The approval records in level order.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     * @throws AccessDeniedException if the caller takes no part in this request and holds neither
     *     leave-administrator role.
     */
    @Transactional(readOnly = true)
    public List<LeaveApprovalDto> getApprovalChain(Long requestId) {
        return readApprovalTrail(requestId);
    }

    private List<LeaveApprovalDto> readApprovalTrail(Long requestId) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        List<LeaveApproval> approvals = approvalRepository.findByLeaveRequestIdOrderByApprovalLevelAsc(requestId);

        requireMayReadTrail(request, approvals);

        return approvals.stream()
                .map(leaveRequestMapper::toApprovalDto)
                .collect(Collectors.toList());
    }

    /**
     * Refuses the caller unless they take part in this request or hold a leave-administrator role.
     *
     * <p>The same rule as the approval trail, exposed because the request itself is on the same
     * footing: an approver cannot decide on a request they cannot open, and the request detail
     * screen is where the approve, reject and delegate buttons are. Reading it here rather than in
     * a {@code @PreAuthorize} is the point, because the guard sees only the request id the caller
     * sent and nothing about whose leave it is.
     *
     * @param request The stored request, already resolved in the current tenant.
     * @throws AccessDeniedException if the caller is neither the employee the leave belongs to, nor
     *     named anywhere in its approval chain, nor a system-admin or hr-admin.
     */
    @Transactional(readOnly = true)
    public void requireMayReadRequest(LeaveRequest request) {
        requireMayReadTrail(
                request,
                approvalRepository.findByLeaveRequestIdOrderByApprovalLevelAsc(request.getId()));
    }

    private void requireMayReadTrail(LeaveRequest request, List<LeaveApproval> approvals) {
        if (leaveAccess.acrossOrganization(AccessPermission.READ)) {
            return;
        }

        Long callerId = currentEmployeeService.currentEmployee().map(Employee::getId).orElse(null);
        if (callerId == null) {
            throw new AccessDeniedException(
                    "You have no employee record in this organization, so you take no part in this "
                            + "leave request.");
        }

        if (request.getEmployee() != null && callerId.equals(request.getEmployee().getId())) {
            return;
        }

        boolean namedInTheChain = approvals.stream().anyMatch(approval ->
                (approval.getApprover() != null && callerId.equals(approval.getApprover().getId()))
                        || callerId.equals(approval.getDelegatedFromId()));
        if (namedInTheChain) {
            return;
        }

        throw new AccessDeniedException(
                "A leave request and its approval trail are readable by the employee it belongs to, "
                        + "the approvers in its chain, and whoever may read every employee's leave.");
    }

    /**
     * Whether the caller may act on this request right now.
     *
     * <p>This is the question a phone asks before it draws an approve button, so it answers for
     * whoever is signed in. It used to take the employee to ask about as a query parameter, which
     * is a different question than any caller has ever wanted answered and let one employee probe
     * another's place in a chain. A caller that still sends {@code employeeId} is served the
     * answer for themselves, because Spring drops a query parameter no handler declares.
     *
     * @param requestId The ID of the leave request.
     * @return {@code true} if the caller is the current approver of a request that is pending
     *     approval; {@code false} otherwise, including when the caller has no employee record here.
     */
    @Transactional(readOnly = true)
    public boolean canApprove(Long requestId) {
        Long callerId = currentEmployeeService.currentEmployee().map(Employee::getId).orElse(null);
        if (callerId == null) {
            return false;
        }

        return requestRepository.findByIdAndOrganization_Id(requestId, TenantContext.getCurrentOrgId())
                .map(request -> request.getCurrentApprover() != null &&
                               request.getCurrentApprover().getId().equals(callerId) &&
                               request.getStatus() == LeaveStatus.PENDING_APPROVAL)
                .orElse(false);
    }

    /**
     * The employee acting on this request, taken from the session.
     *
     * <p>It used to be an {@code approverId} on the payload, compared against the request's
     * current approver. That comparison says the id sent names the right person, never that the
     * caller is that person: the endpoints are gated on the system-admin and hr-admin roles, so
     * any holder of either could send the current approver's id and have the decision recorded
     * under that approver's name. An approval is the accountability on the days it moves, and it
     * has to name whoever actually gave it.
     *
     * <p>The consequence is deliberate: an administrator who is not the current approver can no
     * longer act on a request at all, where before they could act as the approver. Unsticking a
     * request whose approver is unavailable is a real need, but the answer to it is a reassignment
     * that records the administrator, not one that writes their decision in somebody else's name.
     *
     * @return The caller's employee id in the current tenant.
     * @throws org.springframework.security.access.AccessDeniedException if the caller has no
     *     employee record here, so the decision would name nobody.
     */
    private Long resolveActingApprover() {
        return currentEmployeeService.requireCurrentEmployee("act on a leave request").getId();
    }

    private void validateApprover(LeaveRequest request, Long approverId) {
        if (request.getStatus() != LeaveStatus.PENDING_APPROVAL) {
            throw new InvalidRequestException(
                    "Leave request with ID " + request.getId() + " is not pending approval (current status: " + request.getStatus() + ")");
        }

        if (request.getCurrentApprover() == null ||
            !request.getCurrentApprover().getId().equals(approverId)) {
            throw new InvalidRequestException(
                    "You are not the current approver for leave request " + request.getId());
        }
    }

    /**
     * Approves the request and moves its days from pending to used.
     *
     * @param request The request, locked by the caller when a decision is being recorded.
     * @param holdTaken Whether a pending hold of this request's own figure is on the balance.
     *     True on every approval of a submitted request; false when the chain is empty and the
     *     request is finalized during submission, before the submitting service takes the hold.
     */
    private void finalizeApproval(LeaveRequest request, boolean holdTaken) {
        // The policy's weekend and holiday treatment is applied to a pending request at the
        // moment it is approved, so a treatment changed while the request waited decides the
        // days actually deducted. The hold taken at submission is released at its own figure.
        double heldDays = holdTaken && request.getTotalDays() != null ? request.getTotalDays() : 0.0;
        settleCharge(request, heldDays);

        request.setStatus(LeaveStatus.APPROVED);
        request.setCurrentApprover(null);
        requestRepository.save(request);

        transferPendingToUsed(request, heldDays);

        createDeductionTransaction(request);

        calendarService.createCalendarEntries(request);

        notificationService.sendLeaveDecisionNotification(request, ApprovalAction.APPROVED);
    }

    /**
     * Recharges the request under the policy's current treatment and refuses the approval when
     * the recharged figure can no longer be honoured.
     *
     * <p>Two refusals, both answered as a 400 the approver can read. A request whose days have
     * all become non-working since submission (a holiday declared across it, say) would be
     * approved at zero days, which is not a leave; it has to be withdrawn. A request the
     * treatment now charges more for than it held cannot draw the difference from a balance
     * that does not have it: the balance is locked and the extra is checked against what is
     * bookable once this request's own hold is released, since the hold and the charge are the
     * same days.
     */
    private void settleCharge(LeaveRequest request, double heldDays) {
        if (request.getLeavePolicy() == null || request.getStartDate() == null || request.getEndDate() == null) {
            return;
        }
        LeaveCharge charge = leaveRequestValidator.charge(
                request.getLeavePolicy(),
                request.getStartDate(),
                request.getStartHalfDayType(),
                request.getEndDate(),
                request.getEndHalfDayType());

        if (charge.chargedDays() <= 0.0) {
            throw new InvalidRequestException(
                    "Leave request " + request.getRequestNumber() + " now falls entirely on non-working days "
                            + "under the policy's current treatment, so no leave would be charged; it has to "
                            + "be withdrawn rather than approved");
        }

        double extra = LeaveDays.round(charge.chargedDays() - heldDays);
        if (extra > 0.0) {
            balanceRepository.lockByEmployeeIdAndLeavePolicyIdAndYear(
                            request.getEmployee().getId(),
                            request.getLeavePolicy().getId(),
                            request.getStartDate().getYear())
                    .ifPresent(balance -> {
                        double bookableAfterRelease = LeaveDays.round(balance.getBookableBalance() + heldDays);
                        if (charge.chargedDays() > bookableAfterRelease) {
                            throw new InvalidRequestException(
                                    "Leave request " + request.getRequestNumber() + " is now charged "
                                            + charge.chargedDays() + " days under the policy's current treatment, "
                                            + "but only " + bookableAfterRelease + " days are available");
                        }
                    });
        }

        request.setTotalDays(charge.chargedDays());
        request.setDeductionRule(charge.rule());
    }

    private void transferPendingToUsed(LeaveRequest request, double heldDays) {
        int year = request.getStartDate().getYear();

        balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(
                        request.getEmployee().getId(),
                        request.getLeavePolicy().getId(),
                        year)
                .ifPresent(balance -> {
                    balance.setPending(LeaveDays.round(Math.max(0, balance.getPending() - heldDays)));
                    balance.setUsed(LeaveDays.round(balance.getUsed() + request.getTotalDays()));
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
