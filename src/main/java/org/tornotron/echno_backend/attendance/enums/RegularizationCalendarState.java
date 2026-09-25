package org.tornotron.echno_backend.attendance.enums;

/**
 * What the regularization calendar shows for one day of an employee's month.
 *
 * <p>The states are ordered by what decides them, first match wins: a future day, then leave,
 * then a pending request, then what the attendance records say, and last whether the day is a
 * working day at all.
 */
public enum RegularizationCalendarState {
    /** After today. Nothing can be raised for it. */
    FUTURE,
    /** Covered by an approved leave, or by a record marked as leave. */
    LEAVE,
    /** Covered by a leave request still awaiting a decision. */
    LEAVE_PENDING,
    /** A regularization request for the day is awaiting a decision. */
    PENDING,
    /** Complete, and completed by an approved regularization. */
    REGULARIZED,
    /** A record with both a clock-in and a clock-out. */
    COMPLETE,
    /** A record exists but is missing its clock-in or its clock-out. */
    INCOMPLETE,
    /** A working day with no attendance record at all. */
    MISSING,
    /** A weekly off or a declared holiday with no attendance record. */
    NON_WORKING
}
