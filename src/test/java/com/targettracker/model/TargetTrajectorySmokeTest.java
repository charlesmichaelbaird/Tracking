package com.targettracker.model;

import java.awt.Color;

/** Lightweight model checks that run without a third-party test framework. */
public final class TargetTrajectorySmokeTest {
    private static final double TOLERANCE = 1.0e-6;

    private TargetTrajectorySmokeTest() {
    }

    public static void main(String[] args) {
        TargetTrajectory target = new TargetTrajectory("TEST-001", Color.BLUE);
        target.addPathPoint(new EnuPoint(0.0, 0.0, 0.0));
        target.addPathPoint(new EnuPoint(1_000.0, 0.0, 0.0));

        requireClose(1_000.0, target.horizontalLengthMeters(), "path length");
        requireClose(5.0, target.durationSeconds(), "duration at 200 m/s");

        EnuPoint midpoint = target.positionAt(2.5);
        requireClose(500.0, midpoint.east(), "midpoint east");
        requireClose(0.0, midpoint.north(), "midpoint north");
        requireClose(1_000.0, midpoint.up(), "default altitude");

        for (int i = 0; i < target.altitudeProfile().sampleCount(); i++) {
            target.altitudeProfile().setSample(i, 2_500.0);
        }
        requireClose(2_500.0, target.positionAt(2.5).up(), "edited altitude");

        ScalarProfile increasingSpeed = new ScalarProfile(0.0, 500.0, 0.0);
        for (int i = 0; i < increasingSpeed.sampleCount(); i++) {
            increasingSpeed.setSample(i, i * 3.0);
        }
        if (increasingSpeed.normalizedIntegralAt(0.5) >= 0.5) {
            throw new AssertionError("A rising speed profile should cover less than half the distance by half time");
        }

        System.out.println("TargetTrajectorySmokeTest passed");
    }

    private static void requireClose(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > TOLERANCE) {
            throw new AssertionError("%s: expected %f but got %f".formatted(label, expected, actual));
        }
    }
}
