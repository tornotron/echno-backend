package org.tornotron.echno_backend.leave;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.holiday.WorkingCalendarService;
import org.tornotron.echno_backend.leave.dto.LeaveRequestCreationDto;
import org.tornotron.echno_backend.leave.enums.HalfDayType;
import org.tornotron.echno_backend.leave.enums.WeekendHolidayTreatment;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private final WorkingCalendarService workingCalendarService;

    public LeaveRequestValidator(LeaveRequestRepository requestRepository,
                                 LeaveBalanceService balanceService,
                                 WorkingCalendarService workingCalendarService) {
        this.requestRepository = requestRepository;
        this.balanceService = balanceService;
        this.workingCalendarService = workingCalendarService;
    }

    /**
     * What a request under a policy costs, applying the policy's weekend and holiday treatment.
     *
     * <p>Every calendar day in the range carries a weight: one, or a half for a start day taken
     * from midday or an end day taken until midday. {@link WeekendHolidayTreatment#CHARGE_ALL_DAYS}
     * charges every weight, which is the count every request was charged before the treatment
     * existed. The other two consult the organization's working week and holiday calendar:
     * {@link WeekendHolidayTreatment#EXCLUDE_NON_WORKING_DAYS} charges only the working days, and
     * {@link WeekendHolidayTreatment#SANDWICH} also charges a non-working day that sits between
     * two charged working days, so a Friday-to-Monday request costs four days and a
     * Thursday-to-Friday one costs two.
     *
     * @param policy The policy the request is raised under; null applies CHARGE_ALL_DAYS.
     * @return The charge, never null.
     * @throws InvalidRequestException if the start date is after the end date.
     */
    @Transactional(readOnly = true)
    public LeaveCharge charge(
            LeavePolicy policy,
            LocalDate startDate,
            HalfDayType startType,
            LocalDate endDate,
            HalfDayType endType) {

        double calendarDays = calculateTotalDays(startDate, startType, endDate, endType);
        WeekendHolidayTreatment rule = policy == null || policy.getWeekendHolidayTreatment() == null
                ? WeekendHolidayTreatment.CHARGE_ALL_DAYS
                : policy.getWeekendHolidayTreatment();

        if (rule == WeekendHolidayTreatment.CHARGE_ALL_DAYS) {
            return new LeaveCharge(calendarDays, calendarDays, 0, rule);
        }

        Set<LocalDate> nonWorking = workingCalendarService.nonWorkingDays(startDate, endDate);
        List<LocalDate> days = startDate.datesUntil(endDate.plusDays(1)).toList();
        List<Double> weights = new ArrayList<>(days.size());
        for (LocalDate day : days) {
            weights.add(weightOf(day, startDate, startType, endDate, endType));
        }

        // Working days are charged at their weight under both remaining treatments. Under
        // SANDWICH a non-working day is charged in full when a charged working day lies on each
        // side of it within the request; a run that touches the request only at an end is free.
        int firstWorking = -1;
        int lastWorking = -1;
        for (int i = 0; i < days.size(); i++) {
            if (!nonWorking.contains(days.get(i))) {
                if (firstWorking < 0) {
                    firstWorking = i;
                }
                lastWorking = i;
            }
        }

        double charged = 0.0;
        int excluded = 0;
        for (int i = 0; i < days.size(); i++) {
            boolean isNonWorking = nonWorking.contains(days.get(i));
            if (!isNonWorking) {
                charged += weights.get(i);
            } else if (rule == WeekendHolidayTreatment.SANDWICH && i > firstWorking && i < lastWorking) {
                charged += 1.0;
            } else {
                excluded++;
            }
        }

        return new LeaveCharge(LeaveDays.round(charged), calendarDays, excluded, rule);
    }

    private static double weightOf(LocalDate day, LocalDate startDate, HalfDayType startType,
                                   LocalDate endDate, HalfDayType endType) {
        boolean startsAtMidday = day.equals(startDate) && startType == HalfDayType.SECOND_HALF;
        boolean endsAtMidday = day.equals(endDate) && endType == HalfDayType.FIRST_HALF;
        if (startDate.equals(endDate)) {
            boolean half = startType == HalfDayType.FIRST_HALF || startType == HalfDayType.SECOND_HALF
                    || endType == HalfDayType.FIRST_HALF || endType == HalfDayType.SECOND_HALF;
            return half ? 0.5 : 1.0;
        }
        return startsAtMidday || endsAtMidday ? 0.5 : 1.0;
    }

    /**
     * Computes the number of calendar days spanned by the range, honouring
     * first/second-half markers on the start and end days. This is the
     * {@link WeekendHolidayTreatment#CHARGE_ALL_DAYS} figure; {@link #charge} applies
     * the policy's treatment on top of it.
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

    /** How far back a leave may start, for a policy that asks for no advance notice. */
    static final int MAX_BACKDATED_DAYS = 30;

    /**
     * Whether a leave may start on the given day.
     *
     * <p>A leave used to be refused outright when it started before today. That blocked the case
     * the regularization calendar sends people here for: a day the employee did not work and did
     * not clock in on, which is only discovered afterwards and is exactly what a sick or casual
     * leave after the fact covers. A policy that asks for advance notice still cannot start in the
     * past, since notice given after the day is not notice. A policy that asks for none may start
     * up to {@value #MAX_BACKDATED_DAYS} days back, which covers a missed day found at month end
     * without reopening months that payroll has already closed.
     *
     * @param policy The leave policy.
     * @param start  The first day of the leave.
     * @param today  Today.
     * @throws InvalidRequestException if the start is further back than the policy allows.
     */
    static void requireStartDateAllowed(LeavePolicy policy, LocalDate start, LocalDate today) {
        if (!start.isBefore(today)) {
            return;
        }
        boolean needsNotice = policy.getAdvanceNoticeDays() != null && policy.getAdvanceNoticeDays() > 0;
        if (needsNotice) {
            throw new InvalidRequestException(
                    "Cannot apply for leave starting " + start + "; leave policy '"
                            + policy.getLeaveTypeName() + "' requires advance notice, so it cannot "
                            + "be requested for a past date");
        }
        if (start.isBefore(today.minusDays(MAX_BACKDATED_DAYS))) {
            throw new InvalidRequestException(
                    "Cannot apply for leave starting " + start + "; leave can be requested at most "
                            + MAX_BACKDATED_DAYS + " days after the day it covers");
        }
    }

    /**
     * Validates a leave request against the policy: date ordering, no past dates,
     * advance notice, min/max days, half-day permission, sufficient bookable
     * balance, and no overlap with existing requests.
     *
     * @throws InvalidRequestException if any rule is violated.
     */
    public void validate(Employee employee, LeavePolicy policy, LeaveRequestCreationDto dto, Long excludeRequestId) {
        LeavePolicyEligibility.require(employee, policy);

        if (dto.getStartDate().isAfter(dto.getEndDate())) {
            throw new InvalidRequestException(
                    "Start date " + dto.getStartDate() + " cannot be after end date " + dto.getEndDate());
        }

        requireStartDateAllowed(policy, dto.getStartDate(), LocalDate.now());

        if (policy.getAdvanceNoticeDays() != null && policy.getAdvanceNoticeDays() > 0) {
            long daysUntilStart = ChronoUnit.DAYS.between(LocalDate.now(), dto.getStartDate());
            if (daysUntilStart < policy.getAdvanceNoticeDays()) {
                throw new InvalidRequestException(
                        "Leave policy '" + policy.getLeaveTypeName() + "' requires at least " +
                        policy.getAdvanceNoticeDays() + " days advance notice, but only " +
                        daysUntilStart + " days remain before " + dto.getStartDate());
            }
        }

        double totalDays = charge(
                policy,
                dto.getStartDate(),
                dto.getStartHalfDayType(),
                dto.getEndDate(),
                dto.getEndHalfDayType()).chargedDays();

        if (totalDays <= 0.0) {
            throw new InvalidRequestException(
                    "The requested dates fall entirely on non-working days, so no leave would be charged");
        }

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
