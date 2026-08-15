package org.tornotron.echno_backend.leave;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.leave.dto.LeaveRequestCreationDto;
import org.tornotron.echno_backend.leave.enums.HalfDayType;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Validates a leave request against policy rules, computes the leave-day total,
 * and detects overlaps with existing requests. Extracted from
 * {@link LeaveRequestService}, which keeps the request lifecycle (create,
 * submit, cancel, withdraw, update) and delegates day-count and conflict queries
 * here. The approval workflow lives in {@link LeaveApprovalService}, the balance
 * side in {@link LeaveBalanceService}.
 */
@Service
public class LeaveRequestValidator {

    private final LeaveRequestRepository requestRepository;
    private final LeaveBalanceService balanceService;

    public LeaveRequestValidator(LeaveRequestRepository requestRepository, LeaveBalanceService balanceService) {
        this.requestRepository = requestRepository;
        this.balanceService = balanceService;
    }

    /**
     * Computes the number of leave days spanned by the range, honouring
     * first/second-half markers on the start and end days.
     */
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

    /**
     * Validates a leave request against the policy: date ordering, no past dates,
     * advance notice, min/max days, half-day permission, sufficient bookable
     * balance, and no overlap with existing requests.
     *
     * @throws InvalidRequestException if any rule is violated.
     */
    public void validate(Employee employee, LeavePolicy policy, LeaveRequestCreationDto dto, Long excludeRequestId) {
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
}
