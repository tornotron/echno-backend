package org.tornotron.echno_backend.wbs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The delay rule: measured against the planned finish, which never moves. */
class WbsDelayTest {

    private static final LocalDate PLANNED = LocalDate.of(2026, 9, 10);

    @Test
    void withNoPlannedFinishThereIsNothingToBeLateAgainst() {
        assertThat(WbsDelay.delayDays(null, null, null, PLANNED)).isNull();
    }

    @Test
    void aFinishedActivityIsLateByItsActualFinish() {
        assertThat(WbsDelay.delayDays(PLANNED, PLANNED.plusDays(3), null, PLANNED.plusDays(30))).isEqualTo(3);
        assertThat(WbsDelay.delayDays(PLANNED, PLANNED.minusDays(2), null, PLANNED)).isZero();
    }

    @Test
    void anOpenActivityBeforeItsPlannedFinishIsOnTimeUnlessForecastLate() {
        assertThat(WbsDelay.delayDays(PLANNED, null, null, PLANNED.minusDays(5))).isZero();
        assertThat(WbsDelay.delayDays(PLANNED, null, PLANNED.plusDays(4), PLANNED.minusDays(5))).isEqualTo(4);
    }

    @Test
    void anOpenActivityPastItsPlannedFinishIsLateAtLeastUntilToday() {
        assertThat(WbsDelay.delayDays(PLANNED, null, null, PLANNED.plusDays(6))).isEqualTo(6);
        assertThat(WbsDelay.delayDays(PLANNED, null, PLANNED.plusDays(2), PLANNED.plusDays(6))).isEqualTo(6);
        assertThat(WbsDelay.delayDays(PLANNED, null, PLANNED.plusDays(9), PLANNED.plusDays(6))).isEqualTo(9);
    }
}
