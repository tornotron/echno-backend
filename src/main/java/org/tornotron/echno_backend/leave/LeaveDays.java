package org.tornotron.echno_backend.leave;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Rounding for leave-day figures.
 *
 * <p>Day counts are held as {@code double}, and a monthly accrual of
 * {@code annualQuota / 12} rarely divides evenly: a 91-day quota accrued for eight
 * months produces {@code 60.666666666666664}, which is a binary-floating-point
 * artefact of a value that is itself recurring. Every figure the balance reports is
 * therefore rounded to {@link #SCALE} decimal places before it is stored or
 * returned, so no such artefact reaches the API or the screen.
 *
 * <p>Rounding is HALF_UP on the reported figure only. The accrual ledger keeps the
 * exact monthly amounts, so a year's twelve monthly accruals still add up to the
 * annual quota.
 */
public final class LeaveDays {

    /** Decimal places a reported day figure carries. */
    public static final int SCALE = 2;

    private LeaveDays() {
    }

    /**
     * Rounds a day count to the reported precision.
     *
     * @param days The raw day count.
     * @return The count rounded to {@link #SCALE} decimal places.
     */
    public static double round(double days) {
        return BigDecimal.valueOf(days).setScale(SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Rounds a day count that may be absent, treating absence as zero.
     *
     * @param days The raw day count, possibly null.
     * @return The rounded count, or {@code 0.0} when the count is null.
     */
    public static double round(Double days) {
        return days == null ? 0.0 : round(days.doubleValue());
    }
}
