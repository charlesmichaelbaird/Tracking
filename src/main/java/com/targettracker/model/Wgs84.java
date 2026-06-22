package com.targettracker.model;

/** High-precision WGS-84 geodetic/ECEF conversion utilities. */
public final class Wgs84 {
    public static final double SEMI_MAJOR_AXIS_METERS = 6_378_137.0;
    public static final double FLATTENING = 1.0 / 298.257_223_563;
    public static final double SEMI_MINOR_AXIS_METERS =
            SEMI_MAJOR_AXIS_METERS * (1.0 - FLATTENING);
    public static final double FIRST_ECCENTRICITY_SQUARED =
            FLATTENING * (2.0 - FLATTENING);

    private Wgs84() {
    }

    public static EcefPoint toEcef(GeodeticPoint geodetic) {
        double latitude = Math.toRadians(geodetic.latitudeDegrees());
        double longitude = Math.toRadians(geodetic.longitudeDegrees());
        double sinLatitude = Math.sin(latitude);
        double cosLatitude = Math.cos(latitude);
        double primeVerticalRadius = SEMI_MAJOR_AXIS_METERS
                / Math.sqrt(1.0 - FIRST_ECCENTRICITY_SQUARED * sinLatitude * sinLatitude);
        double altitude = geodetic.altitudeMeters();

        double x = (primeVerticalRadius + altitude) * cosLatitude * Math.cos(longitude);
        double y = (primeVerticalRadius + altitude) * cosLatitude * Math.sin(longitude);
        double z = (primeVerticalRadius * (1.0 - FIRST_ECCENTRICITY_SQUARED) + altitude)
                * sinLatitude;
        return new EcefPoint(x, y, z);
    }

    public static GeodeticPoint toGeodetic(EcefPoint ecef) {
        double longitude = Math.atan2(ecef.y(), ecef.x());
        double distanceFromAxis = Math.hypot(ecef.x(), ecef.y());
        if (distanceFromAxis < 1.0e-9) {
            double latitude = Math.copySign(Math.PI / 2.0, ecef.z());
            double altitude = Math.abs(ecef.z()) - SEMI_MINOR_AXIS_METERS;
            return new GeodeticPoint(Math.toDegrees(latitude), 0.0, altitude);
        }

        double latitude = Math.atan2(
                ecef.z(),
                distanceFromAxis * (1.0 - FIRST_ECCENTRICITY_SQUARED));
        double altitude = 0.0;
        for (int iteration = 0; iteration < 12; iteration++) {
            double sinLatitude = Math.sin(latitude);
            double primeVerticalRadius = SEMI_MAJOR_AXIS_METERS
                    / Math.sqrt(1.0 - FIRST_ECCENTRICITY_SQUARED * sinLatitude * sinLatitude);
            altitude = altitudeFor(latitude, distanceFromAxis, ecef.z(), primeVerticalRadius);
            double nextLatitude = Math.atan2(
                    ecef.z(),
                    distanceFromAxis * (1.0 - FIRST_ECCENTRICITY_SQUARED
                            * primeVerticalRadius / (primeVerticalRadius + altitude)));
            if (Math.abs(nextLatitude - latitude) < 1.0e-13) {
                latitude = nextLatitude;
                break;
            }
            latitude = nextLatitude;
        }

        double sinLatitude = Math.sin(latitude);
        double primeVerticalRadius = SEMI_MAJOR_AXIS_METERS
                / Math.sqrt(1.0 - FIRST_ECCENTRICITY_SQUARED * sinLatitude * sinLatitude);
        altitude = altitudeFor(latitude, distanceFromAxis, ecef.z(), primeVerticalRadius);
        return new GeodeticPoint(
                Math.toDegrees(latitude),
                Math.toDegrees(longitude),
                altitude);
    }

    public static double metersPerDegreeLatitude(double latitudeDegrees) {
        double latitude = Math.toRadians(latitudeDegrees);
        double sinLatitude = Math.sin(latitude);
        double denominator = 1.0 - FIRST_ECCENTRICITY_SQUARED * sinLatitude * sinLatitude;
        double meridionalRadius = SEMI_MAJOR_AXIS_METERS
                * (1.0 - FIRST_ECCENTRICITY_SQUARED)
                / Math.pow(denominator, 1.5);
        return Math.PI / 180.0 * meridionalRadius;
    }

    public static double metersPerDegreeLongitude(double latitudeDegrees) {
        double latitude = Math.toRadians(latitudeDegrees);
        double sinLatitude = Math.sin(latitude);
        double primeVerticalRadius = SEMI_MAJOR_AXIS_METERS
                / Math.sqrt(1.0 - FIRST_ECCENTRICITY_SQUARED * sinLatitude * sinLatitude);
        return Math.PI / 180.0 * primeVerticalRadius * Math.cos(latitude);
    }

    private static double altitudeFor(
            double latitude,
            double distanceFromAxis,
            double z,
            double primeVerticalRadius) {
        double cosLatitude = Math.cos(latitude);
        if (Math.abs(cosLatitude) > 0.01) {
            return distanceFromAxis / cosLatitude - primeVerticalRadius;
        }
        return z / Math.sin(latitude)
                - primeVerticalRadius * (1.0 - FIRST_ECCENTRICITY_SQUARED);
    }
}
