package com.targettracker.recording;

/** One target's 9D ECEF truth state at a scenario time. */
public record GroundTruthRecord(
        String targetId,
        double timeSeconds,
        double[] state) {
    private static final int STATE_SIZE = 9;

    public GroundTruthRecord {
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("Target ID is required");
        }
        if (!Double.isFinite(timeSeconds) || timeSeconds < 0.0) {
            throw new IllegalArgumentException("Truth time must be finite and non-negative");
        }
        if (state == null || state.length != STATE_SIZE) {
            throw new IllegalArgumentException("Ground-truth state must contain 9 elements");
        }
        state = state.clone();
    }

    @Override
    public double[] state() {
        return state.clone();
    }
}
