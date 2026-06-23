package com.targettracker.model;

import java.util.List;

/** A rectangular geodetic sensor blackout region defined in local east/north meters. */
public record BlackoutRegion(
        String name,
        GeodeticPoint center,
        double widthMeters,
        double heightMeters) {
    public BlackoutRegion {
        if (name == null || name.isBlank()) {
            name = "Blackout";
        }
        if (!Double.isFinite(widthMeters) || widthMeters <= 0.0
                || !Double.isFinite(heightMeters) || heightMeters <= 0.0) {
            throw new IllegalArgumentException("Blackout dimensions must be positive");
        }
        center = center.withAltitude(0.0);
    }

    public static BlackoutRegion fromLocal(
            String name,
            GeodeticPoint origin,
            double centerEastMeters,
            double centerNorthMeters,
            double widthMeters,
            double heightMeters) {
        double distance = Math.hypot(centerEastMeters, centerNorthMeters);
        double bearing = Math.atan2(centerEastMeters, centerNorthMeters);
        GeodeticPoint center = Wgs84Geodesic.direct(
                origin.withAltitude(0.0), bearing, distance, 0.0);
        return new BlackoutRegion(name, center, widthMeters, heightMeters);
    }

    public static BlackoutRegion fromOppositeCorners(
            String name,
            GeodeticPoint first,
            GeodeticPoint second) {
        double longitudeDelta = GeodeticPoint.normalizeLongitude(
                second.longitudeDegrees() - first.longitudeDegrees());
        double centerLatitude = (first.latitudeDegrees() + second.latitudeDegrees()) / 2.0;
        double centerLongitude = GeodeticPoint.normalizeLongitude(
                first.longitudeDegrees() + longitudeDelta / 2.0);
        GeodeticPoint center = new GeodeticPoint(centerLatitude, centerLongitude, 0.0);

        double halfLongitudeSpan = Math.abs(longitudeDelta) / 2.0;
        double southLatitude = Math.min(first.latitudeDegrees(), second.latitudeDegrees());
        double northLatitude = Math.max(first.latitudeDegrees(), second.latitudeDegrees());
        double width = Wgs84Geodesic.inverse(
                new GeodeticPoint(centerLatitude,
                        centerLongitude - halfLongitudeSpan, 0.0),
                new GeodeticPoint(centerLatitude,
                        centerLongitude + halfLongitudeSpan, 0.0))
                .distanceMeters();
        double height = Wgs84Geodesic.inverse(
                new GeodeticPoint(southLatitude, centerLongitude, 0.0),
                new GeodeticPoint(northLatitude, centerLongitude, 0.0))
                .distanceMeters();
        return new BlackoutRegion(name, center,
                Math.max(25.0, width),
                Math.max(25.0, height));
    }

    public boolean contains(EcefPoint point) {
        return contains(Wgs84.toGeodetic(point));
    }

    public boolean contains(GeodeticPoint point) {
        Wgs84Geodesic.GeodesicData offset = Wgs84Geodesic.inverse(center, point);
        double eastMeters = offset.distanceMeters() * Math.sin(offset.initialBearingRadians());
        double northMeters = offset.distanceMeters() * Math.cos(offset.initialBearingRadians());
        return Math.abs(eastMeters) <= widthMeters / 2.0
                && Math.abs(northMeters) <= heightMeters / 2.0;
    }

    public List<GeodeticPoint> corners() {
        return List.of(
                localCorner(-widthMeters / 2.0, -heightMeters / 2.0),
                localCorner(widthMeters / 2.0, -heightMeters / 2.0),
                localCorner(widthMeters / 2.0, heightMeters / 2.0),
                localCorner(-widthMeters / 2.0, heightMeters / 2.0));
    }

    private GeodeticPoint localCorner(double eastMeters, double northMeters) {
        double distance = Math.hypot(eastMeters, northMeters);
        double bearing = Math.atan2(eastMeters, northMeters);
        return Wgs84Geodesic.direct(center, bearing, distance, 0.0);
    }
}
