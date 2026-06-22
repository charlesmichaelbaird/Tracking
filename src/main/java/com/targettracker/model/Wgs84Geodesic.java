package com.targettracker.model;

/**
 * Vincenty direct/inverse geodesics on the WGS-84 ellipsoid. A spherical
 * fallback handles the rare near-antipodal pairs where Vincenty does not converge.
 */
public final class Wgs84Geodesic {
    private static final int MAX_ITERATIONS = 100;
    private static final double CONVERGENCE = 1.0e-12;
    private static final double MEAN_EARTH_RADIUS_METERS = 6_371_008.8;

    private Wgs84Geodesic() {
    }

    public static GeodesicData inverse(GeodeticPoint start, GeodeticPoint end) {
        double latitude1 = Math.toRadians(start.latitudeDegrees());
        double latitude2 = Math.toRadians(end.latitudeDegrees());
        double longitudeDifference = Math.toRadians(GeodeticPoint.normalizeLongitude(
                end.longitudeDegrees() - start.longitudeDegrees()));
        if (Math.abs(latitude1 - latitude2) < 1.0e-15
                && Math.abs(longitudeDifference) < 1.0e-15) {
            return new GeodesicData(0.0, 0.0, true);
        }

        double reducedLatitude1 = Math.atan((1.0 - Wgs84.FLATTENING) * Math.tan(latitude1));
        double reducedLatitude2 = Math.atan((1.0 - Wgs84.FLATTENING) * Math.tan(latitude2));
        double sinU1 = Math.sin(reducedLatitude1);
        double cosU1 = Math.cos(reducedLatitude1);
        double sinU2 = Math.sin(reducedLatitude2);
        double cosU2 = Math.cos(reducedLatitude2);

        double lambda = longitudeDifference;
        double previousLambda;
        double sinSigma = 0.0;
        double cosSigma = 0.0;
        double sigma = 0.0;
        double sinAlpha = 0.0;
        double cosSquaredAlpha = 0.0;
        double cosTwoSigmaM = 0.0;
        boolean converged = false;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            double sinLambda = Math.sin(lambda);
            double cosLambda = Math.cos(lambda);
            double first = cosU2 * sinLambda;
            double second = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda;
            sinSigma = Math.hypot(first, second);
            if (sinSigma == 0.0) {
                return new GeodesicData(0.0, 0.0, true);
            }
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda;
            sigma = Math.atan2(sinSigma, cosSigma);
            sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma;
            cosSquaredAlpha = 1.0 - sinAlpha * sinAlpha;
            cosTwoSigmaM = cosSquaredAlpha < 1.0e-15
                    ? 0.0
                    : cosSigma - 2.0 * sinU1 * sinU2 / cosSquaredAlpha;
            double coefficient = Wgs84.FLATTENING / 16.0 * cosSquaredAlpha
                    * (4.0 + Wgs84.FLATTENING * (4.0 - 3.0 * cosSquaredAlpha));
            previousLambda = lambda;
            lambda = longitudeDifference + (1.0 - coefficient) * Wgs84.FLATTENING * sinAlpha
                    * (sigma + coefficient * sinSigma
                    * (cosTwoSigmaM + coefficient * cosSigma
                    * (-1.0 + 2.0 * cosTwoSigmaM * cosTwoSigmaM)));
            if (Math.abs(lambda - previousLambda) < CONVERGENCE) {
                converged = true;
                break;
            }
        }

        if (!converged) {
            return sphericalInverse(start, end);
        }

        double uSquared = cosSquaredAlpha
                * (Wgs84.SEMI_MAJOR_AXIS_METERS * Wgs84.SEMI_MAJOR_AXIS_METERS
                - Wgs84.SEMI_MINOR_AXIS_METERS * Wgs84.SEMI_MINOR_AXIS_METERS)
                / (Wgs84.SEMI_MINOR_AXIS_METERS * Wgs84.SEMI_MINOR_AXIS_METERS);
        double a = 1.0 + uSquared / 16_384.0
                * (4_096.0 + uSquared * (-768.0 + uSquared * (320.0 - 175.0 * uSquared)));
        double b = uSquared / 1_024.0
                * (256.0 + uSquared * (-128.0 + uSquared * (74.0 - 47.0 * uSquared)));
        double deltaSigma = b * sinSigma * (cosTwoSigmaM + b / 4.0
                * (cosSigma * (-1.0 + 2.0 * cosTwoSigmaM * cosTwoSigmaM)
                - b / 6.0 * cosTwoSigmaM * (-3.0 + 4.0 * sinSigma * sinSigma)
                * (-3.0 + 4.0 * cosTwoSigmaM * cosTwoSigmaM)));
        double distance = Wgs84.SEMI_MINOR_AXIS_METERS * a * (sigma - deltaSigma);
        double initialBearing = Math.atan2(
                cosU2 * Math.sin(lambda),
                cosU1 * sinU2 - sinU1 * cosU2 * Math.cos(lambda));
        return new GeodesicData(distance, initialBearing, true);
    }

    public static GeodeticPoint interpolate(
            GeodeticPoint start,
            GeodeticPoint end,
            double fraction,
            double altitudeMeters) {
        double clampedFraction = Math.max(0.0, Math.min(1.0, fraction));
        if (clampedFraction == 0.0) {
            return new GeodeticPoint(
                    start.latitudeDegrees(), start.longitudeDegrees(), altitudeMeters);
        }
        if (clampedFraction == 1.0) {
            return new GeodeticPoint(
                    end.latitudeDegrees(), end.longitudeDegrees(), altitudeMeters);
        }
        GeodesicData geodesic = inverse(start, end);
        if (!geodesic.vincentyConverged()) {
            return sphericalDirect(start, geodesic.initialBearingRadians(),
                    geodesic.distanceMeters() * clampedFraction, altitudeMeters);
        }
        return direct(start, geodesic.initialBearingRadians(),
                geodesic.distanceMeters() * clampedFraction, altitudeMeters);
    }

    public static GeodeticPoint direct(
            GeodeticPoint start,
            double initialBearingRadians,
            double distanceMeters,
            double altitudeMeters) {
        double latitude1 = Math.toRadians(start.latitudeDegrees());
        double longitude1 = Math.toRadians(start.longitudeDegrees());
        double reducedLatitude1 = Math.atan((1.0 - Wgs84.FLATTENING) * Math.tan(latitude1));
        double sinU1 = Math.sin(reducedLatitude1);
        double cosU1 = Math.cos(reducedLatitude1);
        double sinBearing = Math.sin(initialBearingRadians);
        double cosBearing = Math.cos(initialBearingRadians);
        double sigma1 = Math.atan2(Math.tan(reducedLatitude1), cosBearing);
        double sinAlpha = cosU1 * sinBearing;
        double cosSquaredAlpha = 1.0 - sinAlpha * sinAlpha;
        double uSquared = cosSquaredAlpha
                * (Wgs84.SEMI_MAJOR_AXIS_METERS * Wgs84.SEMI_MAJOR_AXIS_METERS
                - Wgs84.SEMI_MINOR_AXIS_METERS * Wgs84.SEMI_MINOR_AXIS_METERS)
                / (Wgs84.SEMI_MINOR_AXIS_METERS * Wgs84.SEMI_MINOR_AXIS_METERS);
        double a = 1.0 + uSquared / 16_384.0
                * (4_096.0 + uSquared * (-768.0 + uSquared * (320.0 - 175.0 * uSquared)));
        double b = uSquared / 1_024.0
                * (256.0 + uSquared * (-128.0 + uSquared * (74.0 - 47.0 * uSquared)));

        double sigma = distanceMeters / (Wgs84.SEMI_MINOR_AXIS_METERS * a);
        double previousSigma;
        double cosTwoSigmaM;
        double sinSigma;
        double cosSigma;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            cosTwoSigmaM = Math.cos(2.0 * sigma1 + sigma);
            sinSigma = Math.sin(sigma);
            cosSigma = Math.cos(sigma);
            double deltaSigma = b * sinSigma * (cosTwoSigmaM + b / 4.0
                    * (cosSigma * (-1.0 + 2.0 * cosTwoSigmaM * cosTwoSigmaM)
                    - b / 6.0 * cosTwoSigmaM * (-3.0 + 4.0 * sinSigma * sinSigma)
                    * (-3.0 + 4.0 * cosTwoSigmaM * cosTwoSigmaM)));
            previousSigma = sigma;
            sigma = distanceMeters / (Wgs84.SEMI_MINOR_AXIS_METERS * a) + deltaSigma;
            if (Math.abs(sigma - previousSigma) < CONVERGENCE) {
                break;
            }
        }

        cosTwoSigmaM = Math.cos(2.0 * sigma1 + sigma);
        sinSigma = Math.sin(sigma);
        cosSigma = Math.cos(sigma);
        double temporary = sinU1 * sinSigma - cosU1 * cosSigma * cosBearing;
        double latitude2 = Math.atan2(
                sinU1 * cosSigma + cosU1 * sinSigma * cosBearing,
                (1.0 - Wgs84.FLATTENING) * Math.hypot(sinAlpha, temporary));
        double lambda = Math.atan2(
                sinSigma * sinBearing,
                cosU1 * cosSigma - sinU1 * sinSigma * cosBearing);
        double coefficient = Wgs84.FLATTENING / 16.0 * cosSquaredAlpha
                * (4.0 + Wgs84.FLATTENING * (4.0 - 3.0 * cosSquaredAlpha));
        double longitudeCorrection = (1.0 - coefficient) * Wgs84.FLATTENING * sinAlpha
                * (sigma + coefficient * sinSigma * (cosTwoSigmaM + coefficient * cosSigma
                * (-1.0 + 2.0 * cosTwoSigmaM * cosTwoSigmaM)));
        double longitude2 = longitude1 + lambda - longitudeCorrection;
        return new GeodeticPoint(
                Math.toDegrees(latitude2),
                Math.toDegrees(longitude2),
                altitudeMeters);
    }

    private static GeodesicData sphericalInverse(GeodeticPoint start, GeodeticPoint end) {
        double latitude1 = Math.toRadians(start.latitudeDegrees());
        double latitude2 = Math.toRadians(end.latitudeDegrees());
        double longitudeDifference = Math.toRadians(GeodeticPoint.normalizeLongitude(
                end.longitudeDegrees() - start.longitudeDegrees()));
        double sinHalfLatitude = Math.sin((latitude2 - latitude1) / 2.0);
        double sinHalfLongitude = Math.sin(longitudeDifference / 2.0);
        double haversine = sinHalfLatitude * sinHalfLatitude
                + Math.cos(latitude1) * Math.cos(latitude2)
                * sinHalfLongitude * sinHalfLongitude;
        double centralAngle = 2.0 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1.0 - haversine));
        double bearing = Math.atan2(
                Math.sin(longitudeDifference) * Math.cos(latitude2),
                Math.cos(latitude1) * Math.sin(latitude2)
                        - Math.sin(latitude1) * Math.cos(latitude2) * Math.cos(longitudeDifference));
        return new GeodesicData(MEAN_EARTH_RADIUS_METERS * centralAngle, bearing, false);
    }

    private static GeodeticPoint sphericalDirect(
            GeodeticPoint start,
            double initialBearingRadians,
            double distanceMeters,
            double altitudeMeters) {
        double latitude1 = Math.toRadians(start.latitudeDegrees());
        double longitude1 = Math.toRadians(start.longitudeDegrees());
        double angularDistance = distanceMeters / MEAN_EARTH_RADIUS_METERS;
        double sinLatitude1 = Math.sin(latitude1);
        double cosLatitude1 = Math.cos(latitude1);
        double sinAngularDistance = Math.sin(angularDistance);
        double cosAngularDistance = Math.cos(angularDistance);
        double latitude2 = Math.asin(sinLatitude1 * cosAngularDistance
                + cosLatitude1 * sinAngularDistance * Math.cos(initialBearingRadians));
        double longitude2 = longitude1 + Math.atan2(
                Math.sin(initialBearingRadians) * sinAngularDistance * cosLatitude1,
                cosAngularDistance - sinLatitude1 * Math.sin(latitude2));
        return new GeodeticPoint(
                Math.toDegrees(latitude2),
                Math.toDegrees(longitude2),
                altitudeMeters);
    }

    public record GeodesicData(
            double distanceMeters,
            double initialBearingRadians,
            boolean vincentyConverged) {
    }
}
