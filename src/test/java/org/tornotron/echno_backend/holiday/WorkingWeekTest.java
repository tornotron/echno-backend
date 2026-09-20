package org.tornotron.echno_backend.holiday;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkingWeekTest {

    @Test
    void aFreshRow_worksMondayToFriday() {
        assertThat(new WorkingWeek().workingDaySet()).isEqualTo(WorkingWeek.DEFAULT_WORKING_DAYS);
    }

    @Test
    void theColumnRoundTripsInWeekdayOrder() {
        WorkingWeek week = new WorkingWeek();
        week.setWorkingDaySet(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.MONDAY));

        assertThat(week.getWorkingDays()).isEqualTo("MONDAY,SATURDAY");
        assertThat(week.workingDaySet()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.SATURDAY);
    }

    @Test
    void aBlankColumn_readsAsTheDefaultRatherThanAnEmptyWeek() {
        WorkingWeek week = new WorkingWeek();
        week.setWorkingDays("  ");
        Set<DayOfWeek> days = week.workingDaySet();
        assertThat(days).isEqualTo(WorkingWeek.DEFAULT_WORKING_DAYS);
    }
}
