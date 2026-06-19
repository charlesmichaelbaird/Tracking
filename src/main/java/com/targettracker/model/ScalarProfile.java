package com.targettracker.model;

import java.util.Arrays;

/** A sampled scalar value over normalized scenario time [0, 1]. */
public final class ScalarProfile {
    public static final int SAMPLE_COUNT = 101;

    private final double minimum;
    private final double maximum;
    private final double[] samples = new double[SAMPLE_COUNT];

    public ScalarProfile(double minimum, double maximum, double initialValue) {
        if (maximum <= minimum) {
            throw new IllegalArgumentException("Profile maximum must exceed minimum");
        }
        this.minimum = minimum;
        this.maximum = maximum;
        Arrays.fill(samples, clamp(initialValue));
    }

    public double minimum() {
        return minimum;
    }

    public double maximum() {
        return maximum;
    }

    public int sampleCount() {
        return samples.length;
    }

    public double sample(int index) {
        return samples[Math.max(0, Math.min(samples.length - 1, index))];
    }

    public void setSample(int index, double value) {
        samples[Math.max(0, Math.min(samples.length - 1, index))] = clamp(value);
    }

    public void setBetween(int firstIndex, double firstValue, int lastIndex, double lastValue) {
        int from = Math.max(0, Math.min(samples.length - 1, firstIndex));
        int to = Math.max(0, Math.min(samples.length - 1, lastIndex));
        if (from > to) {
            int tempIndex = from;
            from = to;
            to = tempIndex;
            double tempValue = firstValue;
            firstValue = lastValue;
            lastValue = tempValue;
        }

        int span = Math.max(1, to - from);
        for (int i = from; i <= to; i++) {
            double fraction = (double) (i - from) / span;
            setSample(i, firstValue + (lastValue - firstValue) * fraction);
        }
    }

    public double valueAt(double normalizedTime) {
        double position = Math.max(0.0, Math.min(1.0, normalizedTime)) * (samples.length - 1);
        int lower = (int) Math.floor(position);
        int upper = Math.min(samples.length - 1, lower + 1);
        double fraction = position - lower;
        return samples[lower] + (samples[upper] - samples[lower]) * fraction;
    }

    public double average() {
        double sum = 0.0;
        for (double sample : samples) {
            sum += sample;
        }
        return sum / samples.length;
    }

    /**
     * Converts normalized time into normalized distance by integrating this profile.
     * This makes faster portions of a velocity profile cover proportionally more path.
     */
    public double normalizedIntegralAt(double normalizedTime) {
        double t = Math.max(0.0, Math.min(1.0, normalizedTime));
        double position = t * (samples.length - 1);
        int completeSegments = (int) Math.floor(position);
        double partial = position - completeSegments;

        double total = 0.0;
        double elapsed = 0.0;
        for (int i = 0; i < samples.length - 1; i++) {
            double area = (samples[i] + samples[i + 1]) * 0.5;
            total += area;
            if (i < completeSegments) {
                elapsed += area;
            } else if (i == completeSegments && partial > 0.0) {
                double partialEnd = samples[i] + (samples[i + 1] - samples[i]) * partial;
                elapsed += (samples[i] + partialEnd) * 0.5 * partial;
            }
        }
        return total <= 1.0e-9 ? t : Math.max(0.0, Math.min(1.0, elapsed / total));
    }

    private double clamp(double value) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
