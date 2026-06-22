package com.targettracker.tracking;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.EcefVector;
import com.targettracker.model.TargetMeasurement;

import java.util.List;

/** Deterministic checks for DCWNA/white-jerk prediction, association, and track breaks. */
public final class ImmTrackerSmokeTest {
    private static final double TOLERANCE = 1.0e-6;

    private ImmTrackerSmokeTest() {
    }

    public static void main(String[] args) {
        verifyCvProcessNoise();
        verifyCaProcessNoise();
        verifyAssociationCoastingAndTimeout();
        verifyGreedyOneToOneAssociation();
        verifyUpdateRecordsExcludeCoasts();
        verifyFullLifeTail();
        verifyUncertaintyBreak();
        verifySquareTransitionValidation();
        System.out.println("ImmTrackerSmokeTest passed");
    }

    private static void verifyCvProcessNoise() {
        ImmSettings settings = new ImmSettings();
        settings.setParameters(parameters(List.of(ImmModel.CV), 2.0, 0.25, 1_000.0));
        ImmTracker tracker = new ImmTracker(settings);
        tracker.processMeasurements(List.of(measurement(0.0, 0.0, 0.0)));
        tracker.advanceTo(1.0);
        double expectedPositionVariance = 1.0 + 1.0 + 2.0 / 3.0;
        requireClose(expectedPositionVariance,
                tracker.currentViews().get(0).positionCovariance()[0][0],
                "CV DCWNA position variance");
    }

    private static void verifyCaProcessNoise() {
        ImmSettings settings = new ImmSettings();
        settings.setParameters(parameters(List.of(ImmModel.CA), 2.0, 0.25, 1_000.0));
        ImmTracker tracker = new ImmTracker(settings);
        tracker.processMeasurements(List.of(measurement(0.0, 0.0, 0.0)));
        tracker.advanceTo(1.0);
        double expectedPositionVariance = 1.0 + 1.0 + 0.25 * 100.0 + 0.25 / 20.0;
        requireClose(expectedPositionVariance,
                tracker.currentViews().get(0).positionCovariance()[0][0],
                "CA continuous white-jerk position variance");
    }

    private static void verifyAssociationCoastingAndTimeout() {
        ImmSettings settings = new ImmSettings();
        settings.setParameters(new ImmParameters(
                List.of(ImmModel.CV, ImmModel.CA),
                1.0,
                0.1,
                20.0,
                1_000.0,
                1.0e9,
                new double[][]{{0.95, 0.05}, {0.05, 0.95}}));
        ImmTracker tracker = new ImmTracker(settings);
        tracker.processMeasurements(List.of(measurement(0.0, 0.0, 100.0)));
        tracker.processMeasurements(List.of(measurement(10.0, 1_000.0, 100.0)));
        tracker.advanceTo(10.0);
        if (tracker.currentViews().size() != 1
                || !"TRK-001".equals(tracker.currentViews().get(0).id())) {
            throw new AssertionError("Greedy association should retain one track for compatible looks");
        }

        tracker.advanceTo(20.0);
        TrackView firstCoast = tracker.currentViews().get(0);
        tracker.advanceTo(20.0);
        TrackView repeatedCoast = tracker.currentViews().get(0);
        requireClose(firstCoast.meanPosition().x(), repeatedCoast.meanPosition().x(),
                "coast mean must not compound");
        requireClose(firstCoast.positionCovariance()[0][0],
                repeatedCoast.positionCovariance()[0][0],
                "coast covariance must not compound");

        settings.setParameters(new ImmParameters(
                List.of(ImmModel.CV, ImmModel.CA),
                1.0,
                0.1,
                20.0,
                5.0,
                1.0e9,
                new double[][]{{0.95, 0.05}, {0.05, 0.95}}));
        tracker.parametersChanged(20.0);
        if (tracker.currentViews().size() != 1 || !tracker.currentViews().get(0).dead()) {
            throw new AssertionError("Timed-out track should leave association and render as dead");
        }
    }

    private static void verifyGreedyOneToOneAssociation() {
        ImmSettings settings = new ImmSettings();
        settings.setParameters(parameters(List.of(ImmModel.CV), 0.1, 0.1, 1_000.0));
        ImmTracker tracker = new ImmTracker(settings);
        tracker.processMeasurements(List.of(
                measurement(0.0, 0.0, 0.0),
                measurement(0.0, 10_000.0, 0.0)));
        tracker.processMeasurements(List.of(
                measurement(1.0, 10_000.0, 0.0),
                measurement(1.0, 0.0, 0.0)));
        tracker.advanceTo(1.0);
        if (tracker.currentViews().size() != 2) {
            throw new AssertionError("Greedy one-to-one association should preserve two tracks");
        }
    }

    private static void verifyUncertaintyBreak() {
        ImmSettings settings = new ImmSettings();
        settings.setParameters(new ImmParameters(
                List.of(ImmModel.CV),
                100.0,
                0.1,
                20.0,
                1_000.0,
                1.1,
                new double[][]{{1.0}}));
        ImmTracker tracker = new ImmTracker(settings);
        tracker.processMeasurements(List.of(measurement(0.0, 0.0, 0.0)));
        tracker.advanceTo(1.0);
        if (tracker.currentViews().size() != 1 || !tracker.currentViews().get(0).dead()) {
            throw new AssertionError("Uncertainty threshold should retire an expanding track");
        }
    }

    private static void verifyUpdateRecordsExcludeCoasts() {
        ImmSettings settings = new ImmSettings();
        settings.setParameters(parameters(List.of(ImmModel.CV), 0.1, 0.1, 1_000.0));
        ImmTracker tracker = new ImmTracker(settings);
        tracker.processMeasurements(List.of(measurement(0.0, 0.0, 10.0)));
        List<TrackRecord> initial = tracker.drainUpdatedRecords();
        if (initial.size() != 1 || initial.get(0).state().length != 9
                || initial.get(0).covariance().length != 9 || !initial.get(0).updated()) {
            throw new AssertionError("Track initialization should publish one complete update record");
        }

        tracker.advanceTo(5.0);
        if (!tracker.drainUpdatedRecords().isEmpty()) {
            throw new AssertionError("Coasting must not publish recording rows");
        }

        tracker.processMeasurements(List.of(measurement(5.0, 50.0, 10.0)));
        List<TrackRecord> update = tracker.drainUpdatedRecords();
        if (update.size() != 1 || update.get(0).timeSeconds() != 5.0) {
            throw new AssertionError("Each associated measurement should publish one update record");
        }
    }

    private static void verifyFullLifeTail() {
        ImmSettings settings = new ImmSettings();
        settings.setParameters(parameters(List.of(ImmModel.CV), 0.1, 0.1, 1_000.0));
        ImmTracker tracker = new ImmTracker(settings);
        tracker.processMeasurements(List.of(measurement(0.0, 0.0, 100.0)));
        for (int index = 0; index <= 250; index++) {
            tracker.advanceTo(index * 0.1);
        }
        if (tracker.currentViews().get(0).tail().size() <= 180) {
            throw new AssertionError("Track tail should retain the complete track lifetime");
        }
    }

    private static void verifySquareTransitionValidation() {
        try {
            new ImmParameters(
                    List.of(ImmModel.CV, ImmModel.CA),
                    1.0,
                    1.0,
                    5.0,
                    60.0,
                    1_000.0,
                    new double[][]{{1.0}});
            throw new AssertionError("Non-square transition matrix should be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected validation path.
        }
    }

    private static ImmParameters parameters(
            List<ImmModel> models,
            double cvNoise,
            double caNoise,
            double timeout) {
        return new ImmParameters(
                models,
                cvNoise,
                caNoise,
                20.0,
                timeout,
                1.0e9,
                new double[][]{{1.0}});
    }

    private static TargetMeasurement measurement(double time, double xOffset, double xVelocity) {
        return new TargetMeasurement(
                "truth",
                time,
                new EcefPoint(6_378_137.0 + xOffset, 0.0, 0.0),
                new EcefVector(xVelocity, 0.0, 0.0),
                1.0,
                1.0);
    }

    private static void requireClose(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > TOLERANCE) {
            throw new AssertionError("%s: expected %f but got %f".formatted(label, expected, actual));
        }
    }
}
