package org.tornotron.echno_backend.attendance.service;

import jakarta.validation.ValidationException;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.ShiftTiming;
import org.tornotron.echno_backend.employee.Employee;

/**
 * Which shift a day of attendance is measured against, and what the caller is told when there is
 * none.
 *
 * <p>The employee's own shift comes first. When they have none, the default shift named in the
 * attendance settings for the project (or the organization-wide settings) is used. That default
 * was stored and documented as the fallback for a check-in but nothing read it, so in a new
 * organization, where most employees have no shift yet, a check-in or a correction of a past day
 * was refused even when an administrator had set one.
 *
 * <p>When neither exists the request is refused with a message that says what is missing and who
 * can fix it. The shift is one of the administrative fields on the employee record, which only the
 * holders of {@code team:edit} across the organization may set (the system admin and the HR admin in
 * the starting roles; see {@code EmployeePatchFieldScope.PERSONNEL_ACTION}).
 */
public final class EffectiveShift {

    private EffectiveShift() {
    }

    /**
     * The shift to use: the one given, or else the settings' default.
     *
     * @param assigned The employee's shift, or one chosen on the request; may be {@code null}.
     * @param settings The effective attendance settings for the project.
     * @return The shift, or {@code null} when neither source names one.
     */
    public static ShiftTiming orSettingsDefault(ShiftTiming assigned, AttendanceSettings settings) {
        if (assigned != null) {
            return assigned;
        }
        return settings == null ? null : settings.getDefaultShiftTiming();
    }

    /**
     * The refusal for an employee with no shift to measure the day against.
     *
     * @param employee The employee the request is for.
     * @param action   What was refused, completing "so ... cannot go ahead", e.g. "the correction".
     * @return The exception to throw; the global handler answers it with a 400.
     */
    public static ValidationException missing(Employee employee, String action) {
        String who = employee.getEmployeeName() == null || employee.getEmployeeName().isBlank()
                ? "This employee" : employee.getEmployeeName();
        return new ValidationException(
                who + " has no shift assigned, and the attendance settings for this project name no "
                        + "default shift, so " + action + " cannot go ahead. An HR admin or system "
                        + "admin can assign a shift on the employee's record, or set a default shift "
                        + "in the attendance settings.");
    }
}
