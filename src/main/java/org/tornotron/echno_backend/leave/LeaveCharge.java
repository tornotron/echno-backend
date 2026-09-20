package org.tornotron.echno_backend.leave;

import org.tornotron.echno_backend.leave.enums.WeekendHolidayTreatment;

/**
 * What a leave request costs: the days charged to the balance, the calendar span it covers, and
 * the treatment that decided the difference.
 *
 * @param chargedDays The days deducted from the balance.
 * @param calendarDays The calendar days from the first to the last day inclusive, less the
 *     half-day allowances at either end; the figure every request was charged before the
 *     treatment existed.
 * @param nonWorkingDaysExcluded The weekend and holiday days inside the request that the
 *     treatment left uncharged.
 * @param rule The treatment applied.
 */
public record LeaveCharge(
        double chargedDays,
        double calendarDays,
        int nonWorkingDaysExcluded,
        WeekendHolidayTreatment rule) {
}
