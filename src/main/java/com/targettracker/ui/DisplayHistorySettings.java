package com.targettracker.ui;

/** Shared world-view visibility and history-length settings. */
final class DisplayHistorySettings {
    private boolean gridVisible = true;
    private boolean groundTruthVisible = true;
    private boolean measurementsVisible = true;
    private double groundTruthHistoryFraction = 1.0;
    private double measurementHistoryFraction = 1.0;

    boolean gridVisible() {
        return gridVisible;
    }

    void setGridVisible(boolean visible) {
        gridVisible = visible;
    }

    boolean groundTruthVisible() {
        return groundTruthVisible;
    }

    void setGroundTruthVisible(boolean visible) {
        groundTruthVisible = visible;
    }

    boolean measurementsVisible() {
        return measurementsVisible;
    }

    void setMeasurementsVisible(boolean visible) {
        measurementsVisible = visible;
    }

    double groundTruthHistoryFraction() {
        return groundTruthHistoryFraction;
    }

    void setGroundTruthHistoryFraction(double fraction) {
        groundTruthHistoryFraction = clamp(fraction);
    }

    double measurementHistoryFraction() {
        return measurementHistoryFraction;
    }

    void setMeasurementHistoryFraction(double fraction) {
        measurementHistoryFraction = clamp(fraction);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
