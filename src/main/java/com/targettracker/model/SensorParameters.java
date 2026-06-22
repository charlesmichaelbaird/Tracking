package com.targettracker.model;

/** Immutable parameters for the omniscient scenario measurement sensor. */
public record SensorParameters(
        double lookIntervalSeconds,
        double lookOffsetSeconds,
        double positionStandardDeviationMeters,
        double velocityStandardDeviationMetersPerSecond,
        double probabilityOfDetection,
        int previousMeasurementsToShow) {

    public SensorParameters {
        if (!Double.isFinite(lookIntervalSeconds) || lookIntervalSeconds <= 0.0) {
            throw new IllegalArgumentException("Look timing must be greater than zero");
        }
        if (!Double.isFinite(lookOffsetSeconds) || lookOffsetSeconds < 0.0) {
            throw new IllegalArgumentException("Look offset cannot be negative");
        }
        if (!Double.isFinite(positionStandardDeviationMeters)
                || positionStandardDeviationMeters < 0.0) {
            throw new IllegalArgumentException("Position standard deviation cannot be negative");
        }
        if (!Double.isFinite(velocityStandardDeviationMetersPerSecond)
                || velocityStandardDeviationMetersPerSecond < 0.0) {
            throw new IllegalArgumentException("Velocity standard deviation cannot be negative");
        }
        if (!Double.isFinite(probabilityOfDetection)
                || probabilityOfDetection < 0.0
                || probabilityOfDetection > 1.0) {
            throw new IllegalArgumentException("Probability of detection must be between zero and one");
        }
        if (previousMeasurementsToShow < 0 || previousMeasurementsToShow > 10) {
            throw new IllegalArgumentException("Measurement history must be between zero and ten");
        }
    }

    public static SensorParameters defaults() {
        return new SensorParameters(15.0, 5.0, 100.0, 10.0, 1.0, 5);
    }
}
