package org.tornotron.echno_backend.attendance.service;

import org.springframework.stereotype.Service;
import org.tornotron.echno_backend.attendance.AttendanceSettings;
import org.tornotron.echno_backend.attendance.ClockEvent;
import org.tornotron.echno_backend.attendance.validator.GeofenceValidator;
import org.tornotron.echno_backend.common.enums.OrgRole;
import org.tornotron.echno_backend.employee.Employee;
import org.tornotron.echno_backend.project.Project;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates a clock event against the project's geofence and works out who decides an exception.
 *
 * <p>The three inputs were all present long before anything joined them: the project's coordinates
 * are the centre, the effective attendance settings carry the radius, and the punch carries the
 * position captured on the device. {@link GeofenceValidator} has held the Haversine distance since
 * March 2026 and was never called; this is what calls it.
 *
 * <p>An evaluation is either complete or absent. When any of the three inputs is missing the
 * result is {@link Evaluation#notEvaluated()}, which the entity stores as nulls, and no verdict is
 * implied. That matters more than it sounds: the columns spent months holding a hard-coded
 * {@code false} and {@code 0.0} that a reader could not tell apart from a measurement.
 */
@Service
public class AttendanceGeofenceService {

    private final GeofenceValidator geofenceValidator;

    public AttendanceGeofenceService(GeofenceValidator geofenceValidator) {
        this.geofenceValidator = geofenceValidator;
    }

    /**
     * The outcome of comparing one punch against one project's geofence.
     *
     * <p>All three components are set together or all three are null. {@code withinFence} is the
     * verdict, {@code distanceMeters} the measured distance from the project's marker, and
     * {@code radiusMeters} the radius the verdict was reached against, kept so the row still
     * explains itself after the settings or the project's coordinates are edited.
     *
     * @param withinFence Whether the punch fell inside the radius, or null when not evaluated.
     * @param distanceMeters Distance from the project marker in metres, or null when not measured.
     * @param radiusMeters The radius applied, in metres, or null when not evaluated.
     */
    public record Evaluation(Boolean withinFence, Double distanceMeters, Integer radiusMeters) {

        private static final Evaluation NOT_EVALUATED = new Evaluation(null, null, null);

        /** No verdict was reached, because at least one of centre, radius or position was absent. */
        public static Evaluation notEvaluated() {
            return NOT_EVALUATED;
        }

        /** Whether a verdict was reached at all. */
        public boolean isEvaluated() {
            return withinFence != null;
        }

        /** Whether a verdict was reached and it puts the punch outside the fence. */
        public boolean isOutsideFence() {
            return Boolean.FALSE.equals(withinFence);
        }
    }

    /**
     * Compares a captured position against a project's geofence.
     *
     * <p>Returns {@link Evaluation#notEvaluated()} rather than guessing whenever the project has no
     * coordinates, the settings carry no radius, or the punch carries no position. Roughly one
     * project in six has no coordinates set, so an absent verdict is an ordinary outcome and not an
     * error.
     *
     * <p>The distance is rounded to the centimetre. The project's coordinates are stored as
     * {@code Float}, which holds about seven significant digits, so the metre is already the
     * honest limit of this measurement and a full double's worth of decimals would read as
     * precision that is not there.
     *
     * @param project The project being marked against, or null when it cannot be resolved.
     * @param settings The effective attendance settings for that project, or null.
     * @param latitude The latitude captured on the device, or null.
     * @param longitude The longitude captured on the device, or null.
     * @return The verdict, distance and radius, or an unevaluated result.
     */
    public Evaluation evaluate(Project project, AttendanceSettings settings,
                               Double latitude, Double longitude) {
        if (project == null || latitude == null || longitude == null
                || project.getProjectLatitude() == null || project.getProjectLongitude() == null) {
            return Evaluation.notEvaluated();
        }
        Integer radius = settings == null ? null : settings.getGeofenceRadiusMeters();
        if (radius == null) {
            return Evaluation.notEvaluated();
        }

        double distance = geofenceValidator.calculateDistance(
                latitude, longitude,
                project.getProjectLatitude(), project.getProjectLongitude());
        double rounded = Math.round(distance * 100.0) / 100.0;
        return new Evaluation(rounded <= radius, rounded, radius);
    }

    /**
     * Writes an evaluation onto a clock event, including an unevaluated one.
     *
     * @param event The clock event to stamp.
     * @param evaluation The evaluation to write.
     */
    public void applyTo(ClockEvent event, Evaluation evaluation) {
        event.setIsWithinGeofence(evaluation.withinFence());
        event.setDistanceFromProject(evaluation.distanceMeters());
        event.setGeofenceRadiusMeters(evaluation.radiusMeters());
    }

    /**
     * Names the employee expected to decide a geofence exception raised by this employee on this
     * project.
     *
     * <p>The decision is the reporting manager's: an employee who is legitimately away from the
     * site, at head office or between sites, is someone their own manager can vouch for. That
     * relation exists on the employee as the self-referencing {@code manager}, which is what the
     * leave module already walks to build an approval chain.
     *
     * <p>It is set on a minority of employees, so there is a fallback, and the fallback is
     * deliberately the narrowest thing the schema can already answer rather than a new hierarchy: a
     * project manager assigned to the project being marked against. A project has no manager
     * pointer of its own, only membership plus the organization-wide {@code project-manager} role,
     * so that is the intersection of the two. Where several qualify, the lowest employee id is
     * taken, so the same exception always names the same approver.
     *
     * <p>Null when neither resolves. A null does not leave the record unapprovable: it falls to the
     * record-management roles that already decide every attendance record.
     *
     * @param employee The employee whose punch fell outside the fence.
     * @param project The project the punch was recorded against, or null when unresolved.
     * @return The approver's employee id, or null when none could be named.
     */
    public Long resolveApprover(Employee employee, Project project) {
        Long employeeId = employee == null ? null : employee.getId();

        Employee manager = employee == null ? null : employee.getManager();
        if (manager != null && manager.getId() != null && !manager.getId().equals(employeeId)) {
            return manager.getId();
        }
        return projectManagerFor(project, employeeId);
    }

    /**
     * The lowest-numbered employee assigned to the project who holds the project-manager role,
     * never the employee raising the exception themselves.
     */
    private Long projectManagerFor(Project project, Long employeeId) {
        List<Employee> members = project == null ? null : project.getEmployees();
        if (members == null || members.isEmpty()) {
            return null;
        }
        return members.stream()
                .filter(Objects::nonNull)
                .filter(member -> member.getId() != null && !member.getId().equals(employeeId))
                .filter(member -> member.getOrgRoles() != null
                        && member.getOrgRoles().contains(OrgRole.PROJECT_MANAGER))
                .map(Employee::getId)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }
}
