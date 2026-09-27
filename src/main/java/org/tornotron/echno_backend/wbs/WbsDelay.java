package org.tornotron.echno_backend.wbs;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How far an activity's finish is behind the date agreed for it, in days.
 *
 * <p>The agreed date is the planned finish and it is never moved. A finished activity is late by
 * its actual finish minus the planned finish. An open one is late by the later of its forecast
 * finish and today, once the planned finish has passed, minus the planned finish: an activity
 * still open the day after its planned finish is a day late even when nobody has set a forecast.
 * Early or on time is zero. With no planned finish there is nothing to be late against.
 */
public final class WbsDelay {

    private WbsDelay() {
    }

    public static Integer delayDays(LocalDate plannedFinish, LocalDate actualFinish, LocalDate forecastFinish,
                                    LocalDate today) {
        if (plannedFinish == null) {
            return null;
        }
        if (actualFinish != null) {
            return Math.max(0, (int) ChronoUnit.DAYS.between(plannedFinish, actualFinish));
        }
        LocalDate expected = plannedFinish;
        if (forecastFinish != null && forecastFinish.isAfter(expected)) {
            expected = forecastFinish;
        }
        if (today != null && today.isAfter(plannedFinish) && today.isAfter(expected)) {
            expected = today;
        }
        return Math.max(0, (int) ChronoUnit.DAYS.between(plannedFinish, expected));
    }
}
