package org.tornotron.echno_backend.leave.enums;

/**
 * How weekends and declared holidays inside a leave request are charged.
 *
 * <ul>
 *   <li>{@link #CHARGE_ALL_DAYS}: every calendar day from start to end costs a day. This is the
 *       count every request was charged before the treatment was configurable, and the default
 *       every existing policy keeps.
 *   <li>{@link #EXCLUDE_NON_WORKING_DAYS}: a weekend day or a declared holiday is never charged,
 *       wherever it falls in the request.
 *   <li>{@link #SANDWICH}: a non-working day is charged only when it is sandwiched between two
 *       charged leave days. A run of non-working days that touches the request only at its start
 *       or its end is free; a Friday-to-Monday request costs four days, a Thursday-to-Friday one
 *       costs two.
 * </ul>
 */
public enum WeekendHolidayTreatment {
    SANDWICH,
    EXCLUDE_NON_WORKING_DAYS,
    CHARGE_ALL_DAYS
}
