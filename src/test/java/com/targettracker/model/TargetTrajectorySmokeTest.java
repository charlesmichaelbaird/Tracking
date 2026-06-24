package com.targettracker.model;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;

/** Lightweight model checks that run without a third-party test framework. */
public final class TargetTrajectorySmokeTest {
    private static final double TOLERANCE = 1.0e-6;

    private TargetTrajectorySmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        TargetTrajectory target = new TargetTrajectory("TEST-001", Color.BLUE);
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(0.0, 0.0, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(0.0, 1.0, 0.0)));

        double expectedEquatorialDegree = Math.PI * Wgs84.SEMI_MAJOR_AXIS_METERS / 180.0;
        requireClose(expectedEquatorialDegree, target.surfaceLengthMeters(), 0.001, "surface path length");
        requireClose(expectedEquatorialDegree / 200.0, target.durationSeconds(), 0.001,
                "duration at 200 m/s");

        EcefPoint midpointEcef = target.positionAt(target.durationSeconds() / 2.0);
        GeodeticPoint midpoint = Wgs84.toGeodetic(midpointEcef);
        requireClose(0.0, midpoint.latitudeDegrees(), 1.0e-9, "midpoint latitude");
        requireClose(0.5, midpoint.longitudeDegrees(), 1.0e-9, "midpoint longitude");
        requireClose(1_000.0, midpoint.altitudeMeters(), 1.0e-6, "default altitude");

        for (int i = 0; i < target.altitudeProfile().sampleCount(); i++) {
            target.altitudeProfile().setSample(i, 2_500.0);
        }
        GeodeticPoint raisedMidpoint = Wgs84.toGeodetic(
                target.positionAt(target.durationSeconds() / 2.0));
        requireClose(2_500.0, raisedMidpoint.altitudeMeters(), 1.0e-6, "edited altitude");

        GeodeticPoint[] roundTripInputs = {
                new GeodeticPoint(37.7749, -122.4194, 12_345.67),
                new GeodeticPoint(-33.8688, 151.2093, -25.0),
                new GeodeticPoint(89.999, 45.0, 500.0),
                new GeodeticPoint(0.0, 179.999, 1_000_000.0)
        };
        for (GeodeticPoint roundTripInput : roundTripInputs) {
            GeodeticPoint roundTripOutput = Wgs84.toGeodetic(Wgs84.toEcef(roundTripInput));
            requireClose(roundTripInput.latitudeDegrees(), roundTripOutput.latitudeDegrees(),
                    1.0e-9, "round-trip latitude");
            requireClose(roundTripInput.longitudeDegrees(), roundTripOutput.longitudeDegrees(),
                    1.0e-9, "round-trip longitude");
            requireClose(roundTripInput.altitudeMeters(), roundTripOutput.altitudeMeters(),
                    1.0e-5, "round-trip altitude");
        }
        requireClose(111_319.490_793, Wgs84.metersPerDegreeLongitude(0.0),
                0.001, "equatorial longitude scale");
        requireClose(110_574.275_822, Wgs84.metersPerDegreeLatitude(0.0),
                0.001, "equatorial latitude scale");

        TargetTrajectory curved = new TargetTrajectory("TEST-002", Color.RED);
        curved.addPathPoint(Wgs84.toEcef(new GeodeticPoint(60.0, -20.0, 0.0)));
        curved.addPathPoint(Wgs84.toEcef(new GeodeticPoint(60.0, 20.0, 0.0)));
        for (int i = 0; i < curved.altitudeProfile().sampleCount(); i++) {
            curved.altitudeProfile().setSample(i, 12_000.0);
        }
        GeodeticPoint curvedMidpoint = Wgs84.toGeodetic(
                curved.positionAt(curved.durationSeconds() / 2.0));
        if (curvedMidpoint.latitudeDegrees() <= 60.0) {
            throw new AssertionError("Ellipsoidal geodesic should arc poleward at the midpoint");
        }
        requireClose(12_000.0, curvedMidpoint.altitudeMeters(), 1.0e-5,
                "geodesic midpoint altitude");

        for (int i = 0; i < curved.altitudeProfile().sampleCount(); i++) {
            curved.altitudeProfile().setSample(i, 500.0 + i * 100.0);
        }
        for (int i = 0; i < curved.altitudeProfile().sampleCount(); i++) {
            double elapsed = curved.durationSeconds() * i
                    / (curved.altitudeProfile().sampleCount() - 1.0);
            double actualAltitude = Wgs84.toGeodetic(curved.positionAt(elapsed)).altitudeMeters();
            requireClose(curved.altitudeProfile().sample(i), actualAltitude, 1.0e-5,
                    "sampled profile altitude " + i);
        }

        GeodeticPoint nearAntipodal = Wgs84Geodesic.interpolate(
                new GeodeticPoint(0.0, 0.0, 0.0),
                new GeodeticPoint(0.1, 179.9, 0.0),
                0.5,
                7_500.0);
        requireClose(7_500.0, Wgs84.toGeodetic(Wgs84.toEcef(nearAntipodal)).altitudeMeters(),
                1.0e-5, "near-antipodal fallback altitude");

        ScalarProfile increasingSpeed = new ScalarProfile(0.0, 500.0, 0.0);
        for (int i = 0; i < increasingSpeed.sampleCount(); i++) {
            increasingSpeed.setSample(i, i * 3.0);
        }
        if (increasingSpeed.normalizedIntegralAt(0.5) >= 0.5) {
            throw new AssertionError("A rising speed profile should cover less than half the distance by half time");
        }
        verifyPathEditingUtilities();

        BufferedImage earthMap = ImageIO.read(TargetTrajectorySmokeTest.class
                .getResource("/maps/blue_marble_2048.png"));
        if (earthMap == null || earthMap.getWidth() != 2_048 || earthMap.getHeight() != 1_024) {
            throw new AssertionError("Bundled Earth map must be a readable 2048×1024 raster");
        }

        System.out.println("TargetTrajectorySmokeTest passed");
    }

    private static void verifyPathEditingUtilities() {
        TargetTrajectory target = new TargetTrajectory("TEST-003", Color.GREEN);
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.00, -74.00, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.01, -73.99, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.00, -73.98, 0.0)));
        int originalCount = target.path().size();
        double originalLength = target.surfaceLengthMeters();
        if (!target.smoothPath() || target.path().size() <= originalCount) {
            throw new AssertionError("Smoothing should insert additional path support points");
        }
        if (!target.canUndoSmoothing() || !target.undoSmoothing()) {
            throw new AssertionError("Smoothing should be undoable");
        }
        if (target.path().size() != originalCount
                || Math.abs(target.surfaceLengthMeters() - originalLength) > 1.0e-6) {
            throw new AssertionError("Undo smoothing should restore the original path");
        }
        GeodeticPoint firstBefore = Wgs84.toGeodetic(target.path().get(0));
        if (!target.translatePath(
                new GeodeticPoint(40.00, -74.00, 0.0),
                new GeodeticPoint(40.00, -73.99, 0.0))) {
            throw new AssertionError("Path translation should succeed for a non-zero drag");
        }
        GeodeticPoint firstAfter = Wgs84.toGeodetic(target.path().get(0));
        if (Math.abs(firstAfter.longitudeDegrees() - firstBefore.longitudeDegrees()) < 0.005) {
            throw new AssertionError("Path translation should move the target path geodetically");
        }
    }

    private static void requireClose(double expected, double actual, String label) {
        requireClose(expected, actual, TOLERANCE, label);
    }

    private static void requireClose(double expected, double actual, double tolerance, String label) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError("%s: expected %f but got %f".formatted(label, expected, actual));
        }
    }
}
