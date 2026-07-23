package org.tornotron.echno_backend.leave;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.tornotron.echno_backend.DtoConversions.LeaveRequestDtoConvertor;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    private final LeaveBalanceService balanceService;

    public LeaveRequestService(
            LeaveRequestRepository requestRepository,
            LeaveRequestSequenceRepository sequenceRepository,
            LeavePolicyRepository policyRepository,
            LeaveBalanceRepository balanceRepository,
            EmployeeRepository employeeRepository,
            LeaveApprovalService approvalService,
            LeaveBalanceService balanceService) {
        this.requestRepository = requestRepository;
        this.sequenceRepository = sequenceRepository;
        this.policyRepository = policyRepository;
        this.balanceRepository = balanceRepository;
        this.employeeRepository = employeeRepository;
        this.approvalService = approvalService;
        this.balanceService = balanceService;
    }

    @Transactional
    public LeaveRequestDto createRequest(LeaveRequestCreationDto dto,Long employeeId) {
        Employee employee = employeeRepository.findByIdAndOrganizationId(employeeId, TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Employee with ID " + employeeId + " was not found in this organization"));

        LeavePolicy policy = policyRepository.findByIdAndOrganization_Id(dto.getLeavePolicyId(),TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave policy with ID " + dto.getLeavePolicyId() + " was not found in this organization"));

        validateRequest(employee, policy, dto, null);

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

        return LeaveRequestDtoConvertor.convertToDto(saved);
    }

    @Transactional(readOnly = true)
    public LeaveRequestDto getRequest(Long requestId) {
        LeaveRequest request = requestRepository.findByIdAndOrganization_Id(requestId,TenantContext.getCurrentOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Leave request with ID " + requestId + " was not found in this organization"));
        return LeaveRequestDtoConvertor.convertToDtoWithHandover(request, employeeRepository);
    }

    @Transactional(readOnly = true)
    public Page<LeaveRequestDto> getRequestsByEmployee(Long employeeId, Pageable pageable) {
        return requestRepository.findByEmployeeId(employeeId, pageable)
                .map(LeaveRequestDtoConvertor::convertToDto);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getRequestsByEmployeeAndStatus(Long employeeId, LeaveStatus status) {
        return requestRepository.findByEmployeeIdAndStatus(employeeId, status)
                .stream()
                .map(LeaveRequestDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getRequestsByOrganization() {
        return requestRepository.findByOrganizationId(TenantContext.getCurrentOrgId())
                .stream()
                .map(LeaveRequestDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getPendingApprovals(Long approverId) {
        return requestRepository.findByCurrentApproverIdAndStatus(approverId, LeaveStatus.PENDING_APPROVAL)
                .stream()
                .map(LeaveRequestDtoConvertor::convertToDto)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestDto> getRequestsByApprover(Long approverId) {
        return requestRepository.findDistinctByApproverParticipation(approverId)
                .stream()
                .map(LeaveRequestDtoConvertor::convertToDto)
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
        return LeaveRequestDtoConvertor.convertToDto(saved);
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

        validateRequest(
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

        return LeaveRequestDtoConvertor.convertToDto(saved);
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

        return LeaveRequestDtoConvertor.convertToDto(saved);
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
        return LeaveRequestDtoConvertor.convertToDto(saved);
    }

    @Transactional(readOnly = true)
    public List<LocalDate> getConflictingDates(Long employeeId, LocalDate startDate, LocalDate endDate) {
        return getConflictingDates(employeeId, startDate, endDate, null);
    }

    @Transactional(readOnly = true)
    public List<LocalDate> getConflictingDates(Long employeeId, LocalDate startDate, LocalDate endDate, Long excludeRequestId) {
        List<LeaveRequest> overlapping = requestRepository.findOverlappingRequests(
                employeeId, startDate, endDate, excludeRequestId);

        return overlapping.stream()
                .flatMap(req -> req.getStartDate().datesUntil(req.getEndDate().plusDays(1)))
                .filter(date -> !date.isBefore(startDate) && !date.isAfter(endDate))
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public double calculateTotalDays(
            LocalDate startDate,
            HalfDayType startType,
            LocalDate endDate,
            HalfDayType endType) {

        if (startDate.isAfter(endDate)) {
            throw new InvalidRequestException(
                    "Start date " + startDate + " cannot be after end date " + endDate);
        }

        if (startDate.equals(endDate)) {
            if (startType == HalfDayType.FIRST_HALF || startType == HalfDayType.SECOND_HALF ||
                endType == HalfDayType.FIRST_HALF || endType == HalfDayType.SECOND_HALF) {
                return 0.5;
            }
            return 1.0;
        }

        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        double total = daysBetween;

        if (startType == HalfDayType.SECOND_HALF) {
            total -= 0.5;
        }
        if (endType == HalfDayType.FIRST_HALF) {
            total -= 0.5;
        }

        return total;
    }

    private void validateRequest(Employee employee, LeavePolicy policy, LeaveRequestCreationDto dto, Long excludeRequestId) {
        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new InvalidRequestException(
                    "Start date " + dto.getStartDate() + " cannot be after end date " + dto.getEndDate());
        }

        if (dto.getStartDate().isBefore(LocalDate.now())) {
            throw new InvalidRequestException(
                    "Cannot apply for leave starting " + dto.getStartDate() + "; leave cannot be requested in the past");
        }

        if (policy.getAdvanceNoticeDays() != null && policy.getAdvanceNoticeDays() > 0) {
            long daysUntilStart = ChronoUnit.DAYS.between(LocalDate.now(), dto.getStartDate());
            if (daysUntilStart < policy.getAdvanceNoticeDays()) {
                throw new InvalidRequestException(
                        "Leave policy '" + policy.getLeaveTypeName() + "' requires at least " +
                        policy.getAdvanceNoticeDays() + " days advance notice, but only " +
                        daysUntilStart + " days remain before " + dto.getStartDate());
            }
        }

        double totalDays = calculateTotalDays(
                dto.getStartDate(),
                dto.getStartHalfDayType(),
                dto.getEndDate(),
                dto.getEndHalfDayType());

        if (policy.getMinDaysPerRequest() != null && totalDays < policy.getMinDaysPerRequest()) {
            throw new InvalidRequestException(
                    "Leave policy '" + policy.getLeaveTypeName() + "' requires a minimum of " +
                    policy.getMinDaysPerRequest() + " days per request, but " + totalDays + " days were requested");
        }

        if (policy.getMaxDaysPerRequest() != null && totalDays > policy.getMaxDaysPerRequest()) {
            throw new InvalidRequestException(
                    "Leave policy '" + policy.getLeaveTypeName() + "' allows a maximum of " +
                    policy.getMaxDaysPerRequest() + " days per request, but " + totalDays + " days were requested");
        }

        if ((dto.getStartHalfDayType() == HalfDayType.FIRST_HALF ||
             dto.getStartHalfDayType() == HalfDayType.SECOND_HALF ||
             dto.getEndHalfDayType() == HalfDayType.FIRST_HALF ||
             dto.getEndHalfDayType() == HalfDayType.SECOND_HALF) &&
            !Boolean.TRUE.equals(policy.getAllowHalfDay())) {
            throw new InvalidRequestException(
                    "Leave policy '" + policy.getLeaveTypeName() + "' does not allow half-day leave");
        }

        int year = dto.getStartDate().getYear();
        var balanceDto = balanceService.getOrCalculateBalance(
                employee.getId(), policy.getId(), year);

        if (balanceDto.getBookable() < totalDays) {
            throw new InvalidRequestException(
                    "Employee with ID " + employee.getId() + " has insufficient leave balance for policy '" +
                    policy.getLeaveTypeName() + "': " + balanceDto.getBookable() +
                    " days available, " + totalDays + " days requested");
        }

        List<LocalDate> conflicts = getConflictingDates(
                employee.getId(), dto.getStartDate(), dto.getEndDate(), excludeRequestId);
        if (!conflicts.isEmpty()) {
            throw new InvalidRequestException(
                    "Employee with ID " + employee.getId() + " already has leave requests overlapping these dates: " + conflicts);
        }
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
