package com.targettracker.model;

/** A noisy target measurement expressed entirely in the ECEF frame. */
public record TargetMeasurement(
        String targetId,
        double timeSeconds,
        EcefPoint measuredPosition,
        EcefVector measuredVelocity,
        double positionVarianceMetersSquared,
        double velocityVarianceMetersSquaredPerSecondSquared) {
}
