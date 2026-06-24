package com.targettracker.tracking;

import com.targettracker.model.TargetMeasurement;

/** The 6D ECEF measurement mean/covariance associated with a track update. */
public record AssociatedMeasurement(
        String targetId,
        double[] mean,
        double[][] covariance) {
    private static final int SPATIAL_DIMENSIONS = 3;
    private static final int MEASUREMENT_DERIVATIVE_COUNT = 2;
    private static final int POSITION_OFFSET = 0;
    private static final int VELOCITY_OFFSET = POSITION_OFFSET + SPATIAL_DIMENSIONS;
    private static final int SIZE = SPATIAL_DIMENSIONS * MEASUREMENT_DERIVATIVE_COUNT;

    public AssociatedMeasurement {
        if (targetId == null) {
            targetId = "";
        }
        if (mean == null || mean.length != SIZE) {
            throw new IllegalArgumentException("Measurement mean must contain 6 elements");
        }
        mean = mean.clone();
        covariance = copyCovariance(covariance);
    }

    public static AssociatedMeasurement from(TargetMeasurement measurement) {
        double[] mean = {
                measurement.measuredPosition().x(),
                measurement.measuredPosition().y(),
                measurement.measuredPosition().z(),
                measurement.measuredVelocity().x(),
                measurement.measuredVelocity().y(),
                measurement.measuredVelocity().z()
        };
        double[][] covariance = new double[SIZE][SIZE];
        for (int axis = 0; axis < SPATIAL_DIMENSIONS; axis++) {
            covariance[POSITION_OFFSET + axis][POSITION_OFFSET + axis] =
                    measurement.positionVarianceMetersSquared();
            covariance[VELOCITY_OFFSET + axis][VELOCITY_OFFSET + axis] =
                    measurement.velocityVarianceMetersSquaredPerSecondSquared();
        }
        return new AssociatedMeasurement(measurement.targetId(), mean, covariance);
    }

    @Override
    public double[] mean() {
        return mean.clone();
    }

    @Override
    public double[][] covariance() {
        return copyCovariance(covariance);
    }

    private static double[][] copyCovariance(double[][] source) {
        if (source == null || source.length != SIZE) {
            throw new IllegalArgumentException("Measurement covariance must be 6 by 6");
        }
        double[][] copy = new double[SIZE][SIZE];
        for (int row = 0; row < SIZE; row++) {
            if (source[row] == null || source[row].length != SIZE) {
                throw new IllegalArgumentException("Measurement covariance must be 6 by 6");
            }
            System.arraycopy(source[row], 0, copy[row], 0, SIZE);
        }
        return copy;
    }
}
