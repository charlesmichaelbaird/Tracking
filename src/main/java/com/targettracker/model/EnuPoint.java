package com.targettracker.model;

/** A point in a local East-North-Up coordinate system, expressed in meters. */
public record EnuPoint(double east, double north, double up) {
    public double horizontalDistanceTo(EnuPoint other) {
        return Math.hypot(other.east - east, other.north - north);
    }

    public EnuPoint withUp(double newUp) {
        return new EnuPoint(east, north, newUp);
    }

    public static EnuPoint interpolate(EnuPoint a, EnuPoint b, double fraction) {
        double t = Math.max(0.0, Math.min(1.0, fraction));
        return new EnuPoint(
                a.east + (b.east - a.east) * t,
                a.north + (b.north - a.north) * t,
                a.up + (b.up - a.up) * t);
    }
}
