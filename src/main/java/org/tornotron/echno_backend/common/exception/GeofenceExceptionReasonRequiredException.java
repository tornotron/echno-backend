package org.tornotron.echno_backend.common.exception;

/**
 * Raised when an employee marks their own attendance from outside the project's geofence without
 * saying why.
 *
 * <p>This is not a refusal. Being outside the fence is allowed and expected, for instance a site
 * engineer punching in from head office; it needs a reason on the record and a manager's decision
 * afterwards. The client is being asked for the reason, not turned away, which is why this carries
 * the measured distance and the radius: the prompt should be able to tell the employee how far out
 * they are rather than repeating a generic message.
 */
public class GeofenceExceptionReasonRequiredException extends RuntimeException {

    private final double distanceMeters;
    private final int radiusMeters;

    public GeofenceExceptionReasonRequiredException(double distanceMeters, int radiusMeters) {
        super("This location is " + Math.round(distanceMeters) + " m from the project site, outside "
                + "the " + radiusMeters + " m site boundary. Give a reason to mark attendance from "
                + "here; the record will be held for your reporting manager to approve.");
        this.distanceMeters = distanceMeters;
        this.radiusMeters = radiusMeters;
    }

    /** How far the punch was from the project marker, in metres. */
    public double getDistanceMeters() {
        return distanceMeters;
    }

    /** The geofence radius that applied, in metres. */
    public int getRadiusMeters() {
        return radiusMeters;
    }
}
