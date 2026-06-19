package com.targettracker.model;

/**
 * Defines the local tangent ENU frame used by the editor. Geographic origin
 * metadata is retained so a later version can convert ENU points to geodetic coordinates.
 */
public record EnuFrame(
        String name,
        double originLatitudeDegrees,
        double originLongitudeDegrees,
        double originAltitudeMeters,
        double minEastMeters,
        double maxEastMeters,
        double minNorthMeters,
        double maxNorthMeters) {

    public EnuFrame {
        if (maxEastMeters <= minEastMeters || maxNorthMeters <= minNorthMeters) {
            throw new IllegalArgumentException("ENU frame extents must have positive area");
        }
    }

    public static EnuFrame defaultFrame() {
        return new EnuFrame(
                "Local scenario frame",
                0.0,
                0.0,
                0.0,
                -5_000.0,
                5_000.0,
                -5_000.0,
                5_000.0);
    }
}
