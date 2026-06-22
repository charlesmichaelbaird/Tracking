package com.targettracker.model;

/** A vector expressed in the Earth-Centered, Earth-Fixed frame. */
public record EcefVector(double x, double y, double z) {
    public static final EcefVector ZERO = new EcefVector(0.0, 0.0, 0.0);

    public double magnitude() {
        return Math.sqrt(x * x + y * y + z * z);
    }
}
