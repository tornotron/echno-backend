package org.tornotron.echno_backend.attendance.validator;

import org.springframework.stereotype.Component;

@Component
public class GeofenceValidator {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /**
     * Calculate the Haversine distance between two coordinates in meters.
     */
    public double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    /**
     * Check if the given coordinates are within the geofence radius of the project location.
     */
    public boolean isWithinGeofence(double eventLat, double eventLon,
                                     double projectLat, double projectLon,
                                     int geofenceRadiusMeters) {
        double distance = calculateDistance(eventLat, eventLon, projectLat, projectLon);
        return distance <= geofenceRadiusMeters;
    }
}
