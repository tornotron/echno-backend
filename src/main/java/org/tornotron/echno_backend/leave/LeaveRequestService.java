package org.tornotron.echno_backend.leave;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.leave.mapper.LeaveRequestMapper;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.common.exception.ResourceNotFoundException;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;
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

    public LeaveRequestService(
            LeaveRequestRepository requestRepository,
            LeaveRequestSequenceRepository sequenceRepository,
            LeavePolicyRepository policyRepository,
            LeaveBalanceRepository balanceRepository,
            EmployeeRepository employeeRepository,
            LeaveApprovalService approvalService,
            LeaveRequestValidator leaveRequestValidator,
            LeaveRequestMapper leaveRequestMapper) {
        this.requestRepository = requestRepository;
        this.sequenceRepository = sequenceRepository;
        this.policyRepository = policyRepository;
        this.balanceRepository = balanceRepository;
        this.employeeRepository = employeeRepository;
        this.approvalService = approvalService;
        this.leaveRequestValidator = leaveRequestValidator;
        this.leaveRequestMapper = leaveRequestMapper;
    }

    @Transactional
    public LeaveRequestDto createRequest(LeaveRequestCreationDto dto,Long employeeId) {
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

    @Transactional(readOnly = true)
    public Page<LeaveRequestDto> getRequestsByEmployee(Long employeeId, Pageable pageable) {
        return requestRepository.findByEmployeeId(employeeId, pageable)
                .map(leaveRequestMapper::toDto);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getRequestsByEmployeeAndStatus(Long employeeId, LeaveStatus status) {
        return requestRepository.findByEmployeeIdAndStatus(employeeId, status)
                .stream()
                .map(leaveRequestMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getRequestsByOrganization() {
        return requestRepository.findByOrganizationId(TenantContext.getCurrentOrgId())
                .stream()
                .map(leaveRequestMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getPendingApprovals(Long approverId) {
        return requestRepository.findByCurrentApproverIdAndStatus(approverId, LeaveStatus.PENDING_APPROVAL)
                .stream()
                .map(leaveRequestMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getRequestsByApprover(Long approverId) {
        return requestRepository.findDistinctByApproverParticipation(approverId)
                .stream()
                .map(leaveRequestMapper::toDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public long getPendingApprovalCount(Long approverId) {
        return requestRepository.countByCurrentApproverIdAndStatus(
                approverId, LeaveStatus.PENDING_APPROVAL);
    }

    @Transactional
    public LeaveRequestDto updateRequest(Long requestId, Map<String, Object> updates) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

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

    @Transactional
    public LeaveRequestDto submitRequest(Long requestId) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

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

    @Transactional
    public LeaveRequestDto cancelRequest(Long requestId, String reason) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

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

    @Transactional
    public LeaveRequestDto withdrawRequest(Long requestId) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));

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
