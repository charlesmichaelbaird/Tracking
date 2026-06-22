package com.targettracker.model;

/** Earth-Centered, Earth-Fixed Cartesian position in meters (WGS-84). */
public record EcefPoint(double x, double y, double z) {
    public double distanceTo(EcefPoint other) {
        double dx = other.x - x;
        double dy = other.y - y;
        double dz = other.z - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double magnitude() {
        return Math.sqrt(x * x + y * y + z * z);
    }
}
