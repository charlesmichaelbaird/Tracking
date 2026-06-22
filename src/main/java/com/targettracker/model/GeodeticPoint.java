package com.targettracker.model;

/** Geodetic latitude, longitude, and ellipsoidal altitude above WGS-84. */
public record GeodeticPoint(double latitudeDegrees, double longitudeDegrees, double altitudeMeters) {
    public GeodeticPoint {
        if (!Double.isFinite(latitudeDegrees)
                || !Double.isFinite(longitudeDegrees)
                || !Double.isFinite(altitudeMeters)) {
            throw new IllegalArgumentException("Geodetic coordinates must be finite");
        }
        latitudeDegrees = Math.max(-90.0, Math.min(90.0, latitudeDegrees));
        longitudeDegrees = normalizeLongitude(longitudeDegrees);
    }

    public GeodeticPoint withAltitude(double newAltitudeMeters) {
        return new GeodeticPoint(latitudeDegrees, longitudeDegrees, newAltitudeMeters);
    }

    public static double normalizeLongitude(double longitudeDegrees) {
        double normalized = (longitudeDegrees + 180.0) % 360.0;
        if (normalized < 0.0) {
            normalized += 360.0;
        }
        return normalized - 180.0;
    }
}
