package com.targettracker.analysis;

import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.model.EcefPoint;
import com.targettracker.model.EcefVector;
import com.targettracker.model.TargetMeasurement;
import com.targettracker.tracking.ImmSettings;
import com.targettracker.tracking.ImmTracker;
import com.targettracker.tracking.TrackRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Deterministic check for stitching event discovery, timing, and compatibility costs. */
public final class TrackStitchingAnalyzerSmokeTest {
    private TrackStitchingAnalyzerSmokeTest() {
    }

    public static void main(String[] args) {
        verifyCanonicalInnovationMetrics();
        verifyBackwardPropagationAndAnchors();
        verifyPhysicsAwareCovarianceScaleAffectsOnlyPhysicsAwareNll();
        verifyMidpointCovarianceMatchesTrackerScale();

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
        if (!Double.isFinite(pair.minimumNllTimeSeconds())
                || !Double.isFinite(pair.minimumNegativeLogLikelihood())
                || !Double.isFinite(pair.minimumNllBridgeNegativeLogLikelihoodRatio())
                || !Double.isFinite(pair.minimumNllUserVolumeNegativeLogLikelihoodRatio())
                || pair.minimumNllTimeSeconds() < 0.0
                || pair.minimumNllTimeSeconds() > 5.0) {
            throw new AssertionError("Minimum-NLL bank result should be available");
        }
        if (!Double.isFinite(pair.physicsAwareTimeSeconds())
                || !Double.isFinite(pair.physicsAwareVolume())
                || !Double.isFinite(pair.physicsAwareNegativeLogLikelihood())
                || !Double.isFinite(pair.physicsAwareOpportunityCost())
                || !Double.isFinite(pair.physicsAwareCost())
                || pair.physicsAwareTimeSeconds() < 0.0
                || pair.physicsAwareTimeSeconds() > 5.0) {
            throw new AssertionError("Physics-Aware bank result should be available");
        }
        if (!Double.isFinite(pair.simpleBhattacharyyaDistance())
                || !Double.isFinite(pair.simpleBhattacharyyaCoefficient())
                || !Double.isFinite(pair.simpleHellingerDistance())
                || pair.simpleBhattacharyyaCoefficient() < 0.0
                || pair.simpleBhattacharyyaCoefficient() > 1.0
                || pair.simpleHellingerDistance() < 0.0
                || pair.simpleHellingerDistance() > 1.0) {
            throw new AssertionError("Gaussian-overlap metrics should be available");
        }
        if (events.get(0).bhattacharyyaDistanceAssignments().size() != 1
                || events.get(0).bhattacharyyaCoefficientAssignments().size() != 1
                || events.get(0).hellingerDistanceAssignments().size() != 1
                || events.get(0).sixDimensionalBhattacharyyaDistanceAssignments().size() != 1
                || events.get(0)
                .sixDimensionalBhattacharyyaCoefficientAssignments().size() != 1
                || events.get(0).sixDimensionalHellingerDistanceAssignments().size() != 1) {
            throw new AssertionError("Expected Hungarian optima for Gaussian-overlap metrics");
        }
        if (events.get(0).nllAssignments().size() != 1
                || events.get(0).mahalanobisAssignments().size() != 1
                || events.get(0).minimumNllAssignments().size() != 1
                || !events.get(0).nllAssignments().get(0).oldTrackId().equals("TRK-001")
                || !events.get(0).nllAssignments().get(0).newTrackId().equals("TRK-002")) {
            throw new AssertionError("Expected Hungarian optimum for the single feasible pair");
        }
        if (events.get(0).bhattacharyyaDistanceAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).bhattacharyyaCoefficientAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).hellingerDistanceAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).sixDimensionalBhattacharyyaDistanceAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0)
                .sixDimensionalBhattacharyyaCoefficientAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).sixDimensionalHellingerDistanceAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).nllAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).mahalanobisAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).minimumNllAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).physicsAwareAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).bridgeNllrAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).userVolumeNllrAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).staticNllrAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))
                || events.get(0).learnedNllrAssignments().stream()
                .anyMatch(assignment -> assignment.variant().equals("Truth RMS"))) {
            throw new AssertionError("Hungarian optima must not use truth-only timing variants");
        }
        if (events.get(0).staticNllrAssignments().size() != 1
                || events.get(0).learnedNllrAssignments().size() != 1
                || events.get(0).physicsAwareAssignments().size() != 1
                || events.get(0).bridgeNllrAssignments().size() != 1
                || events.get(0).userVolumeNllrAssignments().size() != 1
                || !Double.isFinite(pair.simpleStaticNegativeLogLikelihoodRatio())
                || !Double.isFinite(pair.simpleLearnedNegativeLogLikelihoodRatio())
                || !Double.isFinite(pair.bridgeNegativeLogLikelihoodRatio())
                || !Double.isFinite(pair.userVolumeNegativeLogLikelihoodRatio())
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
        verifyOverlappingTrackletsRejected(analyzer);
        verifyEligibilityUsesTrackUpdateAges(analyzer);
        verifyLongGapBankIsCapped(analyzer);
        verifyClusteredBirthDensity(analyzer);
        verifyBirthDensityMaturesAfterNewTrackWindow(analyzer);
        System.out.println("TrackStitchingAnalyzerSmokeTest passed");
    }

    private static void verifyEligibilityUsesTrackUpdateAges(
            TrackStitchingAnalyzer analyzer) {
        List<TrackRecord> tracks = List.of(
                track("TRK-OLD", 2.0, 20.0, 10.0, true),
                track("TRK-OLD", 8.0, 80.0, 10.0, false),
                track("TRK-OLD", 10.0, 100.0, 10.0, false),
                track("TRK-NEW", 9.0, 90.0, 10.0, true),
                track("TRK-NEW", 10.0, 100.0, 10.0, true));
        List<GroundTruthRecord> truth = new ArrayList<>();
        for (int second = 0; second <= 10; second++) {
            truth.add(new GroundTruthRecord(
                    "TGT-BLACKOUT",
                    second,
                    new double[]{10.0 * second, 0, 0, 10, 0, 0, 0, 0, 0}));
        }
        RecordedScenario scenario = new RecordedScenario(
                Path.of("measurement_backed_age_windows"),
                "Measurement-backed age windows",
                10.0,
                tracks,
                truth,
                List.of(
                        measurement("TRK-OLD", 2.0, 20.0),
                        measurement("TRK-NEW", 9.0, 90.0),
                        measurement("TRK-NEW", 10.0, 100.0)));
        TrackStitchingAnalyzer.AnalysisResult result = analyzer.analyzeDetailed(
                scenario,
                new TrackStitchingAnalyzer.Configuration(
                        7.5, 8.5, 1.0, 1.0, false, 1.0));
        if (result.events().size() != 1
                || Math.abs(result.events().get(0).timeSeconds() - 10.0) > 1.0e-6) {
            throw new AssertionError(
                    "Expected eligibility to use ages from track updates");
        }
        if (hasEventAt(analyzer.analyzeDetailed(
                scenario,
                new TrackStitchingAnalyzer.Configuration(
                        0.0, 7.9, 0.0, 1.0, false, 1.0)),
                10.0)) {
            throw new AssertionError(
                    "Coasted max should be added to the last measurement update time");
        }
        if (hasEventAt(analyzer.analyzeDetailed(
                scenario,
                new TrackStitchingAnalyzer.Configuration(
                        0.0, 8.5, 0.0, 0.9, false, 1.0)),
                10.0)) {
            throw new AssertionError("New max should be added to the spawn measurement time");
        }
        TrackStitchingAnalyzer.Segment oldSegment = result.events().get(0)
                .oldSegments()
                .stream()
                .filter(segment -> segment.trackId().equals("TRK-OLD"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing old segment"));
        TrackStitchingAnalyzer.Segment newSegment = result.events().get(0)
                .newSegments()
                .stream()
                .filter(segment -> segment.trackId().equals("TRK-NEW"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing new segment"));
        requireClose(2.0, oldSegment.lastUpdateTimeSeconds(),
                "coasted window should ignore predicted/coasted records");
        requireClose(9.0, newSegment.formationTimeSeconds(),
                "new window should use first track update, not target truth start");
    }

    private static boolean hasEventAt(
            TrackStitchingAnalyzer.AnalysisResult result,
            double timeSeconds) {
        return result.events().stream()
                .anyMatch(event -> Math.abs(event.timeSeconds() - timeSeconds) < 1.0e-6);
    }

    private static void verifyLongGapBankIsCapped(TrackStitchingAnalyzer analyzer) {
        List<TrackRecord> tracks = List.of(
                track("TRK-OLD", 0.0, 0.0, 10.0, true),
                track("TRK-OLD", 500.0, 5_000.0, 10.0, false),
                track("TRK-NEW", 400.0, 4_000.0, 10.0, true),
                track("TRK-NEW", 500.0, 5_000.0, 10.0, false));
        RecordedScenario scenario = new RecordedScenario(
                Path.of("long_gap_bank_cap"),
                "Long-gap bank cap",
                500.0,
                tracks,
                List.of(),
                List.of(
                        measurement("TRK-NEW", 400.0, 4_000.0),
                        measurement("TRK-NEW", 500.0, 5_000.0)));
        TrackStitchingAnalyzer.AnalysisResult result = analyzer.analyzeDetailed(
                scenario,
                new TrackStitchingAnalyzer.Configuration(
                        1.0, 1_000.0, 0.0, 200.0, false, 0.1));
        if (result.events().size() != 1) {
            throw new AssertionError("Expected one long-gap stitching event");
        }
        TrackStitchingAnalyzer.PairDiagnostics diagnostics =
                diagnostics(result.events().get(0), "TRK-OLD", "TRK-NEW");
        int bankSampleCount = diagnostics.bankEvaluations().size();
        if (bankSampleCount != 241) {
            throw new AssertionError("Long-gap bank should be capped at 241 samples, got "
                    + bankSampleCount);
        }
        requireClose(0.1, diagnostics.bankEvaluations().get(0).timeSeconds(),
                "long-gap bank start");
        requireClose(399.9,
                diagnostics.bankEvaluations().get(bankSampleCount - 1).timeSeconds(),
                "long-gap bank end");
        if (!Double.isFinite(diagnostics.result().physicsAwareCost())
                || !Double.isFinite(diagnostics.result().minimumNegativeLogLikelihood())) {
            throw new AssertionError("Long-gap capped bank should still produce finite costs");
        }
    }

    private static void verifyClusteredBirthDensity(TrackStitchingAnalyzer analyzer) {
        TrackStitchingAnalyzer.Configuration configuration =
                new TrackStitchingAnalyzer.Configuration(1.0, 10.0, 0.0, 1.0,
                        false, 0.5, 1.0e-6, 1.0e-6);
        double singleDensity = lastDensity(analyzer.analyzeDetailed(
                birthDensityScenario("single_birth_density", 1, 50_000.0),
                configuration));
        double clusteredDensity = lastDensity(analyzer.analyzeDetailed(
                birthDensityScenario("clustered_birth_density", 10, 250.0),
                configuration));
        if (!(clusteredDensity > singleDensity * 3.0)) {
            throw new AssertionError("Clustered first-measurement births should produce "
                    + "larger learned spatial density than a single birth");
        }
    }

    private static double lastDensity(TrackStitchingAnalyzer.AnalysisResult result) {
        List<TrackStitchingAnalyzer.SpatialDensitySnapshot> history =
                result.spatialDensityHistory();
        if (history.isEmpty()) {
            throw new AssertionError("Expected spatial density history");
        }
        return history.get(history.size() - 1).representativeDensityPerCubicKilometer();
    }

    private static void verifyBirthDensityMaturesAfterNewTrackWindow(
            TrackStitchingAnalyzer analyzer) {
        TrackStitchingAnalyzer.Configuration configuration =
                new TrackStitchingAnalyzer.Configuration(1.0, 10.0, 0.0, 2.0,
                        false, 0.5, 1.0e-6, 1.0e-6);
        List<TrackRecord> tracks = List.of(track("TRK-BIRTH", 1.0, 0.0, 0.0, true));
        List<RecordedMeasurement> measurements = List.of(
                measurement("TRK-BIRTH", 1.0, 0.0),
                measurement("TRK-BIRTH", 3.0, 0.0),
                measurement("TRK-BIRTH", 4.0, 0.0));
        TrackStitchingAnalyzer.AnalysisResult result = analyzer.analyzeDetailed(
                new RecordedScenario(
                        Path.of("birth_density_maturity_boundary"),
                        "Birth density maturity boundary",
                        4.0,
                        tracks,
                        List.of(),
                        measurements),
                configuration);
        double boundaryDensity = densityAt(result, 3.0);
        double maturedDensity = densityAt(result, 4.0);
        if (boundaryDensity > 2.0e-6) {
            throw new AssertionError(
                    "Birth evidence should not self-count at the new-track window boundary");
        }
        if (!(maturedDensity > boundaryDensity * 10.0)) {
            throw new AssertionError(
                    "Birth evidence should mature after the new-track window has passed");
        }
    }

    private static double densityAt(
            TrackStitchingAnalyzer.AnalysisResult result,
            double timeSeconds) {
        return result.spatialDensityHistory().stream()
                .filter(snapshot -> Math.abs(snapshot.timeSeconds() - timeSeconds) < 1.0e-6)
                .findFirst()
                .map(TrackStitchingAnalyzer.SpatialDensitySnapshot
                        ::representativeDensityPerCubicKilometer)
                .orElseThrow(() -> new AssertionError("Missing density snapshot at " + timeSeconds));
    }

    private static RecordedScenario birthDensityScenario(
            String name,
            int birthCount,
            double spacingMeters) {
        List<TrackRecord> tracks = new ArrayList<>();
        List<RecordedMeasurement> measurements = new ArrayList<>();
        for (int index = 0; index < birthCount; index++) {
            String trackId = "TRK-BIRTH-" + index;
            double x = index * spacingMeters;
            tracks.add(track(trackId, 1.0, x, 0.0, true));
            measurements.add(measurement(trackId, 1.0, x));
            measurements.add(measurement(trackId, 3.0, x));
        }
        return new RecordedScenario(
                Path.of(name),
                name,
                3.0,
                tracks,
                List.of(),
                measurements);
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
        requireClose(1.5, firstSimple, "first formation-limited midpoint");
        requireClose(1.5, secondSimple, "second formation-limited midpoint");
        requireClose(firstPair.statisticalJoinTimeSeconds(),
                secondPair.statisticalJoinTimeSeconds(),
                "Mahalanobis bank should not drift to the later scenario timestamp");
        if (secondPair.statisticalJoinTimeSeconds() < 0.5
                || secondPair.statisticalJoinTimeSeconds() > 2.5) {
            throw new AssertionError(
                    "Mahalanobis bank should stay strictly inside the old/new update gap");
        }
        if (!Double.isFinite(secondPair.statisticalMahalanobisDistance())) {
            throw new AssertionError("Mahalanobis distance should be reported for each timing row");
        }
        TrackStitchingAnalyzer.PairDiagnostics diagnostics = diagnostics(
                events.get(1), "TRK-OLD", "TRK-NEW");
        double firstBankTime = diagnostics.bankEvaluations().get(0).timeSeconds();
        double lastBankTime = diagnostics.bankEvaluations()
                .get(diagnostics.bankEvaluations().size() - 1)
                .timeSeconds();
        requireClose(0.5, firstBankTime, "Mahalanobis bank should start after old update");
        requireClose(2.5, lastBankTime, "Mahalanobis bank should end before new formation");
    }

    private static void verifyDeadTrackJoinSeeds(TrackStitchingAnalyzer analyzer) {
        List<TrackRecord> tracks = new ArrayList<>();
        tracks.add(track("TRK-OLD", 0.0, 0.0, 10.0, true));
        tracks.add(track("TRK-OLD", 1.0, 10.0, 10.0, false));
        tracks.add(track("TRK-OLD", 2.0, 20.0, 10.0, false));
        tracks.add(track("TRK-NEW", 3.0, 30.0, 10.0, true));
        tracks.add(track("TRK-NEW", 4.0, 40.0, 10.0, false));
        tracks.add(track("TRK-NEW", 5.0, 50.0, 10.0, true));

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
                List.of(
                        measurement("TRK-NEW", 3.0, 30.0),
                        measurement("TRK-NEW", 5.0, 50.0)));
        List<TrackStitchingAnalyzer.EventResult> events = analyzer.analyze(
                scenario,
                new TrackStitchingAnalyzer.Configuration(4.0, 10.0, 0.0, 3.0, true, 0.5));
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

    private static void verifyOverlappingTrackletsRejected(TrackStitchingAnalyzer analyzer) {
        List<TrackRecord> tracks = new ArrayList<>();
        tracks.add(track("TRK-OLD", 0.0, 0.0, 10.0, true));
        tracks.add(track("TRK-OLD", 1.0, 10.0, 10.0, true));
        tracks.add(track("TRK-OLD", 2.0, 20.0, 10.0, true));
        tracks.add(track("TRK-OLD", 3.0, 30.0, 10.0, false));
        tracks.add(track("TRK-NEW", 1.5, 15.0, 10.0, true));
        tracks.add(track("TRK-NEW", 3.0, 30.0, 10.0, true));
        RecordedScenario scenario = new RecordedScenario(
                Path.of("overlapping_tracklets_rejected"),
                "Overlapping tracklets rejected",
                3.0,
                tracks,
                List.of(),
                List.of(
                        measurement("TRK-NEW", 1.5, 15.0),
                        measurement("TRK-NEW", 3.0, 30.0)));
        if (!analyzer.analyze(
                scenario,
                new TrackStitchingAnalyzer.Configuration(
                        0.5, 10.0, 0.0, 10.0, false, 0.5)).isEmpty()) {
            throw new AssertionError(
                    "Overlapping old/new update timelines should not produce a stitch pair");
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

    private static TrackStitchingAnalyzer.PairDiagnostics diagnostics(
            TrackStitchingAnalyzer.EventResult event,
            String oldTrackId,
            String newTrackId) {
        return event.diagnostics().stream()
                .filter(candidate -> candidate.result().oldTrackId().equals(oldTrackId)
                        && candidate.result().newTrackId().equals(newTrackId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing diagnostics " + oldTrackId + " -> " + newTrackId));
    }

    private static void verifyCanonicalInnovationMetrics() {
        double[] oldState = new double[9];
        double[] newState = new double[9];
        newState[0] = 2.0;
        newState[3] = 100.0;
        newState[6] = -25.0;
        double[][] oldCovariance = diagonal(9, 1.0);
        double[][] newCovariance = diagonal(9, 1.0);
        TrackStitchingAnalyzer.InnovationScore score =
                TrackStitchingAnalyzer.innovationScore(
                        new TrackStitchingAnalyzer.PropagatedState(oldState, oldCovariance),
                        new TrackStitchingAnalyzer.PropagatedState(newState, newCovariance));
        if (score.innovation().length != 3 || score.innovationCovariance().length != 3) {
            throw new AssertionError("Stitching score should use 3D position-only innovation");
        }
        requireClose(-2.0, score.innovation()[0], "innovation sign");
        requireClose(2.0, score.innovationCovariance()[0][0],
                "summed innovation covariance");
        requireClose(Math.sqrt(2.0), score.mahalanobisDistance(),
                "position-only Mahalanobis distance");
        double expectedNll = 0.5 * (3.0 * Math.log(2.0 * Math.PI)
                + 3.0 * Math.log(2.0) + 2.0);
        requireClose(expectedNll,
                TrackStitchingAnalyzer.canonicalNegativeLogLikelihood(score),
                "position-only Gaussian NLL");
        TrackStitchingAnalyzer.DistributionScore distribution =
                TrackStitchingAnalyzer.distributionScore(
                        new TrackStitchingAnalyzer.PropagatedState(oldState, oldCovariance),
                        new TrackStitchingAnalyzer.PropagatedState(newState, newCovariance));
        requireClose(0.5, distribution.bhattacharyyaDistance(),
                "Bhattacharyya distance");
        requireClose(Math.exp(-0.5), distribution.bhattacharyyaCoefficient(),
                "Bhattacharyya coefficient");
        requireClose(Math.sqrt(1.0 - Math.exp(-0.5)), distribution.hellingerDistance(),
                "Hellinger distance");
        TrackStitchingAnalyzer.DistributionScore distribution6d =
                TrackStitchingAnalyzer.distributionScore(
                        new TrackStitchingAnalyzer.PropagatedState(oldState, oldCovariance),
                        new TrackStitchingAnalyzer.PropagatedState(newState, newCovariance),
                        6);
        requireClose(1250.5, distribution6d.bhattacharyyaDistance(),
                "6D Bhattacharyya distance");
        if (distribution6d.bhattacharyyaCoefficient() >= 1.0e-300
                || distribution6d.hellingerDistance() < 0.999999) {
            throw new AssertionError("6D Gaussian overlap should reflect velocity mismatch");
        }
    }

    private static void verifyBackwardPropagationAndAnchors() {
        double[] sourceState = {100.0, 0, 0, 10.0, 0, 0, 2.0, 0, 0};
        TrackStitchingAnalyzer.PropagatedState source =
                new TrackStitchingAnalyzer.PropagatedState(sourceState, new double[9][9]);
        TrackStitchingAnalyzer.PropagatedState backward =
                TrackStitchingAnalyzer.propagate(source, 5.0, 3.0);
        TrackStitchingAnalyzer.PropagatedState forward =
                TrackStitchingAnalyzer.propagate(source, 5.0, 7.0);
        requireClose(80.0, backward.state()[0], "negative-dt position transition");
        requireClose(10.0, backward.state()[3], "negative-dt velocity transition");
        requireClose(0.0, backward.state()[6], "stitching propagation ignores latent acceleration");
        requireClose(120.0, forward.state()[0], "positive-dt position transition");
        requireClose(16.0 / 3.0, forward.covariance()[0][0],
                "forward CV process position variance");
        requireClose(16.0 / 3.0, backward.covariance()[0][0],
                "backward CV process position variance");
        requireClose(4.0, forward.covariance()[0][3],
                "forward CV process position-velocity covariance");
        requireClose(-4.0, backward.covariance()[0][3],
                "backward CV process position-velocity covariance uses signed dt");
        requireClose(4.0, forward.covariance()[3][3],
                "forward CV process velocity variance");
        requireClose(4.0, backward.covariance()[3][3],
                "backward CV process velocity variance");
        sourceState[0] = -1_000.0;
        requireClose(80.0,
                TrackStitchingAnalyzer.propagate(source, 5.0, 3.0).state()[0],
                "propagated source should be defensively copied");

        TrackRecord formation = track("TRK-NEW", 6.0, 60.0, 10.0, true);
        TrackRecord future = track("TRK-NEW", 10.0, 100.0, 10.0, true);
        TrackStitchingAnalyzer.Segment segment = new TrackStitchingAnalyzer.Segment(
                "TRK-NEW", 6.0, 10.0, 10.0,
                false, true, formation, future, future, future,
                List.of(formation, future));
        TrackStitchingAnalyzer.PropagatedState unsmoothed =
                TrackStitchingAnalyzer.retrodictNew(segment, 4.0);
        requireClose(40.0, unsmoothed.state()[0],
                "retrodiction starts at latest updated state and passes spawn time");

        TrackRecord coastedDisplaySample = track("TRK-NEW", 12.0, 1_000.0, 10.0, false);
        TrackStitchingAnalyzer.Segment segmentWithCoast =
                new TrackStitchingAnalyzer.Segment(
                        "TRK-NEW", 6.0, 10.0, 12.0,
                        false, true, formation, future, coastedDisplaySample, coastedDisplaySample,
                        List.of(formation, future, coastedDisplaySample));
        TrackStitchingAnalyzer.PropagatedState coastIgnored =
                TrackStitchingAnalyzer.retrodictNew(segmentWithCoast, 4.0);
        requireClose(40.0, coastIgnored.state()[0],
                "retrodiction should start at latest updated measurement, not a coasted sample");

        TrackStitchingAnalyzer.PropagatedState direct =
                TrackStitchingAnalyzer.propagate(
                        new TrackStitchingAnalyzer.PropagatedState(
                                future.state(), future.covariance()),
                        future.timeSeconds(),
                        4.0);
        requireMatrixClose(direct.covariance(), unsmoothed.covariance(),
                "retrodiction covariance should be direct from the latest update");
        verifyBankEvaluationsUseIndependentAnchors();
    }

    private static void verifyBankEvaluationsUseIndependentAnchors() {
        TrackStitchingAnalyzer analyzer = new TrackStitchingAnalyzer();
        TrackRecord oldAnchor = track("TRK-OLD", 0.0, 0.0, 10.0, true);
        TrackRecord oldCoast = track("TRK-OLD", 8.0, 1_000.0, 10.0, false);
        TrackRecord newFormation = track("TRK-NEW", 4.0, 40.0, 10.0, true);
        TrackRecord newMiddleUpdate = track("TRK-NEW", 6.0, 60.0, 10.0, true);
        TrackRecord newLatestUpdate = track("TRK-NEW", 8.0, 80.0, 10.0, true);
        double[][] measurementCovariance = diagonal(6, 0.01);
        RecordedMeasurement formationMeasurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-001", "TRK-NEW", 4.0,
                new double[]{40.0, 0, 0, 10, 0, 0},
                measurementCovariance, 0.1, 0.1);
        RecordedMeasurement distractingMiddleMeasurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-001", "TRK-NEW", 6.0,
                new double[]{-500.0, 0, 0, 0, 0, 0},
                measurementCovariance, 0.1, 0.1);
        RecordedMeasurement latestMeasurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-001", "TRK-NEW", 8.0,
                new double[]{80.0, 0, 0, 10, 0, 0},
                measurementCovariance, 0.1, 0.1);
        RecordedScenario scenario = new RecordedScenario(
                Path.of("precise_stitching_bank"),
                "Precise stitching bank",
                8.0,
                List.of(oldAnchor, oldCoast, newFormation, newMiddleUpdate, newLatestUpdate),
                List.of(),
                List.of(formationMeasurement, distractingMiddleMeasurement, latestMeasurement));
        TrackStitchingAnalyzer.AnalysisResult result = analyzer.analyzeDetailed(
                scenario,
                new TrackStitchingAnalyzer.Configuration(
                        1.0, 10.0, 0.0, 10.0, false, 1.0));
        if (result.events().size() != 1) {
            throw new AssertionError("Expected one stitching event for precise bank test");
        }
        TrackStitchingAnalyzer.PairDiagnostics diagnostics =
                diagnostics(result.events().get(0), "TRK-OLD", "TRK-NEW");
        if (diagnostics.bankEvaluations().size() != 3) {
            throw new AssertionError("Expected bank samples at 1, 2, and 3 seconds");
        }
        for (TrackStitchingAnalyzer.BankEvaluation evaluation
                : diagnostics.bankEvaluations()) {
            double time = evaluation.timeSeconds();
            TrackStitchingAnalyzer.PropagatedState expectedOld =
                    TrackStitchingAnalyzer.propagate(
                            new TrackStitchingAnalyzer.PropagatedState(
                                    oldAnchor.state(), oldAnchor.covariance()),
                            oldAnchor.timeSeconds(),
                            time);
            TrackStitchingAnalyzer.PropagatedState expectedNew =
                    TrackStitchingAnalyzer.propagate(
                            TrackStitchingAnalyzer.propagate(
                                    new TrackStitchingAnalyzer.PropagatedState(
                                            newLatestUpdate.state(),
                                            newLatestUpdate.covariance()),
                                    newLatestUpdate.timeSeconds(),
                                    newFormation.timeSeconds()),
                            newFormation.timeSeconds(),
                            time);
            requireVectorClose(expectedOld.state(), evaluation.oldState(),
                    "old bank state should be direct from last update");
            requireMatrixClose(expectedOld.covariance(), evaluation.oldCovariance(),
                    "old bank covariance should be direct from last update");
            requireVectorClose(expectedNew.state(), evaluation.newState(),
                    "new bank state should use current-event formation estimate");
            requireMatrixClose(expectedNew.covariance(), evaluation.newCovariance(),
                    "new bank covariance should use current-event formation estimate");
            double gapSeconds = newFormation.timeSeconds() - oldAnchor.timeSeconds();
            double oldDuration = time - oldAnchor.timeSeconds();
            double newDuration = newFormation.timeSeconds() - time;
            double positionShape = (oldDuration * oldDuration * oldDuration
                    + newDuration * newDuration * newDuration) / 3.0;
            double expectedLogDeterminant = 3.0 * Math.log(positionShape);
            double expectedLogVolume = 1.5 * Math.log(gapSeconds)
                    + 0.5 * expectedLogDeterminant;
            requireClose(gapSeconds, evaluation.physicsAwareGapSeconds(),
                    "Physics-Aware gap should use old update to new formation");
            requireClose(expectedLogDeterminant,
                    evaluation.physicsAwareBridgeGeometryLogDeterminant(),
                    "Physics-Aware bridge log determinant");
            requireClose(Math.exp(expectedLogVolume), evaluation.physicsAwareVolume(),
                    "Physics-Aware volume");
            requireClose(expectedLogVolume, evaluation.physicsAwareOpportunityCost(),
                    "Physics-Aware alpha-weighted opportunity cost");
            requireClose(evaluation.negativeLogLikelihood(),
                    evaluation.physicsAwareNegativeLogLikelihood(),
                    "default Physics-Aware NLL should match unscaled NLL");
            requireClose(evaluation.physicsAwareNegativeLogLikelihood() + expectedLogVolume,
                    evaluation.physicsAwareCost(),
                    "Physics-Aware final bank cost");
        }
    }

    private static void verifyPhysicsAwareCovarianceScaleAffectsOnlyPhysicsAwareNll() {
        double covarianceScale = 4.0;
        TrackStitchingAnalyzer analyzer = new TrackStitchingAnalyzer();
        TrackRecord oldAnchor = track("TRK-OLD", 0.0, 0.0, 10.0, true);
        TrackRecord oldCoast = track("TRK-OLD", 8.0, 80.0, 10.0, false);
        TrackRecord newFormation = track("TRK-NEW", 4.0, 40.0, 10.0, true);
        TrackRecord newLatestUpdate = track("TRK-NEW", 8.0, 80.0, 10.0, true);
        double[][] measurementCovariance = diagonal(6, 0.01);
        RecordedScenario scenario = new RecordedScenario(
                Path.of("physics_aware_covariance_scale"),
                "Physics-aware covariance scale",
                8.0,
                List.of(oldAnchor, oldCoast, newFormation, newLatestUpdate),
                List.of(),
                List.of(
                        new RecordedMeasurement(
                                "GOD-SENSOR-001", "TGT-001", "TRK-NEW", 4.0,
                                new double[]{40.0, 0, 0, 10, 0, 0},
                                measurementCovariance, 0.1, 0.1),
                        new RecordedMeasurement(
                                "GOD-SENSOR-001", "TGT-001", "TRK-NEW", 8.0,
                                new double[]{80.0, 0, 0, 10, 0, 0},
                                measurementCovariance, 0.1, 0.1)));
        TrackStitchingAnalyzer.AnalysisResult result = analyzer.analyzeDetailed(
                scenario,
                new TrackStitchingAnalyzer.Configuration(
                        1.0, 10.0, 0.0, 10.0, false, 1.0,
                        1.0e-6, 1.0e-6,
                        50.0, 1.0, 1.0,
                        1.0, covarianceScale));
        if (result.events().size() != 1) {
            throw new AssertionError("Expected one event for covariance scale test");
        }
        TrackStitchingAnalyzer.PairDiagnostics diagnostics =
                diagnostics(result.events().get(0), "TRK-OLD", "TRK-NEW");
        for (TrackStitchingAnalyzer.BankEvaluation evaluation
                : diagnostics.bankEvaluations()) {
            double expectedUnscaledNll = 0.5 * (3.0 * Math.log(2.0 * Math.PI)
                    + evaluation.logDeterminant()
                    + evaluation.innovationQuadratic());
            double expectedScaledNll = 0.5 * (3.0 * Math.log(2.0 * Math.PI)
                    + evaluation.logDeterminant()
                    + 3.0 * Math.log(covarianceScale)
                    + evaluation.innovationQuadratic() / covarianceScale);
            requireClose(expectedUnscaledNll, evaluation.negativeLogLikelihood(),
                    "unscaled bank NLL should remain canonical");
            requireClose(expectedScaledNll, evaluation.physicsAwareNegativeLogLikelihood(),
                    "Physics-Aware covariance-scaled NLL");
            requireClose(evaluation.physicsAwareNegativeLogLikelihood()
                            + evaluation.physicsAwareOpportunityCost(),
                    evaluation.physicsAwareCost(),
                    "Physics-Aware cost should use scaled NLL");
        }
        TrackStitchingAnalyzer.PairResult pair = diagnostics.result();
        TrackStitchingAnalyzer.BankEvaluation selected =
                diagnostics.bankEvaluations().stream()
                        .filter(evaluation -> Math.abs(
                                evaluation.timeSeconds()
                                        - pair.physicsAwareTimeSeconds()) <= 1.0e-9)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "Missing selected Physics-Aware bank evaluation"));
        requireClose(selected.physicsAwareNegativeLogLikelihood(),
                pair.physicsAwareNegativeLogLikelihood(),
                "Pair row should expose scaled Physics-Aware NLL");
    }

    private static void verifyMidpointCovarianceMatchesTrackerScale() {
        ImmTracker oldTracker = new ImmTracker(new ImmSettings());
        oldTracker.processMeasurements(List.of(targetMeasurement(0.0, 0.0, 10.0)));
        oldTracker.drainUpdatedRecords();
        oldTracker.processMeasurements(List.of(targetMeasurement(1.0, 10.0, 10.0)));
        TrackRecord oldAnchor = rename(
                last(oldTracker.drainUpdatedRecords()),
                "TRK-OLD");
        oldTracker.advanceTo(2.0);
        double trackerMidpointVariance =
                oldTracker.currentViews().get(0).positionCovariance()[0][0];
        TrackRecord oldEventCoast = rename(oldTracker.recordsAt(5.0, Set.of()).get(0), "TRK-OLD");

        ImmTracker newTracker = new ImmTracker(new ImmSettings());
        newTracker.processMeasurements(List.of(targetMeasurement(3.0, 30.0, 10.0)));
        TrackRecord newFormation = rename(
                last(newTracker.drainUpdatedRecords()),
                "TRK-NEW");
        newTracker.processMeasurements(List.of(targetMeasurement(4.0, 40.0, 10.0)));
        TrackRecord newLatestUpdate = rename(
                last(newTracker.drainUpdatedRecords()),
                "TRK-NEW");
        TrackRecord newEventCoast = rename(newTracker.recordsAt(5.0, Set.of()).get(0), "TRK-NEW");

        RecordedScenario scenario = new RecordedScenario(
                Path.of("midpoint_covariance_scale"),
                "Midpoint covariance scale",
                5.0,
                List.of(oldAnchor, oldEventCoast, newFormation, newLatestUpdate, newEventCoast),
                List.of(),
                List.of(measurement("TRK-NEW", 5.0, 50.0)));
        TrackStitchingAnalyzer.AnalysisResult result =
                new TrackStitchingAnalyzer().analyzeDetailed(
                        scenario,
                        new TrackStitchingAnalyzer.Configuration(
                                1.0, 10.0, 0.0, 10.0, false, 0.5));
        if (result.events().size() != 1) {
            throw new AssertionError("Expected one stitching event for midpoint covariance test");
        }
        TrackStitchingAnalyzer.JoinEvaluation midpoint =
                diagnostics(result.events().get(0), "TRK-OLD", "TRK-NEW")
                        .joinEvaluations().stream()
                        .filter(evaluation -> evaluation.variant().equals("Simple midpoint"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Missing simple midpoint"));
        requireClose(2.0, midpoint.timeSeconds(), "simple temporal midpoint");
        assertComparableCovarianceScale(
                trackerMidpointVariance,
                midpoint.oldCovariance()[0][0],
                "old midpoint covariance scale");
        assertComparableCovarianceScale(
                trackerMidpointVariance,
                midpoint.newCovariance()[0][0],
                "new midpoint covariance scale");
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

    private static TrackRecord rename(TrackRecord record, String trackId) {
        return new TrackRecord(
                trackId,
                record.timeSeconds(),
                record.state(),
                record.covariance(),
                record.updated(),
                record.measurement());
    }

    private static TrackRecord last(List<TrackRecord> records) {
        if (records.isEmpty()) {
            throw new AssertionError("Expected at least one track update");
        }
        return records.get(records.size() - 1);
    }

    private static TargetMeasurement targetMeasurement(double time, double x, double vx) {
        return new TargetMeasurement(
                "truth",
                time,
                new EcefPoint(6_378_137.0 + x, 0.0, 0.0),
                new EcefVector(vx, 0.0, 0.0),
                1.0,
                1.0);
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

    private static void requireVectorClose(double[] expected, double[] actual, String label) {
        if (expected.length != actual.length) {
            throw new AssertionError(label + ": vector size mismatch");
        }
        for (int index = 0; index < expected.length; index++) {
            requireClose(expected[index], actual[index], label + " at " + index);
        }
    }

    private static void requireMatrixClose(double[][] expected, double[][] actual, String label) {
        if (expected.length != actual.length) {
            throw new AssertionError(label + ": matrix row count mismatch");
        }
        for (int row = 0; row < expected.length; row++) {
            if (expected[row].length != actual[row].length) {
                throw new AssertionError(label + ": matrix column count mismatch at " + row);
            }
            for (int column = 0; column < expected[row].length; column++) {
                requireClose(expected[row][column], actual[row][column],
                        label + " at " + row + "," + column);
            }
        }
    }

    private static void assertComparableCovarianceScale(
            double trackerVariance,
            double stitchVariance,
            String label) {
        double reference = Math.max(1.0, trackerVariance);
        if (stitchVariance < reference / 100.0 || stitchVariance > reference * 100.0) {
            throw new AssertionError(label + ": tracker variance " + trackerVariance
                    + " but stitching variance " + stitchVariance);
        }
    }

    private static void requireClose(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 1.0e-6) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }
}
