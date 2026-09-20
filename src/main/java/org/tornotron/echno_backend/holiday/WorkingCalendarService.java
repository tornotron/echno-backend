package org.tornotron.echno_backend.holiday;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.tornotron.echno_backend.common.multitenancy.TenantContext;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Answers, for a date range in the current organization, which days are not working days:
 * the days outside the working week plus the declared holidays.
 *
 * <p>This is the one question the leave deduction rule asks of the calendar, so it is the one
 * method here. The answer is a set of dates rather than a predicate so a caller charging a
 * multi-week request reads the calendar once.
 */
@Service
public class WorkingCalendarService {

    private final HolidayRepository holidayRepository;
    private final HolidayService holidayService;

    public WorkingCalendarService(HolidayRepository holidayRepository, HolidayService holidayService) {
        this.holidayRepository = holidayRepository;
        this.holidayService = holidayService;
    }

    /**
     * The non-working days in a closed date range for the current organization.
     *
     * @param from The first day, inclusive.
     * @param to The last day, inclusive.
     * @return Every day in the range that falls outside the working week or on a declared
     *         holiday, in date order.
     */
    @Transactional
    public Set<LocalDate> nonWorkingDays(LocalDate from, LocalDate to) {
        Set<DayOfWeek> workingWeek = holidayService.workingDays();
        Set<LocalDate> days = from.datesUntil(to.plusDays(1))
                .filter(date -> !workingWeek.contains(date.getDayOfWeek()))
                .collect(Collectors.toCollection(TreeSet::new));
        holidayRepository.findByOrganization_IdAndHolidayDateBetweenOrderByHolidayDateAsc(
                        TenantContext.getCurrentOrgId(), from, to)
                .forEach(holiday -> days.add(holiday.getHolidayDate()));
        return days;
    }
}
