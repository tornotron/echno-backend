package org.tornotron.echno_backend.leave;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
import org.tornotron.echno_backend.common.service.OrganizationSecurityService;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.employee.EmployeeRepository;
import org.tornotron.echno_backend.leave.dto.LeaveRequestCreationDto;
import org.tornotron.echno_backend.leave.dto.LeaveRequestDto;
import org.tornotron.echno_backend.leave.enums.HalfDayType;
import org.tornotron.echno_backend.leave.enums.LeaveStatus;
import org.tornotron.echno_backend.organization.Organization;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Manages the leave request lifecycle from draft through submission, cancellation, and withdrawal.
 *
 * <p>On create or submit it validates against policy, computes total days, assigns a per-year
 * sequential request number under a row lock, and holds the days as pending while the approval
 * chain runs. Cancellation and withdrawal release the appropriate hold: pending days for an
 * in-flight request, used days for one already approved. Editing is allowed only while a request
 * is still a draft.
 */
@Service
@Validated
public class LeaveRequestService {

    private final LeaveRequestRepository requestRepository;
    private final LeaveRequestSequenceRepository sequenceRepository;
    private final LeavePolicyRepository policyRepository;
    private final LeaveBalanceRepository balanceRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveApprovalService approvalService;
    private final LeaveRequestValidator leaveRequestValidator;
    private final LeaveRequestMapper leaveRequestMapper;
    private final OrganizationSecurityService orgSecurity;

    /** The roles that may raise or act on a leave request belonging to somebody else. */
    private static final String[] LEAVE_ADMIN_ROLES = {"system-admin", "hr-admin"};

    public LeaveRequestService(
            LeaveRequestRepository requestRepository,
            LeaveRequestSequenceRepository sequenceRepository,
            LeavePolicyRepository policyRepository,
            LeaveBalanceRepository balanceRepository,
            EmployeeRepository employeeRepository,
            LeaveApprovalService approvalService,
            LeaveRequestValidator leaveRequestValidator,
            LeaveRequestMapper leaveRequestMapper,
            OrganizationSecurityService orgSecurity) {
        this.requestRepository = requestRepository;
        this.sequenceRepository = sequenceRepository;
        this.policyRepository = policyRepository;
        this.balanceRepository = balanceRepository;
        this.employeeRepository = employeeRepository;
        this.approvalService = approvalService;
        this.leaveRequestValidator = leaveRequestValidator;
        this.leaveRequestMapper = leaveRequestMapper;
        this.orgSecurity = orgSecurity;
    }

    /**
     * Refuses the call unless the caller is the employee the leave belongs to, or holds a role
     * that may act for other people.
     *
     * <p>This has to live here rather than in the {@code @PreAuthorize} guard because the guard
     * can only see what the caller sent. Every one of these endpoints names the employee in a
     * query parameter or a path segment, so a guard reading that parameter checks the caller
     * against a number the caller chose. On create that let any member of the tenant raise leave
     * in a colleague's name; on the request-scoped calls it let any member pass their own
     * employee id alongside somebody else's request id and edit, cancel or withdraw it. The
     * employee id is an argument, not evidence.
     *
     * @param employeeId The employee the leave belongs to, resolved from the record where there
     *     is one rather than taken from the request.
     * @throws AccessDeniedException if the caller is neither that employee nor a leave admin.
     */
    private void requireActorMayActFor(Long employeeId) {
        if (orgSecurity.isSelfInCurrentTenant(employeeId)
                || orgSecurity.hasAnyOrgRoleForCurrentTenant(LEAVE_ADMIN_ROLES)) {
            return;
        }
        throw new AccessDeniedException(
                "Leave can only be raised or acted on for yourself, unless you hold the "
                        + "system-admin or hr-admin role");
    }

    /**
     * Creates a leave request as a draft, or submits it immediately for approval.
     *
     * <p>Validates the request against the policy, computes total days, and assigns a request
     * number. When submitted immediately the approval chain is started and the days are held as
     * pending, unless the chain resolved to no approvers and auto-approved the request.
     *
     * @param dto The request details, including whether to submit immediately.
     * @param employeeId The ID of the employee the request is for.
     * @return The created request.
     * @throws ResourceNotFoundException if the employee or policy is not found in this organization.
     * @throws AccessDeniedException if the caller is neither the employee the leave belongs to
     *     nor a holder of the system-admin or hr-admin role.
     */
    @Transactional
    public LeaveRequestDto createRequest(LeaveRequestCreationDto dto,Long employeeId) {
        requireActorMayActFor(employeeId);

        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(dto.getLeavePolicyId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + dto.getLeavePolicyId() + " was not found in this organization"));

        leaveRequestValidator.validate(employee, policy, dto, null);

        double totalDays = calculateTotalDays(
                dto.getStartDate(),
                dto.getStartHalfDayType(),
                dto.getEndDate(),
                dto.getEndHalfDayType());

        Organization organization = employee.getOrganization();
        String requestNumber = generateRequestNumber(organization);

        LeaveRequest request = new LeaveRequest();
        request.setRequestNumber(requestNumber);
        request.setEmployee(employee);
        request.setOrganization(organization);
        request.setLeavePolicy(policy);
        request.setStartDate(dto.getStartDate());
        request.setStartHalfDayType(dto.getStartHalfDayType());
        request.setEndDate(dto.getEndDate());
        request.setEndHalfDayType(dto.getEndHalfDayType());
        request.setTotalDays(totalDays);
        request.setReason(dto.getReason());
        request.setContactDuringLeave(dto.getContactDuringLeave());
        request.setHandoverToId(dto.getHandoverToId());
        request.setHandoverNotes(dto.getHandoverNotes());

        if (Boolean.TRUE.equals(dto.getSubmitImmediately())) {
            request.setStatus(LeaveStatus.PENDING_APPROVAL);
        } else {
            request.setStatus(LeaveStatus.DRAFT);
        }

        LeaveRequest saved = requestRepository.save(request);

        if (saved.getStatus() == LeaveStatus.PENDING_APPROVAL) {
            approvalService.initializeApprovalChain(saved);
            if (saved.getStatus() == LeaveStatus.PENDING_APPROVAL) {
                updatePendingBalance(saved, true);
            }
        }

        return leaveRequestMapper.toDto(saved);
    }

    /**
     * Retrieves a single leave request, resolving the handover employee's name when set.
     *
     * @param requestId The ID of the leave request.
     * @return The request.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     */
    @Transactional(readOnly = true)
    public LeaveRequestDto getRequest(Long requestId) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));
        LeaveRequestDto dto = leaveRequestMapper.toDto(request);
        if (request.getHandoverToId() != null) {
            employeeRepository.findById(request.getHandoverToId())
                    .ifPresent(emp -> dto.setHandoverToName(emp.getEmployeeName()));
        }
        return dto;
    }

    /**
     * Lists an employee's leave requests, paginated.
     *
     * @param employeeId The employee's ID.
     * @param pageable The pagination and sort parameters.
     * @return A page of the employee's requests.
     */
    @Transactional(readOnly = true)
    public Page<LeaveRequestDto> getRequestsByEmployee(Long employeeId, Pageable pageable) {
        return requestRepository.findByEmployeeId(employeeId, pageable)
                .map(leaveRequestMapper::toDto);
    }

    /**
     * Lists an employee's leave requests filtered by status.
     *
     * @param employeeId The employee's ID.
     * @param status The status to filter by.
     * @return The matching requests.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getRequestsByEmployeeAndStatus(Long employeeId, LeaveStatus status) {
        return requestRepository.findByEmployeeIdAndStatus(employeeId, status)
                .stream()
                .map(leaveRequestMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists every leave request in the current organization.
     *
     * @return All requests for the current tenant.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getRequestsByOrganization() {
        return requestRepository.findByOrganizationId(TenantContext.getCurrentOrgId())
                .stream()
                .map(leaveRequestMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists requests currently awaiting a decision from a given approver.
     *
     * @param approverId The approver's employee ID.
     * @return The requests where this approver is the current, pending approver.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getPendingApprovals(Long approverId) {
        return requestRepository.findByCurrentApproverIdAndStatus(approverId, LeaveStatus.PENDING_APPROVAL)
                .stream()
                .map(leaveRequestMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Lists every request an approver has taken part in at any level, past or present.
     *
     * @param approverId The approver's employee ID.
     * @return The distinct requests this approver participated in.
     */
    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getRequestsByApprover(Long approverId) {
        return requestRepository.findDistinctByApproverParticipation(approverId)
                .stream()
                .map(leaveRequestMapper::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Counts the requests currently awaiting a decision from a given approver.
     *
     * @param approverId The approver's employee ID.
     * @return The number of requests pending this approver.
     */
    @Transactional(readOnly = true)
    public long getPendingApprovalCount(Long approverId) {
        return requestRepository.countByCurrentApproverIdAndStatus(
                approverId, LeaveStatus.PENDING_APPROVAL);
    }

    /**
     * Applies a partial update to a draft request and recomputes its total days.
     *
     * <p>Only the supplied keys are changed; unrecognized keys are ignored. Editing is rejected
     * once the request has left draft status.
     *
     * @param requestId The ID of the request to update.
     * @param updates A map of field names to new values.
     * @return The updated request.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     * @throws InvalidRequestException if the request is not in draft status.
     * @throws AccessDeniedException if the caller is neither the employee the leave belongs to
     *     nor a holder of the system-admin or hr-admin role.
     */
    @Transactional
    public LeaveRequestDto updateRequest(Long requestId, Map<String, Object> updates) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        requireActorMayActFor(request.getEmployee().getId());

        if (request.getStatus() != LeaveStatus.DRAFT) {
            throw new InvalidRequestException(
                    "Leave request " + requestId + " cannot be updated (current status: " + request.getStatus() + "); only draft requests can be updated");
        }

        updates.forEach((key, value) -> {
            switch (key) {
                case "startDate" -> request.setStartDate(LocalDate.parse((String) value));
                case "startHalfDayType" -> request.setStartHalfDayType(
                        value != null ? HalfDayType.valueOf((String) value) : null);
                case "endDate" -> request.setEndDate(LocalDate.parse((String) value));
                case "endHalfDayType" -> request.setEndHalfDayType(
                        value != null ? HalfDayType.valueOf((String) value) : null);
                case "reason" -> request.setReason((String) value);
                case "contactDuringLeave" -> request.setContactDuringLeave((String) value);
                case "handoverToId" -> request.setHandoverToId(
                        value != null ? ((Number) value).longValue() : null);
                case "handoverNotes" -> request.setHandoverNotes((String) value);
            }
        });

        double totalDays = calculateTotalDays(
                request.getStartDate(),
                request.getStartHalfDayType(),
                request.getEndDate(),
                request.getEndHalfDayType());
        request.setTotalDays(totalDays);

        LeaveRequest saved = requestRepository.save(request);
        return leaveRequestMapper.toDto(saved);
    }

    /**
     * Submits a draft request for approval, validating it and holding its days as pending.
     *
     * <p>Re-validates against the policy, starts the approval chain, and marks the days pending
     * unless the chain auto-approved the request because it resolved to no approvers.
     *
     * @param requestId The ID of the request to submit.
     * @return The submitted request.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     * @throws InvalidRequestException if the request is not in draft status.
     * @throws AccessDeniedException if the caller is neither the employee the leave belongs to
     *     nor a holder of the system-admin or hr-admin role.
     */
    @Transactional
    public LeaveRequestDto submitRequest(Long requestId) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        requireActorMayActFor(request.getEmployee().getId());

        if (request.getStatus() != LeaveStatus.DRAFT) {
            throw new InvalidRequestException(
                    "Leave request " + requestId + " cannot be submitted (current status: " + request.getStatus() + "); only draft requests can be submitted");
        }

        leaveRequestValidator.validate(
                request.getEmployee(),
                request.getLeavePolicy(),
                createDtoFromRequest(request),
                request.getId());

        request.setStatus(LeaveStatus.PENDING_APPROVAL);
        LeaveRequest saved = requestRepository.save(request);

        approvalService.initializeApprovalChain(saved);
        if (saved.getStatus() == LeaveStatus.PENDING_APPROVAL) {
            updatePendingBalance(saved, true);
        }

        return leaveRequestMapper.toDto(saved);
    }

    /**
     * Cancels a request and restores whichever balance hold it was carrying.
     *
     * <p>A request that was pending approval has its pending days released; one that was already
     * approved has its used days restored. Requests already cancelled or rejected cannot be cancelled.
     *
     * @param requestId The ID of the request to cancel.
     * @param reason The cancellation reason to record.
     * @return The cancelled request.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     * @throws InvalidRequestException if the request is already cancelled or rejected.
     * @throws AccessDeniedException if the caller is neither the employee the leave belongs to
     *     nor a holder of the system-admin or hr-admin role.
     */
    @Transactional
    public LeaveRequestDto cancelRequest(Long requestId, String reason) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        requireActorMayActFor(request.getEmployee().getId());

        if (request.getStatus() == LeaveStatus.CANCELLED ||
            request.getStatus() == LeaveStatus.REJECTED) {
            throw new InvalidRequestException(
                    "Leave request " + requestId + " is already " + request.getStatus().toString().toLowerCase() + " and cannot be cancelled again");
        }

        LeaveStatus previousStatus = request.getStatus();
        request.setStatus(LeaveStatus.CANCELLED);
        request.setCancelledAt(java.time.LocalDateTime.now());
        request.setCancellationReason(reason);
        request.setCurrentApprover(null);

        LeaveRequest saved = requestRepository.save(request);

        if (previousStatus == LeaveStatus.PENDING_APPROVAL) {
            updatePendingBalance(saved, false);
        } else if (previousStatus == LeaveStatus.APPROVED) {
            restoreUsedBalance(saved);
        }

        return leaveRequestMapper.toDto(saved);
    }

    /**
     * Withdraws a draft or pending request, releasing any pending balance hold.
     *
     * <p>Applies only before a decision is reached; a pending request's held days are released
     * before it moves to withdrawn.
     *
     * @param requestId The ID of the request to withdraw.
     * @return The withdrawn request.
     * @throws ResourceNotFoundException if no request with the given ID exists in this organization.
     * @throws InvalidRequestException if the request is neither draft nor pending approval.
     * @throws AccessDeniedException if the caller is neither the employee the leave belongs to
     *     nor a holder of the system-admin or hr-admin role.
     */
    @Transactional
    public LeaveRequestDto withdrawRequest(Long requestId) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

        requireActorMayActFor(request.getEmployee().getId());

        if (request.getStatus() != LeaveStatus.DRAFT &&
            request.getStatus() != LeaveStatus.PENDING_APPROVAL) {
            throw new InvalidRequestException(
                    "Leave request " + requestId + " cannot be withdrawn (current status: " + request.getStatus() + "); only draft or pending requests can be withdrawn");
        }

        if (request.getStatus() == LeaveStatus.PENDING_APPROVAL) {
            updatePendingBalance(request, false);
        }

        request.setStatus(LeaveStatus.WITHDRAWN);
        request.setCurrentApprover(null);

        LeaveRequest saved = requestRepository.save(request);
        return leaveRequestMapper.toDto(saved);
    }

    /** Overlapping leave dates for an employee. Delegates to {@link LeaveRequestValidator}. */
    public List<LocalDate> getConflictingDates(Long employeeId, LocalDate startDate, LocalDate endDate) {
        return leaveRequestValidator.getConflictingDates(employeeId, startDate, endDate);
    }

    /** Overlapping leave dates, excluding one request. Delegates to {@link LeaveRequestValidator}. */
    public List<LocalDate> getConflictingDates(Long employeeId, LocalDate startDate, LocalDate endDate, Long excludeRequestId) {
        return leaveRequestValidator.getConflictingDates(employeeId, startDate, endDate, excludeRequestId);
    }

    /** Number of leave days in the range. Delegates to {@link LeaveRequestValidator}. */
    public double calculateTotalDays(
            LocalDate startDate,
            HalfDayType startType,
            LocalDate endDate,
            HalfDayType endType) {
        return leaveRequestValidator.calculateTotalDays(startDate, startType, endDate, endType);
    }

    private String generateRequestNumber(Organization organization) {
        int year = LocalDate.now().getYear();

        LeaveRequestSequence sequence = sequenceRepository
                .findByOrganizationIdAndYearWithLock(organization.getId(), year)
                .orElseGet(() -> {
                    LeaveRequestSequence newSeq = new LeaveRequestSequence();
                    newSeq.setOrganization(organization);
                    newSeq.setYear(year);
                    newSeq.setLastSequence(0L);
                    return newSeq;
                });

        sequence.setLastSequence(sequence.getLastSequence() + 1);
        sequenceRepository.save(sequence);

        return String.format("LR-%d-%06d", year, sequence.getLastSequence());
    }

    private void updatePendingBalance(LeaveRequest request, boolean add) {
        int year = request.getStartDate().getYear();

        balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(
                        request.getEmployee().getId(),
                        request.getLeavePolicy().getId(),
                        year)
                .ifPresent(balance -> {
                    if (add) {
                        balance.setPending(balance.getPending() + request.getTotalDays());
                    } else {
                        balance.setPending(Math.max(0, balance.getPending() - request.getTotalDays()));
                    }
                    balanceRepository.save(balance);
                });
    }

    private void restoreUsedBalance(LeaveRequest request) {
        int year = request.getStartDate().getYear();

        balanceRepository.findByEmployeeIdAndLeavePolicyIdAndYear(
                        request.getEmployee().getId(),
                        request.getLeavePolicy().getId(),
                        year)
                .ifPresent(balance -> {
                    balance.setUsed(Math.max(0, balance.getUsed() - request.getTotalDays()));
                    balanceRepository.save(balance);
                });
    }

    private LeaveRequestCreationDto createDtoFromRequest(LeaveRequest request) {
        LeaveRequestCreationDto dto = new LeaveRequestCreationDto();
        dto.setLeavePolicyId(request.getLeavePolicy().getId());
        dto.setStartDate(request.getStartDate());
        dto.setStartHalfDayType(request.getStartHalfDayType());
        dto.setEndDate(request.getEndDate());
        dto.setEndHalfDayType(request.getEndHalfDayType());
        dto.setReason(request.getReason());
        return dto;
    }
}
