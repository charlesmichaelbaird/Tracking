package com.targettracker.tracking;

/**
 * Immutable snapshot of a fused 9D track state at one filter event.
 * State order is ECEF [x, y, z, vx, vy, vz, ax, ay, az].
 */
public record TrackRecord(
        String trackId,
        double timeSeconds,
        double[] state,
        double[][] covariance,
        boolean updated) {
    private static final int STATE_SIZE = 9;

    public TrackRecord {
        if (trackId == null || trackId.isBlank()) {
            throw new IllegalArgumentException("Track ID is required");
        }
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("Track time must be finite and non-negative");
        }
        if (state == null || state.length != STATE_SIZE) {
            throw new IllegalArgumentException("Track state must contain 9 elements");
        }
        if (covariance == null || covariance.length != STATE_SIZE) {
            throw new IllegalArgumentException("Track covariance must be 9 by 9");
        }
        state = state.clone();
        covariance = copyCovariance(covariance);
    }

    @Override
    public double[] state() {
        return state.clone();
    }

    @Override
    public double[][] covariance() {
        return copyCovariance(covariance);
    }

    private static double[][] copyCovariance(double[][] source) {
        double[][] copy = new double[STATE_SIZE][STATE_SIZE];
        for (int row = 0; row < STATE_SIZE; row++) {
            if (source[row] == null || source[row].length != STATE_SIZE) {
                throw new IllegalArgumentException("Track covariance must be 9 by 9");
            }
            System.arraycopy(source[row], 0, copy[row], 0, STATE_SIZE);
        }
        return copy;
    }
}
