package org.tornotron.echno_backend.leave;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LeaveDays}. The cases are the ones that reached a screen:
 * a quota that does not divide by twelve, a sum of half-days, and the difference of
 * two rounded figures.
 */
class LeaveDaysTest {

    @Test
    void round_recurringMonthlyAccrual_losesTheFloatingPointTail() {
        // Eight months of a 91-day annual quota, the figure that reached the My Leaves screen.
        double eightMonthsOfNinetyOne = 91.0 / 12.0 * 8;

        assertThat(eightMonthsOfNinetyOne).isNotEqualTo(60.67);
        assertThat(LeaveDays.round(eightMonthsOfNinetyOne)).isEqualTo(60.67);
    }

    @Test
    void round_halfDayFigures_areUntouched() {
        assertThat(LeaveDays.round(0.5)).isEqualTo(0.5);
        assertThat(LeaveDays.round(10.5)).isEqualTo(10.5);
        assertThat(LeaveDays.round(0.0)).isEqualTo(0.0);
    }

    @Test
    void round_wholeDays_stayWhole() {
        assertThat(LeaveDays.round(12.0)).isEqualTo(12.0);
        assertThat(LeaveDays.round(91.0)).isEqualTo(91.0);
    }

    @Test
    void round_isHalfUp() {
        assertThat(LeaveDays.round(1.005)).isEqualTo(1.01);
        assertThat(LeaveDays.round(1.004)).isEqualTo(1.0);
    }

    @Test
    void round_negativeFigures_roundAwayFromZeroOnTheHalf() {
        assertThat(LeaveDays.round(-0.005)).isEqualTo(-0.01);
        assertThat(LeaveDays.round(-2.5)).isEqualTo(-2.5);
    }

    @Test
    void round_absentFigure_isZero() {
        assertThat(LeaveDays.round((Double) null)).isEqualTo(0.0);
    }

    @Test
    void round_boxedFigure_isRoundedLikeThePrimitive() {
        Double raw = 60.666666666666664;

        assertThat(LeaveDays.round(raw)).isEqualTo(60.67);
    }
}
