package org.tornotron.echno_backend.attendance;

import org.tornotron.echno_backend.common.exception.UnprocessableRequestException;

/**
 * Raised when a supervisor marks attendance for somebody else from outside the project's geofence.
 *
 * <p>This is a refusal, unlike the self-marking case. An employee away from the site can explain
 * themselves and have their manager decide; a supervisor entering attendance for their team is
 * vouching that the team is on site, and that claim is only worth something when the supervisor
 * is standing there too. So there is no reason to give and no day to hold: the entry is not
 * written. The measured distance and the radius travel in the message, the way the self-marking
 * prompt reports them, so the supervisor is told how far out they are rather than turned away
 * with a generic sentence.
 *
 * <p>Extends {@link UnprocessableRequestException} so it answers as a 422 through the handler
 * that exception already has.
 */
public class TeamMarkingOutsideGeofenceException extends UnprocessableRequestException {

    private final double distanceMeters;
    private final int radiusMeters;

    public TeamMarkingOutsideGeofenceException(double distanceMeters, int radiusMeters) {
        super("You are " + Math.round(distanceMeters) + " m from the project site, outside the "
                + radiusMeters + " m site boundary. Attendance can only be marked for the team "
                + "from the site: move within " + radiusMeters + " m of the project marker and "
                + "try again.");
        this.distanceMeters = distanceMeters;
        this.radiusMeters = radiusMeters;
    }

    /** How far the supervisor was from the project marker, in metres. */
    public double getDistanceMeters() {
        return distanceMeters;
    }

    /** The geofence radius that applied, in metres. */
    public int getRadiusMeters() {
        return radiusMeters;
    }
}
