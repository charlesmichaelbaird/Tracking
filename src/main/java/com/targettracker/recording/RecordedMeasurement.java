package com.targettracker.recording;

/** One sensor measurement plus its optional persisted track association. */
public record RecordedMeasurement(
        String sensorId,
        String targetId,
        String associatedTrackId,
        double timeSeconds,
        double[] mean,
        double[][] covariance,
        double positionUncertaintyMeters,
        double velocityUncertaintyMetersPerSecond) {
    private static final int SIZE = 6;

    public RecordedMeasurement {
        if (sensorId == null || sensorId.isBlank()) {
            throw new IllegalArgumentException("Sensor ID is required");
        }
        if (targetId == null) {
            targetId = "";
        }
        if (associatedTrackId == null) {
            associatedTrackId = "";
        }
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("Measurement time must be finite and non-negative");
        }
        if (mean == null || mean.length != SIZE) {
            throw new IllegalArgumentException("Measurement mean must contain 6 elements");
        }
        mean = mean.clone();
        covariance = copyCovariance(covariance);
        if (!Double.isFinite(positionUncertaintyMeters) || positionUncertaintyMeters < 0.0
                || !Double.isFinite(velocityUncertaintyMetersPerSecond)
                || velocityUncertaintyMetersPerSecond < 0.0) {
            throw new IllegalArgumentException("Measurement uncertainty must be non-negative");
        }
    }

    public RecordedMeasurement(
            String sensorId,
            String targetId,
            double timeSeconds,
            double[] mean,
            double[][] covariance,
            double positionUncertaintyMeters,
            double velocityUncertaintyMetersPerSecond) {
        this(sensorId, targetId, "", timeSeconds, mean, covariance,
                positionUncertaintyMeters, velocityUncertaintyMetersPerSecond);
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
