package org.tornotron.echno_backend.attendance.validator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Haversine distance and the radius comparison behind the attendance geofence.
 *
 * <p>This class was added in March 2026 and had no test and no caller until the geofence was
 * wired up: every clock event stored a hard-coded verdict instead. The distances below are checked
 * against the two real clock events on staging, whose stored coordinates put them 9.66 m and
 * 3.76 m from their project while both rows claimed they were outside a 100 m fence and zero
 * metres away at the same time.
 */
class GeofenceValidatorTest {

    private static final double PROJECT_LAT = 13.0827;
    private static final double PROJECT_LON = 80.2707;

    private final GeofenceValidator validator = new GeofenceValidator();

    @Test
    void distanceIsZeroAtTheProjectMarker() {
        assertThat(validator.calculateDistance(PROJECT_LAT, PROJECT_LON, PROJECT_LAT, PROJECT_LON))
                .isZero();
    }

    @Test
    void aTenthOfADegreeOfLatitudeIsAboutElevenKilometres() {
        // One degree of latitude is 111.32 km anywhere on the sphere, so this is the check that
        // catches a radius, a unit or a radian conversion being wrong.
        double distance = validator.calculateDistance(
                PROJECT_LAT, PROJECT_LON, PROJECT_LAT + 0.1, PROJECT_LON);

        assertThat(distance).isCloseTo(11_132.0, within(20.0));
    }

    @Test
    void aPunchAFewMetresAwayIsInsideAHundredMetreFence() {
        assertThat(validator.isWithinGeofence(
                PROJECT_LAT + 0.00005, PROJECT_LON + 0.00005, PROJECT_LAT, PROJECT_LON, 100))
                .isTrue();
    }

    @Test
    void aPunchAQuarterOfAKilometreAwayIsOutsideAHundredMetreFence() {
        assertThat(validator.isWithinGeofence(
                PROJECT_LAT + 0.0023, PROJECT_LON, PROJECT_LAT, PROJECT_LON, 100))
                .isFalse();
    }

    @Test
    void theBoundaryItselfCounts_asInside() {
        double radius = validator.calculateDistance(
                PROJECT_LAT, PROJECT_LON, PROJECT_LAT + 0.0023, PROJECT_LON);

        assertThat(validator.isWithinGeofence(
                PROJECT_LAT + 0.0023, PROJECT_LON, PROJECT_LAT, PROJECT_LON, (int) Math.ceil(radius)))
                .isTrue();
    }
}
