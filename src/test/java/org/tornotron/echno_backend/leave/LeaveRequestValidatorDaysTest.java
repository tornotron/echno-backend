package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tornotron.echno_backend.common.exception.InvalidRequestException;
import org.tornotron.echno_backend.leave.enums.HalfDayType;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LeaveRequestValidator#calculateTotalDays}, the arithmetic that
 * decides how many days a leave request costs. Neither collaborator is touched by it, so
 * both are mocked and never stubbed.
 *
 * <p>The rule being pinned: a request that starts on the second half of its first day, or
 * ends on the first half of its last day, gives half a day back. A first half on the first
 * day, or a second half on the last, describes a leave with a gap in the middle and is
 * ignored, which is why the apply form does not offer those combinations.
 *
 * <p>The count is of <em>calendar</em> days. A leave spanning a weekend or a public holiday
 * costs those days too; nothing here consults a working-day calendar. That is the behaviour
 * as built, and the weekend cases below record it deliberately rather than by omission.
 */
@ExtendWith(MockitoExtension.class)
class LeaveRequestValidatorDaysTest {

    @Mock private LeaveRequestRepository requestRepository;
    @Mock private LeaveBalanceService balanceService;

    private LeaveRequestValidator validator;

    // 2026-08-24 is a Monday, so the week runs Mon 24 ... Fri 28, Sat 29, Sun 30.
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 8, 25);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 8, 28);
    private static final LocalDate NEXT_MONDAY = LocalDate.of(2026, 8, 31);

    @BeforeEach
    void setUp() {
        validator = new LeaveRequestValidator(requestRepository, balanceService);
    }

    private double days(LocalDate start, HalfDayType startType, LocalDate end, HalfDayType endType) {
        return validator.calculateTotalDays(start, startType, end, endType);
    }

    // --- a period that starts and ends on the same day -----------------------

    @Test
    void oneDay_withNoHalfChosen_isAWholeDay() {
        assertThat(days(MONDAY, null, MONDAY, null)).isEqualTo(1.0);
        assertThat(days(MONDAY, HalfDayType.FULL_DAY, MONDAY, HalfDayType.FULL_DAY))
                .isEqualTo(1.0);
    }

    @Test
    void oneDay_firstHalf_isHalfADay() {
        assertThat(days(MONDAY, HalfDayType.FIRST_HALF, MONDAY, HalfDayType.FIRST_HALF))
                .isEqualTo(0.5);
    }

    @Test
    void oneDay_secondHalf_isHalfADay() {
        assertThat(days(MONDAY, HalfDayType.SECOND_HALF, MONDAY, HalfDayType.SECOND_HALF))
                .isEqualTo(0.5);
    }

    @Test
    void oneDay_withAHalfOnOnlyOneEnd_isStillHalfADay() {
        assertThat(days(MONDAY, HalfDayType.FIRST_HALF, MONDAY, null)).isEqualTo(0.5);
        assertThat(days(MONDAY, null, MONDAY, HalfDayType.SECOND_HALF)).isEqualTo(0.5);
    }

    @Test
    void oneDay_withOppositeHalvesOnEachEnd_isHalfADayNotAWholeOne() {
        // The combination the apply form used to allow on a single date: second half as the
        // first day, first half as the last. It is one date, so it can only be half a
        // day, never the whole day it read as on screen.
        assertThat(days(MONDAY, HalfDayType.SECOND_HALF, MONDAY, HalfDayType.FIRST_HALF))
                .isEqualTo(0.5);
    }

    // --- a period across several days ---------------------------------------

    @Test
    void twoWholeDays_countTwo() {
        assertThat(days(MONDAY, null, TUESDAY, null)).isEqualTo(2.0);
    }

    @Test
    void startingAtMidday_givesBackHalfTheFirstDay() {
        assertThat(days(MONDAY, HalfDayType.SECOND_HALF, TUESDAY, null)).isEqualTo(1.5);
    }

    @Test
    void endingAtMidday_givesBackHalfTheLastDay() {
        assertThat(days(MONDAY, null, TUESDAY, HalfDayType.FIRST_HALF)).isEqualTo(1.5);
    }

    @Test
    void aHalfDayAtEachEndOfThePeriod_givesBackAWholeDay() {
        assertThat(days(MONDAY, HalfDayType.SECOND_HALF, TUESDAY, HalfDayType.FIRST_HALF))
                .isEqualTo(1.0);
    }

    @Test
    void aHalfOnTheWrongEndOfThePeriod_changesNothing() {
        // Leaving on the morning of the first day, or returning on the afternoon of the
        // last, describes a leave with a hole in the middle. The total ignores it, which is
        // why the apply form does not offer these two.
        assertThat(days(MONDAY, HalfDayType.FIRST_HALF, TUESDAY, null)).isEqualTo(2.0);
        assertThat(days(MONDAY, null, TUESDAY, HalfDayType.SECOND_HALF)).isEqualTo(2.0);
    }

    @Test
    void halfDaysOnEitherSideOfAWeekend_stillCountTheWeekend() {
        // Friday afternoon through Monday morning. Four calendar days, less a half at each
        // end, is three: the Saturday and the Sunday are charged to the balance. Whether
        // that is the intended policy is a question for the organization, not the code.
        // It is recorded here so that changing it is a deliberate act.
        assertThat(days(FRIDAY, HalfDayType.SECOND_HALF, NEXT_MONDAY, HalfDayType.FIRST_HALF))
                .isEqualTo(3.0);
    }

    @Test
    void aPeriodSpanningAWeekend_countsCalendarDays() {
        assertThat(days(FRIDAY, null, NEXT_MONDAY, null)).isEqualTo(4.0);
    }

    @Test
    void aFullWorkingWeek_countsFiveDays() {
        assertThat(days(MONDAY, null, FRIDAY, null)).isEqualTo(5.0);
    }

    @Test
    void aFullWorkingWeekStartingAtMidday_countsFourAndAHalf() {
        assertThat(days(MONDAY, HalfDayType.SECOND_HALF, FRIDAY, null)).isEqualTo(4.5);
    }

    // --- a period that cannot exist ------------------------------------------

    @Test
    void aPeriodThatEndsBeforeItStarts_isRefused() {
        assertThatThrownBy(() -> days(TUESDAY, null, MONDAY, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("cannot be after end date");
    }
}
