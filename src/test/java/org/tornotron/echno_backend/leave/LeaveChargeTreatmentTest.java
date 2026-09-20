package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.tornotron.echno_backend.holiday.WorkingCalendarService;
import org.tornotron.echno_backend.leave.enums.HalfDayType;
import org.tornotron.echno_backend.leave.enums.WeekendHolidayTreatment;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The deduction rule, {@link LeaveRequestValidator#charge}, across the three treatments.
 *
 * <p>The week is Mon 24 Aug 2026 to Sun 30 Aug, then Mon 31 Aug. The calendar is stubbed to a
 * Monday-to-Friday working week plus whichever holiday the row declares, so the matrix reads as
 * the requirement was written: Fri to Mon, Thu to Fri, Fri only, Mon only, a holiday on the
 * Wednesday and on the Friday of a Mon to Fri request, and half days at either end, each under
 * SANDWICH, EXCLUDE_NON_WORKING_DAYS and CHARGE_ALL_DAYS.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaveChargeTreatmentTest {

    @Mock private LeaveRequestRepository requestRepository;
    @Mock private LeaveBalanceService balanceService;
    @Mock private WorkingCalendarService workingCalendarService;

    private LeaveRequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new LeaveRequestValidator(requestRepository, balanceService, workingCalendarService);
    }

    private void calendarWithHoliday(LocalDate holiday) {
        when(workingCalendarService.nonWorkingDays(any(), any())).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(0);
            LocalDate to = invocation.getArgument(1);
            Set<LocalDate> days = new TreeSet<>();
            from.datesUntil(to.plusDays(1))
                    .filter(d -> d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY)
                    .forEach(days::add);
            if (holiday != null && !holiday.isBefore(from) && !holiday.isAfter(to)) {
                days.add(holiday);
            }
            return days;
        });
    }

    private static LeavePolicy policy(WeekendHolidayTreatment treatment) {
        LeavePolicy policy = new LeavePolicy();
        policy.setWeekendHolidayTreatment(treatment);
        return policy;
    }

    private static HalfDayType half(String name) {
        return name == null || name.isBlank() || "FULL".equals(name) ? HalfDayType.FULL_DAY : HalfDayType.valueOf(name);
    }

    /**
     * Columns: case, start, startHalf, end, endHalf, holiday (or blank), calendar days, then the
     * charged days under SANDWICH, EXCLUDE_NON_WORKING_DAYS and CHARGE_ALL_DAYS.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "Fri to Mon,                 2026-08-28, FULL,        2026-08-31, FULL,       ,           4, 4,   2,   4",
            "Thu to Fri,                 2026-08-27, FULL,        2026-08-28, FULL,       ,           2, 2,   2,   2",
            "Fri only,                   2026-08-28, FULL,        2026-08-28, FULL,       ,           1, 1,   1,   1",
            "Mon only,                   2026-08-24, FULL,        2026-08-24, FULL,       ,           1, 1,   1,   1",
            "Mon to Fri with Wed holiday,2026-08-24, FULL,        2026-08-28, FULL,       2026-08-26, 5, 5,   4,   5",
            "Mon to Fri with Fri holiday,2026-08-24, FULL,        2026-08-28, FULL,       2026-08-28, 5, 4,   4,   5",
            "Fri pm to Mon am,           2026-08-28, SECOND_HALF, 2026-08-31, FIRST_HALF, ,           3, 3,   1,   3",
            "Mon pm to Fri am with Wed holiday,2026-08-24, SECOND_HALF, 2026-08-28, FIRST_HALF, 2026-08-26, 4, 4, 3, 4",
            "Fri pm only,                2026-08-28, SECOND_HALF, 2026-08-28, SECOND_HALF, ,          0.5, 0.5, 0.5, 0.5",
            "Fri to Sun,                 2026-08-28, FULL,        2026-08-30, FULL,       ,           3, 1,   1,   3",
            "Sat to Sun,                 2026-08-29, FULL,        2026-08-30, FULL,       ,           2, 0,   0,   2",
            "Sat to Mon,                 2026-08-29, FULL,        2026-08-31, FULL,       ,           3, 1,   1,   3",
    })
    void chargedDaysFollowTheTreatment(String label, String start, String startHalf, String end, String endHalf,
                                       String holiday, double calendarDays,
                                       double sandwich, double exclude, double chargeAll) {
        calendarWithHoliday(holiday == null || holiday.isBlank() ? null : LocalDate.parse(holiday.trim()));
        LocalDate startDate = LocalDate.parse(start.trim());
        LocalDate endDate = LocalDate.parse(end.trim());

        LeaveCharge s = validator.charge(policy(WeekendHolidayTreatment.SANDWICH),
                startDate, half(startHalf.trim()), endDate, half(endHalf.trim()));
        LeaveCharge e = validator.charge(policy(WeekendHolidayTreatment.EXCLUDE_NON_WORKING_DAYS),
                startDate, half(startHalf.trim()), endDate, half(endHalf.trim()));
        LeaveCharge a = validator.charge(policy(WeekendHolidayTreatment.CHARGE_ALL_DAYS),
                startDate, half(startHalf.trim()), endDate, half(endHalf.trim()));

        assertThat(s.chargedDays()).as("%s under SANDWICH", label).isEqualTo(sandwich);
        assertThat(e.chargedDays()).as("%s under EXCLUDE_NON_WORKING_DAYS", label).isEqualTo(exclude);
        assertThat(a.chargedDays()).as("%s under CHARGE_ALL_DAYS", label).isEqualTo(chargeAll);
        for (LeaveCharge charge : new LeaveCharge[] {s, e, a}) {
            assertThat(charge.calendarDays()).as("%s calendar days", label).isEqualTo(calendarDays);
        }
        assertThat(s.rule()).isEqualTo(WeekendHolidayTreatment.SANDWICH);
        assertThat(a.nonWorkingDaysExcluded()).isZero();
    }

    @ParameterizedTest(name = "no policy charges every day: {0}")
    @CsvSource({
            "2026-08-28, 2026-08-31, 4",
            "2026-08-29, 2026-08-30, 2",
    })
    void withoutAPolicy_everyCalendarDayIsCharged(String start, String end, double expected) {
        LeaveCharge charge = validator.charge(null, LocalDate.parse(start), null, LocalDate.parse(end), null);
        assertThat(charge.chargedDays()).isEqualTo(expected);
        assertThat(charge.rule()).isEqualTo(WeekendHolidayTreatment.CHARGE_ALL_DAYS);
    }
}
