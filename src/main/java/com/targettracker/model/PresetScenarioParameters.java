package com.targettracker.model;

/** Validated shared inputs for a generated maneuver scenario. */
public record PresetScenarioParameters(
        double originLatitudeDegrees,
        double originLongitudeDegrees,
        double averageSpeedMetersPerSecond,
        double altitudeMeters,
        int durationSeconds) {
    public static final int MINIMUM_DURATION_SECONDS = 5 * 60;

    public PresetScenarioParameters {
        if (!Double.isFinite(originLatitudeDegrees)
                || originLatitudeDegrees < -85.0 || originLatitudeDegrees > 85.0) {
            throw new IllegalArgumentException("Latitude must be between -85 and 85 degrees");
        }
        if (!Double.isFinite(originLongitudeDegrees)
                || originLongitudeDegrees < -180.0 || originLongitudeDegrees > 180.0) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180 degrees");
        }
        if (!Double.isFinite(averageSpeedMetersPerSecond)
                || averageSpeedMetersPerSecond < 1.0
                || averageSpeedMetersPerSecond > 250.0) {
            throw new IllegalArgumentException("Average speed must be between 1 and 250 m/s");
        }
        if (!Double.isFinite(altitudeMeters) || altitudeMeters < 0.0
                || altitudeMeters > 20_000.0) {
            throw new IllegalArgumentException("Altitude must be between 0 and 20,000 m");
        }
        if (durationSeconds < MINIMUM_DURATION_SECONDS) {
            throw new IllegalArgumentException("Preset duration must be at least 05:00");
        }
    }

    public GeodeticPoint origin() {
        return new GeodeticPoint(
                originLatitudeDegrees, originLongitudeDegrees, altitudeMeters);
    }
}
