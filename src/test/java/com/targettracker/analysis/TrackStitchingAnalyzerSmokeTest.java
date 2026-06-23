package com.targettracker.analysis;

import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.tracking.TrackRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Deterministic check for stitching event discovery, timing, and NLL costs. */
public final class TrackStitchingAnalyzerSmokeTest {
    private TrackStitchingAnalyzerSmokeTest() {
    }

    public static void main(String[] args) {
        verifyCanonicalInnovationMetrics();
        verifyBackwardPropagationAndSmoothing();

        List<TrackRecord> tracks = new ArrayList<>();
        tracks.add(track("TRK-001", 0.0, 0.0, 8.0, true));
        for (int second = 1; second <= 5; second++) {
            tracks.add(track("TRK-001", second, 8.0 * second, 8.0, false));
        }
        tracks.add(track("TRK-002", 5.0, 50.0, 12.0, true));

        List<GroundTruthRecord> truth = new ArrayList<>();
        for (int halfSecond = 0; halfSecond <= 10; halfSecond++) {
            double time = halfSecond / 2.0;
            truth.add(new GroundTruthRecord(
                    "TGT-001",
                    time,
                    new double[]{10.0 * time, 0, 0, 10, 0, 0, 0, 0, 0}));
        }
        RecordedScenario scenario = new RecordedScenario(
                Path.of("stitching_test"),
                "Stitching test",
                5.0,
                tracks,
                truth,
                List.of(measurement(5.0)));
        TrackStitchingAnalyzer analyzer = new TrackStitchingAnalyzer();
        List<TrackStitchingAnalyzer.EventResult> events = analyzer.analyze(
                scenario,
                new TrackStitchingAnalyzer.Configuration(1.0, 10.0, 0.0, 1.0, false, 0.5));
        if (events.size() != 1
                || events.get(0).oldSegments().size() != 1
                || events.get(0).newSegments().size() != 1
                || events.get(0).pairs().size() != 1) {
            throw new AssertionError("Expected one old/new stitching candidate at t=5");
        }
        TrackStitchingAnalyzer.PairResult pair = events.get(0).pairs().get(0);
        requireClose(2.5, pair.simpleJoinTimeSeconds(), "simple midpoint");
        requireClose(2.5, pair.kinematicJoinTimeSeconds(), "kinematic midpoint");
        if (!Double.isFinite(pair.statisticalJoinTimeSeconds())
                || pair.statisticalJoinTimeSeconds() < 0.0
                || pair.statisticalJoinTimeSeconds() > 5.0) {
            throw new AssertionError("Mahalanobis bank time should remain inside the join interval");
        }
        requireClose(2.25, pair.actualJoinTimeSeconds(), "truth RMS bank midpoint");
        if (!pair.truthTargetId().equals("TGT-001")
                || !Double.isFinite(pair.statisticalNegativeLogLikelihood())) {
            throw new AssertionError("Truth identity and NLL cost should be available");
        }
        if (events.get(0).nllAssignments().size() != 1
                || events.get(0).mahalanobisAssignments().size() != 1
                || !events.get(0).nllAssignments().get(0).oldTrackId().equals("TRK-001")
                || !events.get(0).nllAssignments().get(0).newTrackId().equals("TRK-002")) {
            throw new AssertionError("Expected Hungarian optimum for the single feasible pair");
        }
        if (events.get(0).nllAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).mahalanobisAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).staticNllrAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).learnedNllrAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))) {
            throw new AssertionError("Hungarian optima must not use truth-only timing variants");
        }
        if (events.get(0).staticNllrAssignments().size() != 1
                || events.get(0).learnedNllrAssignments().size() != 1
                || !Double.isFinite(pair.simpleStaticNegativeLogLikelihoodRatio())
                || !Double.isFinite(pair.simpleLearnedNegativeLogLikelihoodRatio())
                || !Double.isFinite(events.get(0)
                .learnedBirthDensityPerCubicKilometer())) {
            throw new AssertionError("Alternative-hypothesis NLLR outputs should be available");
        }

        List<TrackRecord> deadTrackRecords = tracks.stream()
                .filter(record -> !record.trackId().equals("TRK-001")
                        || record.timeSeconds() <= 2.0)
                .toList();
        RecordedScenario deadTrackScenario = new RecordedScenario(
                Path.of("dead_stitching_test"), "Dead stitching test", 5.0,
                deadTrackRecords, truth, List.of(measurement(5.0)));
        if (!analyzer.analyze(deadTrackScenario,
                new TrackStitchingAnalyzer.Configuration(
                        1.0, 10.0, 0.0, 1.0, false, 0.5)).isEmpty()) {
            throw new AssertionError("Dead old tracks should be excluded by default");
        }
        if (analyzer.analyze(deadTrackScenario,
                new TrackStitchingAnalyzer.Configuration(
                        1.0, 10.0, 0.0, 1.0, true, 0.5)).size() != 1) {
            throw new AssertionError("Dead old tracks should be eligible when enabled");
        }
        verifyEventLimitedJoinSeeds(analyzer);
        verifyDeadTrackJoinSeeds(analyzer);
        System.out.println("TrackStitchingAnalyzerSmokeTest passed");
    }

    private static void verifyEventLimitedJoinSeeds(TrackStitchingAnalyzer analyzer) {
        List<TrackRecord> tracks = new ArrayList<>();
        tracks.add(track("TRK-OLD", 0.0, 0.0, 10.0, true));
        for (int second = 1; second <= 5; second++) {
            tracks.add(track("TRK-OLD", second, 10.0 * second, 10.0, false));
        }
        tracks.add(track("TRK-NEW", 3.0, 30.0, 10.0, true));
        tracks.add(track("TRK-NEW", 4.0, 40.0, 10.0, false));
        tracks.add(track("TRK-NEW", 5.0, 50.0, 10.0, false));
        List<GroundTruthRecord> truth = new ArrayList<>();
        for (int second = 0; second <= 5; second++) {
            truth.add(new GroundTruthRecord(
                    "TGT-001", second,
                    new double[]{10.0 * second, 0, 0, 10, 0, 0, 0, 0, 0}));
        }
        RecordedScenario scenario = new RecordedScenario(
                Path.of("event_limited_stitching_test"),
                "Event-limited stitching test",
                5.0,
                tracks,
                truth,
                List.of(
                        measurement("TRK-NEW", 4.0, 40.0),
                        measurement("TRK-NEW", 5.0, 50.0)));
        List<TrackStitchingAnalyzer.EventResult> events = analyzer.analyze(
                scenario,
                new TrackStitchingAnalyzer.Configuration(1.0, 10.0, 0.0, 10.0, false, 0.5));
        if (events.size() != 2) {
            throw new AssertionError("Expected two candidate snapshots");
        }
        TrackStitchingAnalyzer.PairResult firstPair = pair(events.get(0), "TRK-OLD", "TRK-NEW");
        TrackStitchingAnalyzer.PairResult secondPair = pair(events.get(1), "TRK-OLD", "TRK-NEW");
        double firstSimple = firstPair.simpleJoinTimeSeconds();
        double secondSimple = secondPair.simpleJoinTimeSeconds();
        requireClose(2.0, firstSimple, "first event-limited midpoint");
        requireClose(2.5, secondSimple, "second event-limited midpoint");
        if (!Double.isFinite(secondPair.statisticalMahalanobisDistance())) {
            throw new AssertionError("Mahalanobis distance should be reported for each timing row");
        }
    }

    private static void verifyDeadTrackJoinSeeds(TrackStitchingAnalyzer analyzer) {
        List<TrackRecord> tracks = new ArrayList<>();
        tracks.add(track("TRK-OLD", 0.0, 0.0, 10.0, true));
        tracks.add(track("TRK-OLD", 1.0, 10.0, 10.0, false));
        tracks.add(track("TRK-OLD", 2.0, 20.0, 10.0, false));
        tracks.add(track("TRK-NEW", 3.0, 30.0, 10.0, true));
        tracks.add(track("TRK-NEW", 4.0, 40.0, 10.0, false));
        tracks.add(track("TRK-NEW", 5.0, 50.0, 10.0, false));

        List<GroundTruthRecord> truth = new ArrayList<>();
        for (int second = 0; second <= 5; second++) {
            truth.add(new GroundTruthRecord(
                    "TGT-001", second,
                    new double[]{10.0 * second, 0, 0, 10, 0, 0, 0, 0, 0}));
        }
        RecordedScenario scenario = new RecordedScenario(
                Path.of("dead_anchor_stitching_test"),
                "Dead-anchor stitching test",
                5.0,
                tracks,
                truth,
                List.of(measurement("TRK-NEW", 5.0, 50.0)));
        List<TrackStitchingAnalyzer.EventResult> events = analyzer.analyze(
                scenario,
                new TrackStitchingAnalyzer.Configuration(1.0, 10.0, 0.0, 3.0, true, 0.5));
        if (events.size() != 1 || events.get(0).oldSegments().stream()
                .noneMatch(TrackStitchingAnalyzer.Segment::deadAtEvent)) {
            throw new AssertionError("Dead track should be eligible for stitching when enabled");
        }
        TrackStitchingAnalyzer.PairResult pair = pair(events.get(0), "TRK-OLD", "TRK-NEW");
        requireClose(1.5, pair.simpleJoinTimeSeconds(), "dead-track midpoint seed");
        requireClose(1.5, pair.kinematicJoinTimeSeconds(), "dead-track kinematic seed");
        if (pair.statisticalJoinTimeSeconds() < 0.0
                || pair.statisticalJoinTimeSeconds() > 3.0) {
            throw new AssertionError(
                    "Dead-track Mahalanobis bank should stay before the new tracklet formation");
        }
    }

    private static TrackStitchingAnalyzer.PairResult pair(
            TrackStitchingAnalyzer.EventResult event,
            String oldTrackId,
            String newTrackId) {
        return event.pairs().stream()
                .filter(candidate -> candidate.oldTrackId().equals(oldTrackId)
                        && candidate.newTrackId().equals(newTrackId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing pair " + oldTrackId + " -> " + newTrackId));
    }

    private static void verifyCanonicalInnovationMetrics() {
        double[] oldState = new double[9];
        double[] newState = new double[9];
        newState[0] = 2.0;
        double[][] oldCovariance = diagonal(9, 1.0);
        double[][] newCovariance = diagonal(9, 1.0);
        TrackStitchingAnalyzer.InnovationScore score =
                TrackStitchingAnalyzer.innovationScore(
                        new TrackStitchingAnalyzer.PropagatedState(oldState, oldCovariance),
                        new TrackStitchingAnalyzer.PropagatedState(newState, newCovariance));
        requireClose(-2.0, score.innovation()[0], "innovation sign");
        requireClose(2.0, score.innovationCovariance()[0][0],
                "summed innovation covariance");
        requireClose(Math.sqrt(2.0), score.mahalanobisDistance(),
                "canonical Mahalanobis distance");
        double expectedNll = 0.5 * (9.0 * Math.log(2.0 * Math.PI)
                + 9.0 * Math.log(2.0) + 2.0);
        requireClose(expectedNll,
                TrackStitchingAnalyzer.canonicalNegativeLogLikelihood(score),
                "canonical Gaussian NLL");
    }

    private static void verifyBackwardPropagationAndSmoothing() {
        double[] sourceState = {100.0, 0, 0, 10.0, 0, 0, 2.0, 0, 0};
        TrackStitchingAnalyzer.PropagatedState source =
                new TrackStitchingAnalyzer.PropagatedState(sourceState, new double[9][9]);
        TrackStitchingAnalyzer.PropagatedState backward =
                TrackStitchingAnalyzer.propagate(source, 5.0, 3.0);
        TrackStitchingAnalyzer.PropagatedState forward =
                TrackStitchingAnalyzer.propagate(source, 5.0, 7.0);
        requireClose(84.0, backward.state()[0], "negative-dt position transition");
        requireClose(6.0, backward.state()[3], "negative-dt velocity transition");
        requireClose(124.0, forward.state()[0], "positive-dt position transition");
        for (int row = 0; row < 9; row++) {
            for (int column = 0; column < 9; column++) {
                requireClose(forward.covariance()[row][column],
                        backward.covariance()[row][column],
                        "positive covariance-growth interval");
            }
        }

        TrackRecord formation = track("TRK-NEW", 6.0, 60.0, 10.0, true);
        TrackRecord future = track("TRK-NEW", 10.0, 100.0, 10.0, true);
        TrackStitchingAnalyzer.Segment segment = new TrackStitchingAnalyzer.Segment(
                "TRK-NEW", 6.0, 10.0, 10.0,
                false, true, formation, future, future, future,
                List.of(formation, future));
        TrackStitchingAnalyzer.PropagatedState unsmoothed =
                TrackStitchingAnalyzer.retrodictNew(segment, List.of(), 4.0);
        requireClose(40.0, unsmoothed.state()[0],
                "retrodiction starts at most-future state and passes spawn time");

        double[][] measurementCovariance = diagonal(6, 0.01);
        RecordedMeasurement earlierMeasurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-001", "TRK-NEW", 8.0,
                new double[]{0, 0, 0, 0, 0, 0},
                measurementCovariance, 0.1, 0.1);
        TrackStitchingAnalyzer.PropagatedState smoothed =
                TrackStitchingAnalyzer.retrodictNew(
                        segment, List.of(earlierMeasurement), 4.0);
        if (Math.abs(smoothed.state()[0] - unsmoothed.state()[0]) < 1.0) {
            throw new AssertionError(
                    "Backward measurement update should influence retrodicted state");
        }
    }

    private static TrackRecord track(
            String id,
            double time,
            double x,
            double vx,
            boolean updated) {
        double[] state = {x, 0, 0, vx, 0, 0, 0, 0, 0};
        double[][] covariance = new double[9][9];
        for (int index = 0; index < 9; index++) {
            covariance[index][index] = 4.0;
        }
        return new TrackRecord(id, time, state, covariance, updated);
    }

    private static RecordedMeasurement measurement(double time) {
        double[][] covariance = diagonal(6, 1.0);
        return new RecordedMeasurement(
                "GOD-SENSOR-001",
                "TGT-001",
                time,
                new double[]{50, 0, 0, 12, 0, 0},
                covariance,
                1.0,
                1.0);
    }

    private static RecordedMeasurement measurement(String associatedTrackId, double time, double x) {
        double[][] covariance = diagonal(6, 1.0);
        return new RecordedMeasurement(
                "GOD-SENSOR-001",
                "TGT-001",
                associatedTrackId,
                time,
                new double[]{x, 0, 0, 10, 0, 0},
                covariance,
                1.0,
                1.0);
    }

    private static double[][] diagonal(int size, double value) {
        double[][] matrix = new double[size][size];
        for (int index = 0; index < size; index++) {
            matrix[index][index] = value;
        }
        return matrix;
    }

    private static void requireClose(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 1.0e-6) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }
}
