package com.targettracker.analysis;

import com.targettracker.math.LinearAlgebra;
import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.tracking.ImmParameters;
import com.targettracker.tracking.TrackRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Track-segment timing, compatibility, and truth-reference analysis. */
public final class TrackStitchingAnalyzer {
    private static final int STATE_SIZE = 9;
    private static final int POSITION_SIZE = 3;
    private static final int POSITION_VELOCITY_SIZE = 6;
    private static final int MEASUREMENT_SIZE = 6;
    private static final double EPSILON = 1.0e-8;
    private static final double LIVE_SAMPLE_TOLERANCE_SECONDS = 1.01;
    private static final double STITCHING_PROCESS_NOISE =
            ImmParameters.defaults().cvProcessNoise();
    private static final double MINIMUM_LAMBDA_EX = 1.0e-300;
    private static final double CUBIC_METERS_PER_CUBIC_KILOMETER = 1.0e9;
    private static final double MINIMUM_LEARNED_BIRTH_DENSITY_PER_CUBIC_KILOMETER = 1.0e-12;
    private static final double MAXIMUM_LEARNED_BIRTH_DENSITY_PER_CUBIC_KILOMETER = 1.0e3;
    private static final double FIELD_FORGETTING_HALF_LIFE_SECONDS = 30.0 * 60.0;
    private static final double PRIOR_EXPOSURE_SCANS = 2.0;
    private static final double MINIMUM_SPATIAL_CELL_METERS = 500.0;
    private static final double MAXIMUM_SPATIAL_CELL_METERS = 5_000.0;
    private static final double TARGET_GRID_AXIS_CELLS = 28.0;
    private static final int MAXIMUM_SMOOTHING_RADIUS_CELLS = 4;

    public List<EventResult> analyze(RecordedScenario scenario, Configuration configuration) {
        return analyzeDetailed(scenario, configuration).events();
    }

    public AnalysisResult analyzeDetailed(
            RecordedScenario scenario,
            Configuration configuration) {
        Map<String, List<TrackRecord>> tracks = groupTracks(scenario.records());
        Map<String, List<GroundTruthRecord>> truth = groupTruth(scenario.groundTruth());
        Map<String, List<RecordedMeasurement>> measurementsByTrack =
                associateMeasurements(tracks, scenario.measurements());
        BirthDensityField learnedBirthDensityField = BirthDensityField.create(
                tracks, measurementsByTrack, scenario.measurements(), configuration);
        TreeSet<Double> measurementTimes = new TreeSet<>();
        scenario.measurements().stream()
                .map(RecordedMeasurement::timeSeconds)
                .forEach(measurementTimes::add);

        List<EventResult> events = new ArrayList<>();
        List<SpatialDensitySnapshot> densityHistory = new ArrayList<>();
        for (double eventTime : measurementTimes) {
            List<Segment> allSegments = new ArrayList<>();
            List<Segment> oldSegments = new ArrayList<>();
            List<Segment> newSegments = new ArrayList<>();
            for (Map.Entry<String, List<TrackRecord>> entry : tracks.entrySet()) {
                Segment segment = segmentAt(entry.getKey(), entry.getValue(), eventTime);
                if (segment == null) {
                    continue;
                }
                allSegments.add(segment);
                double coastAge = eventTime - segment.lastUpdateTimeSeconds();
                if (coastAge > EPSILON
                        && inWindow(coastAge,
                        configuration.coastedMinimumSeconds(),
                        configuration.coastedMaximumSeconds())
                        && (segment.liveAtEvent() || configuration.allowDeadTracks())) {
                    oldSegments.add(segment);
                }
                double newAge = eventTime - segment.formationTimeSeconds();
                if (segment.liveAtEvent()
                        && inWindow(newAge,
                        configuration.newMinimumSeconds(),
                        configuration.newMaximumSeconds())) {
                    newSegments.add(segment);
                }
            }
            allSegments.sort(Comparator.comparing(Segment::trackId));
            learnedBirthDensityField.advanceTo(eventTime);
            double learnedBirthDensity = learnedBirthDensityField.meanDensityPerCubicKilometer();
            densityHistory.add(learnedBirthDensityField.snapshot(eventTime));
            oldSegments.sort(Comparator.comparing(Segment::trackId));
            newSegments.sort(Comparator.comparing(Segment::trackId));
            boolean hasDistinctPair = oldSegments.stream().anyMatch(oldSegment ->
                    newSegments.stream().anyMatch(newSegment ->
                            eligibleStrictPair(
                                    oldSegment,
                                    newSegment,
                                    configuration.resolutionSeconds())));
            if (!hasDistinctPair) {
                continue;
            }

            List<PairResult> pairs = new ArrayList<>();
            List<PairDiagnostics> diagnostics = new ArrayList<>();
            for (Segment oldSegment : oldSegments) {
                for (Segment newSegment : newSegments) {
                    if (eligibleStrictPair(
                            oldSegment,
                            newSegment,
                            configuration.resolutionSeconds())) {
                        PairDiagnostics pairDiagnostics = analyzePair(
                                oldSegment,
                                newSegment,
                                truth,
                                configuration,
                                learnedBirthDensityField);
                        pairs.add(pairDiagnostics.result());
                        diagnostics.add(pairDiagnostics);
                    }
                }
            }
            events.add(new EventResult(
                    eventTime,
                    List.copyOf(allSegments),
                    List.copyOf(oldSegments),
                    List.copyOf(newSegments),
                    List.copyOf(pairs),
                    optimalAssignments(
                            oldSegments, newSegments, pairs, Metric.BHATTACHARYYA_DISTANCE),
                    optimalAssignments(
                            oldSegments, newSegments, pairs, Metric.BHATTACHARYYA_COEFFICIENT),
                    optimalAssignments(
                            oldSegments, newSegments, pairs, Metric.HELLINGER_DISTANCE),
                    optimalAssignments(
                            oldSegments, newSegments, pairs,
                            Metric.BHATTACHARYYA_DISTANCE_6D),
                    optimalAssignments(
                            oldSegments, newSegments, pairs,
                            Metric.BHATTACHARYYA_COEFFICIENT_6D),
                    optimalAssignments(
                            oldSegments, newSegments, pairs,
                            Metric.HELLINGER_DISTANCE_6D),
                    optimalAssignments(oldSegments, newSegments, pairs, Metric.NLL),
                    optimalAssignments(oldSegments, newSegments, pairs, Metric.MAHALANOBIS),
                    optimalAssignments(oldSegments, newSegments, pairs, Metric.STATIC_NLLR),
                    optimalAssignments(oldSegments, newSegments, pairs, Metric.LEARNED_NLLR),
                    learnedBirthDensity,
                    List.copyOf(diagnostics)));
        }
        return new AnalysisResult(List.copyOf(events), List.copyOf(densityHistory));
    }

    private static boolean eligibleStrictPair(
            Segment oldSegment,
            Segment newSegment,
            double resolutionSeconds) {
        if (oldSegment.trackId().equals(newSegment.trackId())) {
            return false;
        }
        double bankStep = bankStep(resolutionSeconds);
        return oldSegment.lastUpdateTimeSeconds() + bankStep
                <= newSegment.formationTimeSeconds() - bankStep + EPSILON;
    }

    private static PairDiagnostics analyzePair(
            Segment oldSegment,
            Segment newSegment,
            Map<String, List<GroundTruthRecord>> truth,
            Configuration configuration,
            BirthDensityField learnedBirthDensityField) {
        TrackRecord oldAnchor = oldSegment.lastUpdateRecord();
        TrackRecord newAnchor = joinTimingAnchor(newSegment);
        double oldTime = oldAnchor.timeSeconds();
        double newTime = newAnchor.timeSeconds();
        double bankStep = bankStep(configuration.resolutionSeconds());
        double bankStart = oldTime + bankStep;
        double bankEnd = newTime - bankStep;
        List<Double> timeBank = timeBank(bankStart, bankEnd, bankStep);

        double simpleTime = (oldTime + newTime) / 2.0;
        double kinematicTime = kinematicJoinTime(oldAnchor, newAnchor, bankStart, bankEnd);
        List<ScoredTime> mahalanobisScores = new ArrayList<>();
        List<TruthScore> truthScores = new ArrayList<>();
        List<BankEvaluation> bankEvaluations = new ArrayList<>();
        for (double time : timeBank) {
            BankEvaluation evaluation = bankEvaluation(
                    oldSegment.trackId(),
                    newSegment.trackId(),
                    oldAnchor,
                    newSegment,
                    time,
                    configuration,
                    learnedBirthDensityField);
            bankEvaluations.add(evaluation);
            mahalanobisScores.add(new ScoredTime(time, evaluation.mahalanobisDistance()));
            TruthScore truthScore = truthScore(
                    time, evaluation.oldState(), evaluation.newState(), truth);
            if (truthScore != null) {
                truthScores.add(truthScore);
            }
        }
        double statisticalTime = bestTwoMidpoint(mahalanobisScores, simpleTime);
        double actualTime = truthScores.isEmpty()
                ? Double.NaN
                : bestTwoTruthMidpoint(truthScores, simpleTime);
        String truthTargetId = truthScores.stream()
                .min(Comparator.comparingDouble(TruthScore::score))
                .map(TruthScore::targetId)
                .orElse("");

        List<JoinEvaluation> joinEvaluations = new ArrayList<>();
        JoinEvaluation simpleEvaluation = joinEvaluation(
                "Simple midpoint", oldAnchor, newSegment,
                simpleTime, configuration, learnedBirthDensityField);
        JoinEvaluation kinematicEvaluation = joinEvaluation(
                "Kinematic midpoint", oldAnchor, newSegment,
                kinematicTime, configuration, learnedBirthDensityField);
        JoinEvaluation statisticalEvaluation = joinEvaluation(
                "Mahalanobis bank", oldAnchor, newSegment,
                statisticalTime, configuration, learnedBirthDensityField);
        JoinEvaluation actualEvaluation = Double.isFinite(actualTime)
                ? joinEvaluation(
                        "Truth RMS", oldAnchor, newSegment,
                        actualTime, configuration, learnedBirthDensityField)
                : JoinEvaluation.nan("Truth RMS");
        joinEvaluations.add(simpleEvaluation);
        joinEvaluations.add(kinematicEvaluation);
        joinEvaluations.add(statisticalEvaluation);
        joinEvaluations.add(actualEvaluation);

        PairResult result = new PairResult(
                oldSegment.trackId(),
                newSegment.trackId(),
                truthTargetId,
                simpleTime,
                kinematicTime,
                statisticalTime,
                actualTime,
                simpleEvaluation.bhattacharyyaDistance(),
                kinematicEvaluation.bhattacharyyaDistance(),
                statisticalEvaluation.bhattacharyyaDistance(),
                actualEvaluation.bhattacharyyaDistance(),
                simpleEvaluation.bhattacharyyaCoefficient(),
                kinematicEvaluation.bhattacharyyaCoefficient(),
                statisticalEvaluation.bhattacharyyaCoefficient(),
                actualEvaluation.bhattacharyyaCoefficient(),
                simpleEvaluation.hellingerDistance(),
                kinematicEvaluation.hellingerDistance(),
                statisticalEvaluation.hellingerDistance(),
                actualEvaluation.hellingerDistance(),
                simpleEvaluation.bhattacharyyaDistance6d(),
                kinematicEvaluation.bhattacharyyaDistance6d(),
                statisticalEvaluation.bhattacharyyaDistance6d(),
                actualEvaluation.bhattacharyyaDistance6d(),
                simpleEvaluation.bhattacharyyaCoefficient6d(),
                kinematicEvaluation.bhattacharyyaCoefficient6d(),
                statisticalEvaluation.bhattacharyyaCoefficient6d(),
                actualEvaluation.bhattacharyyaCoefficient6d(),
                simpleEvaluation.hellingerDistance6d(),
                kinematicEvaluation.hellingerDistance6d(),
                statisticalEvaluation.hellingerDistance6d(),
                actualEvaluation.hellingerDistance6d(),
                simpleEvaluation.negativeLogLikelihood(),
                kinematicEvaluation.negativeLogLikelihood(),
                statisticalEvaluation.negativeLogLikelihood(),
                actualEvaluation.negativeLogLikelihood(),
                simpleEvaluation.mahalanobisDistance(),
                kinematicEvaluation.mahalanobisDistance(),
                statisticalEvaluation.mahalanobisDistance(),
                actualEvaluation.mahalanobisDistance(),
                simpleEvaluation.staticNegativeLogLikelihoodRatio(),
                kinematicEvaluation.staticNegativeLogLikelihoodRatio(),
                statisticalEvaluation.staticNegativeLogLikelihoodRatio(),
                actualEvaluation.staticNegativeLogLikelihoodRatio(),
                simpleEvaluation.learnedNegativeLogLikelihoodRatio(),
                kinematicEvaluation.learnedNegativeLogLikelihoodRatio(),
                statisticalEvaluation.learnedNegativeLogLikelihoodRatio(),
                actualEvaluation.learnedNegativeLogLikelihoodRatio());
        return new PairDiagnostics(
                result,
                List.copyOf(joinEvaluations),
                List.copyOf(bankEvaluations));
    }

    private static List<OptimalAssignment> optimalAssignments(
            List<Segment> oldSegments,
            List<Segment> newSegments,
            List<PairResult> pairs,
            Metric metric) {
        if (oldSegments.isEmpty() || newSegments.isEmpty() || pairs.isEmpty()) {
            return List.of();
        }
        Map<String, PairResult> pairByIds = new LinkedHashMap<>();
        for (PairResult pair : pairs) {
            pairByIds.put(key(pair.oldTrackId(), pair.newTrackId()), pair);
        }
        int size = Math.max(oldSegments.size(), newSegments.size());
        double[][] costs = new double[size][size];
        double missingCost = 1.0e12;
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                costs[row][column] = row < oldSegments.size() && column < newSegments.size()
                        ? missingCost
                        : 0.0;
            }
        }
        VariantScore[][] scores = new VariantScore[oldSegments.size()][newSegments.size()];
        for (int row = 0; row < oldSegments.size(); row++) {
            for (int column = 0; column < newSegments.size(); column++) {
                PairResult pair = pairByIds.get(key(
                        oldSegments.get(row).trackId(),
                        newSegments.get(column).trackId()));
                if (pair == null) {
                    continue;
                }
                VariantScore score = bestVariant(pair, metric);
                if (score != null) {
                    scores[row][column] = score;
                    costs[row][column] = score.cost();
                }
            }
        }
        int[] assignment = hungarian(costs);
        List<OptimalAssignment> results = new ArrayList<>();
        for (int row = 0; row < oldSegments.size(); row++) {
            int column = assignment[row];
            if (column < 0 || column >= newSegments.size()) {
                continue;
            }
            VariantScore score = scores[row][column];
            if (score == null || !Double.isFinite(score.score())) {
                continue;
            }
            results.add(new OptimalAssignment(
                    metric.displayName(),
                    oldSegments.get(row).trackId(),
                    newSegments.get(column).trackId(),
                    score.variant(),
                    score.joinTimeSeconds(),
                    score.score()));
        }
        return List.copyOf(results);
    }

    private static VariantScore bestVariant(PairResult pair, Metric metric) {
        List<VariantScore> variants = List.of(
                variant(pair, "Simple midpoint",
                        pair.simpleJoinTimeSeconds(),
                        pair.simpleNegativeLogLikelihood(),
                        pair.simpleMahalanobisDistance(),
                        metric),
                variant(pair, "Kinematic midpoint",
                        pair.kinematicJoinTimeSeconds(),
                        pair.kinematicNegativeLogLikelihood(),
                        pair.kinematicMahalanobisDistance(),
                        metric),
                variant(pair, "Mahalanobis bank",
                        pair.statisticalJoinTimeSeconds(),
                        pair.statisticalNegativeLogLikelihood(),
                        pair.statisticalMahalanobisDistance(),
                        metric));
        return variants.stream()
                .filter(score -> Double.isFinite(score.score())
                        && Double.isFinite(score.cost()))
                .min(Comparator.comparingDouble(VariantScore::cost))
                .orElse(null);
    }

    private static VariantScore variant(
            PairResult pair,
            String label,
            double joinTimeSeconds,
            double negativeLogLikelihood,
            double mahalanobisDistance,
            Metric metric) {
        double score = switch (metric) {
            case BHATTACHARYYA_DISTANCE -> switch (label) {
                case "Simple midpoint" -> pair.simpleBhattacharyyaDistance();
                case "Kinematic midpoint" -> pair.kinematicBhattacharyyaDistance();
                case "Mahalanobis bank" -> pair.statisticalBhattacharyyaDistance();
                default -> pair.actualBhattacharyyaDistance();
            };
            case BHATTACHARYYA_COEFFICIENT -> switch (label) {
                case "Simple midpoint" -> pair.simpleBhattacharyyaCoefficient();
                case "Kinematic midpoint" -> pair.kinematicBhattacharyyaCoefficient();
                case "Mahalanobis bank" -> pair.statisticalBhattacharyyaCoefficient();
                default -> pair.actualBhattacharyyaCoefficient();
            };
            case HELLINGER_DISTANCE -> switch (label) {
                case "Simple midpoint" -> pair.simpleHellingerDistance();
                case "Kinematic midpoint" -> pair.kinematicHellingerDistance();
                case "Mahalanobis bank" -> pair.statisticalHellingerDistance();
                default -> pair.actualHellingerDistance();
            };
            case BHATTACHARYYA_DISTANCE_6D -> switch (label) {
                case "Simple midpoint" -> pair.simpleBhattacharyyaDistance6d();
                case "Kinematic midpoint" -> pair.kinematicBhattacharyyaDistance6d();
                case "Mahalanobis bank" -> pair.statisticalBhattacharyyaDistance6d();
                default -> pair.actualBhattacharyyaDistance6d();
            };
            case BHATTACHARYYA_COEFFICIENT_6D -> switch (label) {
                case "Simple midpoint" -> pair.simpleBhattacharyyaCoefficient6d();
                case "Kinematic midpoint" -> pair.kinematicBhattacharyyaCoefficient6d();
                case "Mahalanobis bank" -> pair.statisticalBhattacharyyaCoefficient6d();
                default -> pair.actualBhattacharyyaCoefficient6d();
            };
            case HELLINGER_DISTANCE_6D -> switch (label) {
                case "Simple midpoint" -> pair.simpleHellingerDistance6d();
                case "Kinematic midpoint" -> pair.kinematicHellingerDistance6d();
                case "Mahalanobis bank" -> pair.statisticalHellingerDistance6d();
                default -> pair.actualHellingerDistance6d();
            };
            case NLL -> negativeLogLikelihood;
            case MAHALANOBIS -> mahalanobisDistance;
            case STATIC_NLLR -> switch (label) {
                case "Simple midpoint" -> pair.simpleStaticNegativeLogLikelihoodRatio();
                case "Kinematic midpoint" -> pair.kinematicStaticNegativeLogLikelihoodRatio();
                case "Mahalanobis bank" -> pair.statisticalStaticNegativeLogLikelihoodRatio();
                default -> pair.actualStaticNegativeLogLikelihoodRatio();
            };
            case LEARNED_NLLR -> switch (label) {
                case "Simple midpoint" -> pair.simpleLearnedNegativeLogLikelihoodRatio();
                case "Kinematic midpoint" -> pair.kinematicLearnedNegativeLogLikelihoodRatio();
                case "Mahalanobis bank" -> pair.statisticalLearnedNegativeLogLikelihoodRatio();
                default -> pair.actualLearnedNegativeLogLikelihoodRatio();
            };
        };
        double cost = metric == Metric.BHATTACHARYYA_COEFFICIENT
                || metric == Metric.BHATTACHARYYA_COEFFICIENT_6D
                ? 1.0 - score
                : score;
        return new VariantScore(label, joinTimeSeconds, score, cost);
    }

    private static TrackRecord joinTimingAnchor(Segment newSegment) {
        return newSegment.formationRecord();
    }

    private static int[] hungarian(double[][] cost) {
        int size = cost.length;
        double[] rowPotential = new double[size + 1];
        double[] columnPotential = new double[size + 1];
        int[] matchingRowForColumn = new int[size + 1];
        int[] previousColumn = new int[size + 1];
        for (int row = 1; row <= size; row++) {
            matchingRowForColumn[0] = row;
            int column0 = 0;
            double[] minimum = new double[size + 1];
            boolean[] used = new boolean[size + 1];
            for (int column = 1; column <= size; column++) {
                minimum[column] = Double.POSITIVE_INFINITY;
            }
            do {
                used[column0] = true;
                int row0 = matchingRowForColumn[column0];
                double delta = Double.POSITIVE_INFINITY;
                int column1 = 0;
                for (int column = 1; column <= size; column++) {
                    if (used[column]) {
                        continue;
                    }
                    double current = cost[row0 - 1][column - 1]
                            - rowPotential[row0]
                            - columnPotential[column];
                    if (current < minimum[column]) {
                        minimum[column] = current;
                        previousColumn[column] = column0;
                    }
                    if (minimum[column] < delta) {
                        delta = minimum[column];
                        column1 = column;
                    }
                }
                for (int column = 0; column <= size; column++) {
                    if (used[column]) {
                        rowPotential[matchingRowForColumn[column]] += delta;
                        columnPotential[column] -= delta;
                    } else {
                        minimum[column] -= delta;
                    }
                }
                column0 = column1;
            } while (matchingRowForColumn[column0] != 0);
            do {
                int column1 = previousColumn[column0];
                matchingRowForColumn[column0] = matchingRowForColumn[column1];
                column0 = column1;
            } while (column0 != 0);
        }
        int[] assignment = new int[size];
        java.util.Arrays.fill(assignment, -1);
        for (int column = 1; column <= size; column++) {
            if (matchingRowForColumn[column] > 0) {
                assignment[matchingRowForColumn[column] - 1] = column - 1;
            }
        }
        return assignment;
    }

    private static String key(String oldTrackId, String newTrackId) {
        return oldTrackId + "\u0000" + newTrackId;
    }

    private static Segment segmentAt(
            String trackId,
            List<TrackRecord> records,
            double eventTime) {
        TrackRecord formation = null;
        TrackRecord lastUpdate = null;
        TrackRecord lastObserved = null;
        List<TrackRecord> history = new ArrayList<>();
        for (TrackRecord record : records) {
            if (record.timeSeconds() > eventTime + EPSILON) {
                break;
            }
            if (formation == null) {
                formation = record;
            }
            history.add(record);
            if (record.updated()) {
                lastUpdate = record;
            }
            lastObserved = record;
        }
        if (formation == null || lastUpdate == null || lastObserved == null) {
            return null;
        }
        boolean live = eventTime - lastObserved.timeSeconds() <= LIVE_SAMPLE_TOLERANCE_SECONDS;
        return new Segment(
                trackId,
                formation.timeSeconds(),
                lastUpdate.timeSeconds(),
                lastObserved.timeSeconds(),
                !live,
                live,
                formation,
                lastUpdate,
                lastObserved,
                lastObserved,
                List.copyOf(history));
    }

    private static double kinematicJoinTime(
            TrackRecord oldAnchor,
            TrackRecord newAnchor,
            double bankStart,
            double bankEnd) {
        double[] oldState = oldAnchor.state();
        double[] newState = newAnchor.state();
        double dx = newState[0] - oldState[0];
        double dy = newState[1] - oldState[1];
        double dz = newState[2] - oldState[2];
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double averageVx = (oldState[3] + newState[3]) / 2.0;
        double averageVy = (oldState[4] + newState[4]) / 2.0;
        double averageVz = (oldState[5] + newState[5]) / 2.0;
        double averageSpeed = Math.sqrt(
                averageVx * averageVx + averageVy * averageVy + averageVz * averageVz);
        if (averageSpeed <= EPSILON) {
            return (bankStart + bankEnd) / 2.0;
        }
        double travelSeconds = distance / averageSpeed;
        return clamp(oldAnchor.timeSeconds() + travelSeconds / 2.0, bankStart, bankEnd);
    }

    private static List<Double> timeBank(double start, double end, double resolutionSeconds) {
        double resolution = bankStep(resolutionSeconds);
        List<Double> bank = new ArrayList<>();
        if (start > end + EPSILON) {
            return bank;
        }
        for (double time = start; time < end - EPSILON; time += resolution) {
            bank.add(time);
        }
        bank.add(end);
        return bank;
    }

    private static double bankStep(double resolutionSeconds) {
        return Math.max(0.001, resolutionSeconds);
    }

    private static BankEvaluation bankEvaluation(
            String oldTrackId,
            String newTrackId,
            TrackRecord oldAnchor,
            Segment newSegment,
            double timeSeconds,
            Configuration configuration,
            BirthDensityField learnedBirthDensityField) {
        PropagatedState oldState = predictOld(oldAnchor, timeSeconds);
        PropagatedState newState = retrodictNew(newSegment, timeSeconds);
        InnovationScore score = innovationScore(oldState, newState);
        double nll = canonicalNegativeLogLikelihood(score);
        double innovationVolume = innovationVolumeCubicKilometers(score.innovationCovariance());
        double staticDensity = configuration.falseAlarmRatePerCubicKilometer()
                + configuration.birthRatePerCubicKilometer();
        double staticLambdaEx = lambdaEx(staticDensity, innovationVolume);
        BirthDensityQuery learnedQuery = learnedBirthDensityField.queryExpectedBirths(
                queryCenter(oldState.state(), newState.state()),
                score.innovationCovariance(),
                innovationVolume);
        double learnedLambdaEx = Math.max(MINIMUM_LAMBDA_EX, learnedQuery.expectedBirths());
        return new BankEvaluation(
                oldTrackId,
                newTrackId,
                timeSeconds,
                oldState.state(),
                oldState.covariance(),
                newState.state(),
                newState.covariance(),
                score.innovation(),
                score.innovationCovariance(),
                score.mahalanobisDistance(),
                score.innovationQuadratic(),
                score.logDeterminant(),
                nll,
                innovationVolume,
                staticLambdaEx,
                nll + Math.log(staticLambdaEx),
                learnedQuery.densityPerCubicKilometer(),
                learnedQuery.expectedBirths(),
                learnedQuery.exposureScanCubicKilometers(),
                learnedQuery.reliability(),
                learnedQuery.sigmaMeters(),
                nll + Math.log(learnedLambdaEx));
    }

    private static JoinEvaluation joinEvaluation(
            String variant,
            TrackRecord oldAnchor,
            Segment newSegment,
            double timeSeconds,
            Configuration configuration,
            BirthDensityField learnedBirthDensityField) {
        PropagatedState oldState = predictOld(oldAnchor, timeSeconds);
        PropagatedState newState = retrodictNew(newSegment, timeSeconds);
        InnovationScore score = innovationScore(oldState, newState);
        DistributionScore distributionScore3d =
                distributionScore(oldState, newState, POSITION_SIZE);
        DistributionScore distributionScore6d =
                distributionScore(oldState, newState, POSITION_VELOCITY_SIZE);
        double nll = canonicalNegativeLogLikelihood(score);
        double innovationVolume = innovationVolumeCubicKilometers(score.innovationCovariance());
        double staticDensity = configuration.falseAlarmRatePerCubicKilometer()
                + configuration.birthRatePerCubicKilometer();
        double staticNllr = nll + Math.log(lambdaEx(staticDensity, innovationVolume));
        BirthDensityQuery learnedQuery = learnedBirthDensityField.queryExpectedBirths(
                queryCenter(oldState.state(), newState.state()),
                score.innovationCovariance(),
                innovationVolume);
        double learnedNllr = nll + Math.log(Math.max(
                MINIMUM_LAMBDA_EX, learnedQuery.expectedBirths()));
        return new JoinEvaluation(
                variant,
                timeSeconds,
                oldState.state(),
                oldState.covariance(),
                newState.state(),
                newState.covariance(),
                score.innovation(),
                score.innovationCovariance(),
                score.mahalanobisDistance(),
                nll,
                innovationVolume,
                staticNllr,
                learnedNllr,
                distributionScore3d.bhattacharyyaDistance(),
                distributionScore3d.bhattacharyyaCoefficient(),
                distributionScore3d.hellingerDistance(),
                distributionScore6d.bhattacharyyaDistance(),
                distributionScore6d.bhattacharyyaCoefficient(),
                distributionScore6d.hellingerDistance());
    }

    private static double lambdaEx(double densityPerCubicKilometer, double volumeCubicKilometers) {
        return Math.max(MINIMUM_LAMBDA_EX,
                Math.max(0.0, densityPerCubicKilometer)
                        * Math.max(0.0, volumeCubicKilometers));
    }

    private static double[] queryCenter(double[] oldState, double[] newState) {
        return new double[]{
                (oldState[0] + newState[0]) / 2.0,
                (oldState[1] + newState[1]) / 2.0,
                (oldState[2] + newState[2]) / 2.0};
    }

    private static double innovationVolumeCubicKilometers(double[][] innovationCovariance) {
        double[][] positionCovariance = new double[3][3];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(innovationCovariance[row], 0, positionCovariance[row], 0, 3);
        }
        double determinant = LinearAlgebra.determinant(positionCovariance);
        double volumeCubicMeters = 4.0 / 3.0 * Math.PI
                * Math.sqrt(Math.max(0.0, determinant));
        if (!Double.isFinite(volumeCubicMeters)) {
            return 0.0;
        }
        return volumeCubicMeters / CUBIC_METERS_PER_CUBIC_KILOMETER;
    }

    static double canonicalNegativeLogLikelihood(InnovationScore score) {
        double logLikelihood = -0.5 * (POSITION_SIZE * Math.log(2.0 * Math.PI)
                + score.logDeterminant()
                + score.innovationQuadratic());
        return -logLikelihood;
    }

    static InnovationScore innovationScore(
            PropagatedState oldState,
            PropagatedState newState) {
        double[] oldMean = oldState.state();
        double[] newMean = newState.state();
        double[][] oldCovariance = oldState.covariance();
        double[][] newCovariance = newState.covariance();
        double[] innovation = new double[POSITION_SIZE];
        for (int index = 0; index < POSITION_SIZE; index++) {
            innovation[index] = oldMean[index] - newMean[index];
        }
        double[][] covariance = new double[POSITION_SIZE][POSITION_SIZE];
        for (int row = 0; row < POSITION_SIZE; row++) {
            for (int column = 0; column < POSITION_SIZE; column++) {
                covariance[row][column] = oldCovariance[row][column]
                        + newCovariance[row][column];
            }
        }
        LinearAlgebra.GaussianLikelihood likelihood =
                LinearAlgebra.gaussianLikelihood(innovation, covariance);
        return new InnovationScore(
                innovation,
                covariance,
                likelihood.mahalanobisDistance(),
                likelihood.squaredMahalanobisDistance(),
                likelihood.logDeterminant());
    }

    static DistributionScore distributionScore(
            PropagatedState oldState,
            PropagatedState newState) {
        return distributionScore(oldState, newState, POSITION_SIZE);
    }

    static DistributionScore distributionScore(
            PropagatedState oldState,
            PropagatedState newState,
            int dimension) {
        if (dimension != POSITION_SIZE && dimension != POSITION_VELOCITY_SIZE) {
            throw new IllegalArgumentException("Overlap dimension must be 3D or 6D");
        }
        double[] oldMean = leadingStateVector(oldState.state(), dimension);
        double[] newMean = leadingStateVector(newState.state(), dimension);
        double[][] oldCovariance = leadingStateCovariance(oldState.covariance(), dimension);
        double[][] newCovariance = leadingStateCovariance(newState.covariance(), dimension);
        double[][] averageCovariance = LinearAlgebra.scale(
                0.5, LinearAlgebra.add(oldCovariance, newCovariance));
        double[] difference = LinearAlgebra.subtract(oldMean, newMean);
        LinearAlgebra.SpdSolve averageSolve =
                LinearAlgebra.solveSpd(averageCovariance, difference);
        double quadratic = Math.max(
                0.0, LinearAlgebra.dot(difference, averageSolve.solution()));
        double oldLogDeterminant =
                LinearAlgebra.solveSpd(oldCovariance, new double[dimension])
                        .logDeterminant();
        double newLogDeterminant =
                LinearAlgebra.solveSpd(newCovariance, new double[dimension])
                        .logDeterminant();
        double determinantTerm = 0.5 * (
                averageSolve.logDeterminant()
                        - 0.5 * (oldLogDeterminant + newLogDeterminant));
        double distance = Math.max(0.0, 0.125 * quadratic + determinantTerm);
        double coefficient = Math.exp(-Math.min(745.0, distance));
        coefficient = Math.max(0.0, Math.min(1.0, coefficient));
        double hellinger = Math.sqrt(Math.max(0.0, 1.0 - coefficient));
        return new DistributionScore(distance, coefficient, hellinger);
    }

    private static double[] leadingStateVector(double[] state, int dimension) {
        double[] result = new double[dimension];
        System.arraycopy(state, 0, result, 0, dimension);
        return result;
    }

    private static double[][] leadingStateCovariance(double[][] covariance, int dimension) {
        double[][] result = new double[dimension][dimension];
        for (int row = 0; row < dimension; row++) {
            System.arraycopy(covariance[row], 0, result[row], 0, dimension);
        }
        return result;
    }

    private static PropagatedState predictOld(TrackRecord record, double wantedTime) {
        return propagate(
                new PropagatedState(record.state(), record.covariance()),
                record.timeSeconds(),
                wantedTime);
    }

    static PropagatedState retrodictNew(
            Segment newSegment,
            double wantedTime) {
        TrackRecord futureRecord = newSegment.lastUpdateRecord();
        return propagate(
                new PropagatedState(futureRecord.state(), futureRecord.covariance()),
                futureRecord.timeSeconds(),
                wantedTime);
    }

    static PropagatedState propagate(
            PropagatedState source,
            double sourceTime,
            double wantedTime) {
        double dt = wantedTime - sourceTime;
        double[] sourceState = source.state();
        double[][] sourceCovariance = source.covariance();
        double[][] transition = stitchTransition(dt);
        double[] propagatedState = LinearAlgebra.multiply(transition, sourceState);
        double[][] propagatedCovariance = LinearAlgebra.add(
                LinearAlgebra.multiply(
                        LinearAlgebra.multiply(transition, sourceCovariance),
                        LinearAlgebra.transpose(transition)),
                processCovariance(dt));
        return new PropagatedState(
                propagatedState,
                LinearAlgebra.symmetrized(propagatedCovariance, 1.0e-12));
    }

    private static double[][] stitchTransition(double dt) {
        double[][] transition = LinearAlgebra.identity(STATE_SIZE);
        for (int axis = 0; axis < 3; axis++) {
            int position = axis;
            int velocity = axis + 3;
            int acceleration = axis + 6;
            transition[position][velocity] = dt;
            transition[acceleration][acceleration] = 0.0;
        }
        return transition;
    }

    private static double[][] processCovariance(double dt) {
        double[][] covariance = new double[STATE_SIZE][STATE_SIZE];
        double interval = Math.abs(dt);
        double interval2 = interval * interval;
        double interval3 = interval2 * interval;
        double q = STITCHING_PROCESS_NOISE;
        double signedPositionVelocity = Math.copySign(
                q * interval2 / 2.0,
                dt == 0.0 ? 1.0 : dt);
        for (int axis = 0; axis < 3; axis++) {
            int position = axis;
            int velocity = axis + 3;
            int acceleration = axis + 6;
            covariance[position][position] = q * interval3 / 3.0;
            covariance[position][velocity] = signedPositionVelocity;
            covariance[velocity][position] = covariance[position][velocity];
            covariance[velocity][velocity] = q * interval;
            covariance[acceleration][acceleration] = 1.0e-9;
        }
        return covariance;
    }

    private static TruthScore truthScore(
            double timeSeconds,
            double[] oldState,
            double[] newState,
            Map<String, List<GroundTruthRecord>> truth) {
        TruthScore best = null;
        for (Map.Entry<String, List<GroundTruthRecord>> entry : truth.entrySet()) {
            double[] truthState = interpolateTruth(entry.getValue(), timeSeconds);
            if (truthState == null) {
                continue;
            }
            double oldDistanceSquared = positionDistanceSquared(oldState, truthState);
            double newDistanceSquared = positionDistanceSquared(newState, truthState);
            double rms = Math.sqrt((oldDistanceSquared + newDistanceSquared) / 2.0);
            if (best == null || rms < best.score()) {
                best = new TruthScore(timeSeconds, rms, entry.getKey());
            }
        }
        return best;
    }

    private static double[] interpolateTruth(
            List<GroundTruthRecord> records,
            double timeSeconds) {
        if (records.isEmpty()
                || timeSeconds < records.get(0).timeSeconds() - EPSILON
                || timeSeconds > records.get(records.size() - 1).timeSeconds() + EPSILON) {
            return null;
        }
        int low = 0;
        int upper = records.size() - 1;
        while (low <= upper) {
            int middle = (low + upper) >>> 1;
            if (records.get(middle).timeSeconds() < timeSeconds) {
                low = middle + 1;
            } else {
                upper = middle - 1;
            }
        }
        int high = low;
        if (high == 0) {
            return records.get(0).state();
        }
        if (high >= records.size()) {
            return records.get(records.size() - 1).state();
        }
        GroundTruthRecord before = records.get(high - 1);
        GroundTruthRecord after = records.get(high);
        double span = Math.max(EPSILON, after.timeSeconds() - before.timeSeconds());
        double fraction = (timeSeconds - before.timeSeconds()) / span;
        double[] left = before.state();
        double[] right = after.state();
        double[] state = new double[STATE_SIZE];
        for (int index = 0; index < STATE_SIZE; index++) {
            state[index] = left[index] + fraction * (right[index] - left[index]);
        }
        return state;
    }

    private static double bestTwoMidpoint(List<ScoredTime> scores, double fallback) {
        return scores.stream()
                .sorted(Comparator.comparingDouble(ScoredTime::score))
                .limit(2)
                .mapToDouble(ScoredTime::timeSeconds)
                .average()
                .orElse(fallback);
    }

    private static double bestTwoTruthMidpoint(List<TruthScore> scores, double fallback) {
        return scores.stream()
                .sorted(Comparator.comparingDouble(TruthScore::score))
                .limit(2)
                .mapToDouble(TruthScore::timeSeconds)
                .average()
                .orElse(fallback);
    }

    private static List<BirthPoint> birthPoints(
            Map<String, List<TrackRecord>> tracks,
            Map<String, List<RecordedMeasurement>> measurementsByTrack) {
        List<BirthPoint> births = new ArrayList<>();
        for (Map.Entry<String, List<TrackRecord>> entry : tracks.entrySet()) {
            List<RecordedMeasurement> measurements = measurementsByTrack.get(entry.getKey());
            if (measurements == null || measurements.isEmpty()) {
                continue;
            }
            RecordedMeasurement birth = measurements.get(0);
            double[] mean = birth.mean();
            births.add(new BirthPoint(
                    birth.timeSeconds(),
                    mean[0],
                    mean[1],
                    mean[2],
                    measurementUncertaintyMeters(birth),
                    birthConfidence(entry.getKey(), birth, tracks)));
        }
        births.sort(Comparator.comparingDouble(BirthPoint::timeSeconds));
        return List.copyOf(births);
    }

    private static double measurementUncertaintyMeters(RecordedMeasurement birth) {
        double[][] covariance = birth.covariance();
        double variance = Math.max(0.0,
                covariance[0][0] + covariance[1][1] + covariance[2][2]);
        return Math.max(250.0, Math.min(MAXIMUM_SPATIAL_CELL_METERS, Math.sqrt(variance)));
    }

    private static double birthConfidence(
            String birthTrackId,
            RecordedMeasurement birth,
            Map<String, List<TrackRecord>> tracks) {
        double bestCompatibility = Double.POSITIVE_INFINITY;
        double[] mean = birth.mean();
        for (Map.Entry<String, List<TrackRecord>> entry : tracks.entrySet()) {
            if (entry.getKey().equals(birthTrackId)) {
                continue;
            }
            TrackRecord prior = latestUpdatedBefore(entry.getValue(), birth.timeSeconds());
            if (prior == null) {
                continue;
            }
            PropagatedState prediction = predictOld(prior, birth.timeSeconds());
            double[] measurementState = {
                    mean[0], mean[1], mean[2], mean[3], mean[4], mean[5], 0.0, 0.0, 0.0};
            double distance = Math.sqrt(positionDistanceSquared(
                    prediction.state(), measurementState));
            double[][] measurementCovariance = birth.covariance();
            double[][] predictionCovariance = prediction.covariance();
            double oneSigma = Math.sqrt(Math.max(1.0,
                    predictionCovariance[0][0] + predictionCovariance[1][1]
                            + predictionCovariance[2][2]
                            + measurementCovariance[0][0]
                            + measurementCovariance[1][1]
                            + measurementCovariance[2][2]));
            bestCompatibility = Math.min(bestCompatibility,
                    distance / Math.max(500.0, 3.0 * oneSigma));
        }
        if (!Double.isFinite(bestCompatibility)) {
            return 1.0;
        }
        if (bestCompatibility < 1.0) {
            return 0.10;
        }
        if (bestCompatibility < 2.0) {
            return 0.35;
        }
        if (bestCompatibility < 3.0) {
            return 0.65;
        }
        return 1.0;
    }

    private static TrackRecord latestUpdatedBefore(
            List<TrackRecord> records,
            double timeSeconds) {
        TrackRecord latest = null;
        for (TrackRecord record : records) {
            if (record.timeSeconds() >= timeSeconds - EPSILON) {
                break;
            }
            if (record.updated()) {
                latest = record;
            }
        }
        return latest;
    }

    private static Bounds operatingBounds(
            Map<String, List<TrackRecord>> tracks,
            List<RecordedMeasurement> measurements,
            List<BirthPoint> births) {
        MutableBounds bounds = new MutableBounds();
        for (List<TrackRecord> records : tracks.values()) {
            for (TrackRecord record : records) {
                double[] state = record.state();
                bounds.include(state[0], state[1], state[2]);
            }
        }
        for (RecordedMeasurement measurement : measurements) {
            double[] mean = measurement.mean();
            bounds.include(mean[0], mean[1], mean[2]);
        }
        for (BirthPoint birth : births) {
            bounds.include(birth.xMeters(), birth.yMeters(), birth.zMeters());
        }
        return bounds.toBounds();
    }

    private static Map<String, List<TrackRecord>> groupTracks(List<TrackRecord> records) {
        Map<String, List<TrackRecord>> grouped = new LinkedHashMap<>();
        records.forEach(record -> grouped.computeIfAbsent(
                record.trackId(), ignored -> new ArrayList<>()).add(record));
        grouped.values().forEach(history -> history.sort(
                Comparator.comparingDouble(TrackRecord::timeSeconds)));
        return grouped;
    }

    private static Map<String, List<RecordedMeasurement>> associateMeasurements(
            Map<String, List<TrackRecord>> tracks,
            List<RecordedMeasurement> measurements) {
        Map<String, List<RecordedMeasurement>> associated = new LinkedHashMap<>();
        tracks.keySet().forEach(trackId -> associated.put(trackId, new ArrayList<>()));
        for (RecordedMeasurement measurement : measurements) {
            String trackId = measurement.associatedTrackId();
            if (trackId.isBlank() || !tracks.containsKey(trackId)) {
                trackId = nearestUpdatedTrack(tracks, measurement);
            }
            if (trackId != null) {
                associated.computeIfAbsent(trackId, ignored -> new ArrayList<>())
                        .add(measurement);
            }
        }
        associated.values().forEach(history -> history.sort(
                Comparator.comparingDouble(RecordedMeasurement::timeSeconds)));
        return associated;
    }

    private static String nearestUpdatedTrack(
            Map<String, List<TrackRecord>> tracks,
            RecordedMeasurement measurement) {
        String bestTrack = null;
        double bestScore = Double.POSITIVE_INFINITY;
        double[] mean = measurement.mean();
        for (Map.Entry<String, List<TrackRecord>> entry : tracks.entrySet()) {
            for (TrackRecord record : entry.getValue()) {
                if (!record.updated()
                        || Math.abs(record.timeSeconds() - measurement.timeSeconds()) > 1.0e-6) {
                    continue;
                }
                double[] state = record.state();
                double[][] trackCovariance = record.covariance();
                double[][] measurementCovariance = measurement.covariance();
                double score = 0.0;
                for (int index = 0; index < MEASUREMENT_SIZE; index++) {
                    double variance = Math.max(1.0e-9,
                            trackCovariance[index][index]
                                    + measurementCovariance[index][index]);
                    double residual = state[index] - mean[index];
                    score += residual * residual / variance;
                }
                if (score < bestScore) {
                    bestScore = score;
                    bestTrack = entry.getKey();
                }
            }
        }
        return bestTrack;
    }

    private static Map<String, List<GroundTruthRecord>> groupTruth(
            List<GroundTruthRecord> records) {
        Map<String, List<GroundTruthRecord>> grouped = new LinkedHashMap<>();
        records.forEach(record -> grouped.computeIfAbsent(
                record.targetId(), ignored -> new ArrayList<>()).add(record));
        grouped.values().forEach(history -> history.sort(
                Comparator.comparingDouble(GroundTruthRecord::timeSeconds)));
        return grouped;
    }

    private static boolean inWindow(double value, double minimum, double maximum) {
        return value + EPSILON >= minimum && value <= maximum + EPSILON;
    }

    private static double positionDistanceSquared(double[] left, double[] right) {
        double dx = left[0] - right[0];
        double dy = left[1] - right[1];
        double dz = left[2] - right[2];
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double[] copyVector(double[] source, int size) {
        if (source == null || source.length != size) {
            throw new IllegalArgumentException("Unexpected vector size");
        }
        return source.clone();
    }

    private static double[][] copySquare(double[][] source, int size) {
        if (source == null || source.length != size) {
            throw new IllegalArgumentException("Unexpected matrix size");
        }
        double[][] copy = new double[size][size];
        for (int row = 0; row < size; row++) {
            if (source[row] == null || source[row].length != size) {
                throw new IllegalArgumentException("Unexpected matrix size");
            }
            System.arraycopy(source[row], 0, copy[row], 0, size);
        }
        return copy;
    }

    public record Configuration(
            double coastedMinimumSeconds,
            double coastedMaximumSeconds,
            double newMinimumSeconds,
            double newMaximumSeconds,
            boolean allowDeadTracks,
            double resolutionSeconds,
            double falseAlarmRatePerCubicKilometer,
            double birthRatePerCubicKilometer) {
        public Configuration(
                double coastedMinimumSeconds,
                double coastedMaximumSeconds,
                double newMinimumSeconds,
                double newMaximumSeconds,
                boolean allowDeadTracks,
                double resolutionSeconds) {
            this(coastedMinimumSeconds, coastedMaximumSeconds,
                    newMinimumSeconds, newMaximumSeconds, allowDeadTracks,
                    resolutionSeconds, 1.0e-6, 1.0e-6);
        }

        public Configuration {
            if (!Double.isFinite(coastedMinimumSeconds)
                    || !Double.isFinite(coastedMaximumSeconds)
                    || !Double.isFinite(newMinimumSeconds)
                    || !Double.isFinite(newMaximumSeconds)
                    || coastedMinimumSeconds < 0.0
                    || coastedMaximumSeconds < coastedMinimumSeconds
                    || newMinimumSeconds < 0.0
                    || newMaximumSeconds < newMinimumSeconds
                    || !Double.isFinite(resolutionSeconds)
                    || resolutionSeconds <= 0.0
                    || !Double.isFinite(falseAlarmRatePerCubicKilometer)
                    || falseAlarmRatePerCubicKilometer < 0.0
                    || !Double.isFinite(birthRatePerCubicKilometer)
                    || birthRatePerCubicKilometer < 0.0) {
                throw new IllegalArgumentException("Invalid stitching time-window configuration");
            }
        }
    }

    public record AnalysisResult(
            List<EventResult> events,
            List<SpatialDensitySnapshot> spatialDensityHistory) {
        public AnalysisResult {
            events = events == null ? List.of() : List.copyOf(events);
            spatialDensityHistory = spatialDensityHistory == null
                    ? List.of()
                    : List.copyOf(spatialDensityHistory);
        }
    }

    public record Segment(
            String trackId,
            double formationTimeSeconds,
            double lastUpdateTimeSeconds,
            double lastObservedTimeSeconds,
            boolean deadAtEvent,
            boolean liveAtEvent,
            TrackRecord formationRecord,
            TrackRecord lastUpdateRecord,
            TrackRecord lastObservedRecord,
            TrackRecord mostFutureRecord,
            List<TrackRecord> history) {
    }

    public record EventResult(
            double timeSeconds,
            List<Segment> allSegments,
            List<Segment> oldSegments,
            List<Segment> newSegments,
            List<PairResult> pairs,
            List<OptimalAssignment> bhattacharyyaDistanceAssignments,
            List<OptimalAssignment> bhattacharyyaCoefficientAssignments,
            List<OptimalAssignment> hellingerDistanceAssignments,
            List<OptimalAssignment> sixDimensionalBhattacharyyaDistanceAssignments,
            List<OptimalAssignment> sixDimensionalBhattacharyyaCoefficientAssignments,
            List<OptimalAssignment> sixDimensionalHellingerDistanceAssignments,
            List<OptimalAssignment> nllAssignments,
            List<OptimalAssignment> mahalanobisAssignments,
            List<OptimalAssignment> staticNllrAssignments,
            List<OptimalAssignment> learnedNllrAssignments,
            double learnedBirthDensityPerCubicKilometer,
            List<PairDiagnostics> diagnostics) {
        public EventResult {
            allSegments = allSegments == null ? List.of() : List.copyOf(allSegments);
            oldSegments = oldSegments == null ? List.of() : List.copyOf(oldSegments);
            newSegments = newSegments == null ? List.of() : List.copyOf(newSegments);
            pairs = pairs == null ? List.of() : List.copyOf(pairs);
            bhattacharyyaDistanceAssignments = bhattacharyyaDistanceAssignments == null
                    ? List.of()
                    : List.copyOf(bhattacharyyaDistanceAssignments);
            bhattacharyyaCoefficientAssignments = bhattacharyyaCoefficientAssignments == null
                    ? List.of()
                    : List.copyOf(bhattacharyyaCoefficientAssignments);
            hellingerDistanceAssignments = hellingerDistanceAssignments == null
                    ? List.of()
                    : List.copyOf(hellingerDistanceAssignments);
            sixDimensionalBhattacharyyaDistanceAssignments =
                    sixDimensionalBhattacharyyaDistanceAssignments == null
                            ? List.of()
                            : List.copyOf(sixDimensionalBhattacharyyaDistanceAssignments);
            sixDimensionalBhattacharyyaCoefficientAssignments =
                    sixDimensionalBhattacharyyaCoefficientAssignments == null
                            ? List.of()
                            : List.copyOf(sixDimensionalBhattacharyyaCoefficientAssignments);
            sixDimensionalHellingerDistanceAssignments =
                    sixDimensionalHellingerDistanceAssignments == null
                            ? List.of()
                            : List.copyOf(sixDimensionalHellingerDistanceAssignments);
            nllAssignments = nllAssignments == null ? List.of() : List.copyOf(nllAssignments);
            mahalanobisAssignments = mahalanobisAssignments == null
                    ? List.of()
                    : List.copyOf(mahalanobisAssignments);
            staticNllrAssignments = staticNllrAssignments == null
                    ? List.of()
                    : List.copyOf(staticNllrAssignments);
            learnedNllrAssignments = learnedNllrAssignments == null
                    ? List.of()
                    : List.copyOf(learnedNllrAssignments);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    public record PairResult(
            String oldTrackId,
            String newTrackId,
            String truthTargetId,
            double simpleJoinTimeSeconds,
            double kinematicJoinTimeSeconds,
            double statisticalJoinTimeSeconds,
            double actualJoinTimeSeconds,
            double simpleBhattacharyyaDistance,
            double kinematicBhattacharyyaDistance,
            double statisticalBhattacharyyaDistance,
            double actualBhattacharyyaDistance,
            double simpleBhattacharyyaCoefficient,
            double kinematicBhattacharyyaCoefficient,
            double statisticalBhattacharyyaCoefficient,
            double actualBhattacharyyaCoefficient,
            double simpleHellingerDistance,
            double kinematicHellingerDistance,
            double statisticalHellingerDistance,
            double actualHellingerDistance,
            double simpleBhattacharyyaDistance6d,
            double kinematicBhattacharyyaDistance6d,
            double statisticalBhattacharyyaDistance6d,
            double actualBhattacharyyaDistance6d,
            double simpleBhattacharyyaCoefficient6d,
            double kinematicBhattacharyyaCoefficient6d,
            double statisticalBhattacharyyaCoefficient6d,
            double actualBhattacharyyaCoefficient6d,
            double simpleHellingerDistance6d,
            double kinematicHellingerDistance6d,
            double statisticalHellingerDistance6d,
            double actualHellingerDistance6d,
            double simpleNegativeLogLikelihood,
            double kinematicNegativeLogLikelihood,
            double statisticalNegativeLogLikelihood,
            double actualNegativeLogLikelihood,
            double simpleMahalanobisDistance,
            double kinematicMahalanobisDistance,
            double statisticalMahalanobisDistance,
            double actualMahalanobisDistance,
            double simpleStaticNegativeLogLikelihoodRatio,
            double kinematicStaticNegativeLogLikelihoodRatio,
            double statisticalStaticNegativeLogLikelihoodRatio,
            double actualStaticNegativeLogLikelihoodRatio,
            double simpleLearnedNegativeLogLikelihoodRatio,
            double kinematicLearnedNegativeLogLikelihoodRatio,
            double statisticalLearnedNegativeLogLikelihoodRatio,
            double actualLearnedNegativeLogLikelihoodRatio) {
    }

    public record OptimalAssignment(
            String metric,
            String oldTrackId,
            String newTrackId,
            String variant,
            double joinTimeSeconds,
            double score) {
    }

    public record PairDiagnostics(
            PairResult result,
            List<JoinEvaluation> joinEvaluations,
            List<BankEvaluation> bankEvaluations) {
        public PairDiagnostics {
            if (result == null) {
                throw new IllegalArgumentException("Pair result is required");
            }
            joinEvaluations = joinEvaluations == null ? List.of() : List.copyOf(joinEvaluations);
            bankEvaluations = bankEvaluations == null ? List.of() : List.copyOf(bankEvaluations);
        }
    }

    public record JoinEvaluation(
            String variant,
            double timeSeconds,
            double[] oldState,
            double[][] oldCovariance,
            double[] newState,
            double[][] newCovariance,
            double[] innovation,
            double[][] innovationCovariance,
            double mahalanobisDistance,
            double negativeLogLikelihood,
            double innovationVolumeCubicKilometers,
            double staticNegativeLogLikelihoodRatio,
            double learnedNegativeLogLikelihoodRatio,
            double bhattacharyyaDistance,
            double bhattacharyyaCoefficient,
            double hellingerDistance,
            double bhattacharyyaDistance6d,
            double bhattacharyyaCoefficient6d,
            double hellingerDistance6d) {
        public JoinEvaluation {
            variant = variant == null ? "" : variant;
            oldState = copyVector(oldState, STATE_SIZE);
            oldCovariance = copySquare(oldCovariance, STATE_SIZE);
            newState = copyVector(newState, STATE_SIZE);
            newCovariance = copySquare(newCovariance, STATE_SIZE);
            innovation = copyVector(innovation, POSITION_SIZE);
            innovationCovariance = copySquare(innovationCovariance, POSITION_SIZE);
        }

        static JoinEvaluation nan(String variant) {
            return new JoinEvaluation(
                    variant,
                    Double.NaN,
                    new double[STATE_SIZE],
                    new double[STATE_SIZE][STATE_SIZE],
                    new double[STATE_SIZE],
                    new double[STATE_SIZE][STATE_SIZE],
                    new double[POSITION_SIZE],
                    new double[POSITION_SIZE][POSITION_SIZE],
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN);
        }

        @Override
        public double[] oldState() {
            return oldState.clone();
        }

        @Override
        public double[][] oldCovariance() {
            return copySquare(oldCovariance, STATE_SIZE);
        }

        @Override
        public double[] newState() {
            return newState.clone();
        }

        @Override
        public double[][] newCovariance() {
            return copySquare(newCovariance, STATE_SIZE);
        }

        @Override
        public double[] innovation() {
            return innovation.clone();
        }

        @Override
        public double[][] innovationCovariance() {
            return copySquare(innovationCovariance, POSITION_SIZE);
        }
    }

    public record BankEvaluation(
            String oldTrackId,
            String newTrackId,
            double timeSeconds,
            double[] oldState,
            double[][] oldCovariance,
            double[] newState,
            double[][] newCovariance,
            double[] innovation,
            double[][] innovationCovariance,
            double mahalanobisDistance,
            double innovationQuadratic,
            double logDeterminant,
            double negativeLogLikelihood,
            double innovationVolumeCubicKilometers,
            double staticLambdaEx,
            double staticNegativeLogLikelihoodRatio,
            double learnedBirthDensityPerCubicKilometer,
            double learnedExpectedBirths,
            double learnedExposureScanCubicKilometers,
            double learnedReliability,
            double learnedQuerySigmaMeters,
            double learnedNegativeLogLikelihoodRatio) {
        public BankEvaluation {
            oldState = copyVector(oldState, STATE_SIZE);
            oldCovariance = copySquare(oldCovariance, STATE_SIZE);
            newState = copyVector(newState, STATE_SIZE);
            newCovariance = copySquare(newCovariance, STATE_SIZE);
            innovation = copyVector(innovation, POSITION_SIZE);
            innovationCovariance = copySquare(innovationCovariance, POSITION_SIZE);
        }

        @Override
        public double[] oldState() {
            return oldState.clone();
        }

        @Override
        public double[][] oldCovariance() {
            return copySquare(oldCovariance, STATE_SIZE);
        }

        @Override
        public double[] newState() {
            return newState.clone();
        }

        @Override
        public double[][] newCovariance() {
            return copySquare(newCovariance, STATE_SIZE);
        }

        @Override
        public double[] innovation() {
            return innovation.clone();
        }

        @Override
        public double[][] innovationCovariance() {
            return copySquare(innovationCovariance, POSITION_SIZE);
        }
    }

    public record SpatialDensitySnapshot(
            double timeSeconds,
            double representativeDensityPerCubicKilometer,
            double peakDensityPerCubicKilometer,
            double meanDensityPerCubicKilometer,
            double totalBirthEvidence,
            double totalExposureScanCubicKilometers,
            double meanReliability,
            double priorDensityPerCubicKilometer,
            double cellMeters,
            int xCells,
            int yCells,
            int zCells,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
    }

    record PropagatedState(double[] state, double[][] covariance) {
        PropagatedState {
            state = copyVector(state, STATE_SIZE);
            covariance = copySquare(covariance, STATE_SIZE);
        }

        @Override
        public double[] state() {
            return state.clone();
        }

        @Override
        public double[][] covariance() {
            return copySquare(covariance, STATE_SIZE);
        }
    }

    record InnovationScore(
            double[] innovation,
            double[][] innovationCovariance,
            double mahalanobisDistance,
            double innovationQuadratic,
            double logDeterminant) {
    }

    record DistributionScore(
            double bhattacharyyaDistance,
            double bhattacharyyaCoefficient,
            double hellingerDistance) {
    }

    private record ScoredTime(double timeSeconds, double score) {
    }

    private record TruthScore(double timeSeconds, double score, String targetId) {
    }

    private record BirthDensityQuery(
            double densityPerCubicKilometer,
            double expectedBirths,
            double exposureScanCubicKilometers,
            double reliability,
            double sigmaMeters) {
    }

    private enum Metric {
        BHATTACHARYYA_DISTANCE("Bhattacharyya Distance"),
        BHATTACHARYYA_COEFFICIENT("Bhattacharyya Coefficient"),
        HELLINGER_DISTANCE("Hellinger Distance"),
        BHATTACHARYYA_DISTANCE_6D("6D Bhattacharyya Distance"),
        BHATTACHARYYA_COEFFICIENT_6D("6D Bhattacharyya Coefficient"),
        HELLINGER_DISTANCE_6D("6D Hellinger Distance"),
        NLL("NLL"),
        MAHALANOBIS("Mahalanobis"),
        STATIC_NLLR("Static/uniform NLLR"),
        LEARNED_NLLR("Learned spatial NLLR");

        private final String displayName;

        Metric(String displayName) {
            this.displayName = displayName;
        }

        String displayName() {
            return displayName;
        }
    }

    private record VariantScore(
            String variant,
            double joinTimeSeconds,
            double score,
            double cost) {
    }

    private record BirthPoint(
            double timeSeconds,
            double xMeters,
            double yMeters,
            double zMeters,
            double uncertaintyMeters,
            double confidence) {
    }

    private static final class BirthDensityField {
        private final List<BirthPoint> births;
        private final double priorDensityPerCubicKilometer;
        private final Bounds bounds;
        private final double cellMeters;
        private final double cellVolumeCubicKilometers;
        private final int xCells;
        private final int yCells;
        private final int zCells;
        private final double maturityDelaySeconds;
        private final double[] birthEvidence;
        private final double[] exposure;
        private int nextBirthIndex;
        private double currentTimeSeconds;

        private BirthDensityField(
                List<BirthPoint> births,
                double priorDensityPerCubicKilometer,
                Bounds bounds,
                double cellMeters,
                double maturityDelaySeconds) {
            this.births = births;
            this.priorDensityPerCubicKilometer = clampDensity(priorDensityPerCubicKilometer);
            this.bounds = bounds;
            this.cellMeters = cellMeters;
            this.cellVolumeCubicKilometers = Math.pow(cellMeters / 1_000.0, 3.0);
            this.xCells = Math.max(1, (int) Math.ceil(bounds.widthMeters() / cellMeters));
            this.yCells = Math.max(1, (int) Math.ceil(bounds.depthMeters() / cellMeters));
            this.zCells = Math.max(1, (int) Math.ceil(bounds.heightMeters() / cellMeters));
            this.maturityDelaySeconds = Math.max(0.0, maturityDelaySeconds);
            this.birthEvidence = new double[xCells * yCells * zCells];
            this.exposure = new double[birthEvidence.length];
            initializePrior();
        }

        static BirthDensityField create(
                Map<String, List<TrackRecord>> tracks,
                Map<String, List<RecordedMeasurement>> measurementsByTrack,
                List<RecordedMeasurement> measurements,
                Configuration configuration) {
            List<BirthPoint> births = birthPoints(tracks, measurementsByTrack);
            Bounds rawBounds = operatingBounds(tracks, measurements, births);
            double span = Math.max(rawBounds.maxX() - rawBounds.minX(),
                    Math.max(rawBounds.maxY() - rawBounds.minY(),
                            rawBounds.maxZ() - rawBounds.minZ()));
            double cellMeters = Math.max(MINIMUM_SPATIAL_CELL_METERS,
                    Math.min(MAXIMUM_SPATIAL_CELL_METERS, span / TARGET_GRID_AXIS_CELLS));
            Bounds paddedBounds = rawBounds.padded(Math.max(3.0 * cellMeters, 2_000.0));
            return new BirthDensityField(
                    births,
                    configuration.birthRatePerCubicKilometer(),
                    paddedBounds,
                    cellMeters,
                    configuration.newMaximumSeconds());
        }

        void advanceTo(double eventTimeSeconds) {
            double deltaTimeSeconds = Math.max(0.0, eventTimeSeconds - currentTimeSeconds);
            applyForgetting(deltaTimeSeconds);
            addObservableExposure();
            while (nextBirthIndex < births.size()
                    && births.get(nextBirthIndex).timeSeconds() + maturityDelaySeconds
                            < eventTimeSeconds - EPSILON) {
                depositBirthEvidence(births.get(nextBirthIndex));
                nextBirthIndex++;
            }
            currentTimeSeconds = Math.max(currentTimeSeconds, eventTimeSeconds);
        }

        BirthDensityQuery queryExpectedBirths(
                double[] centerMeters,
                double[][] innovationCovariance,
                double innovationVolumeCubicKilometers) {
            if (centerMeters == null || centerMeters.length < 3
                    || innovationVolumeCubicKilometers <= 0.0
                    || !Double.isFinite(innovationVolumeCubicKilometers)) {
                double fallbackVolume = Math.max(1.0e-9, innovationVolumeCubicKilometers);
                return new BirthDensityQuery(
                        priorDensityPerCubicKilometer,
                        Math.max(MINIMUM_LAMBDA_EX,
                                priorDensityPerCubicKilometer * fallbackVolume),
                        PRIOR_EXPOSURE_SCANS * fallbackVolume,
                        0.0,
                        cellMeters);
            }
            double equivalentRadiusKilometers = Math.cbrt(
                    3.0 * innovationVolumeCubicKilometers / (4.0 * Math.PI));
            double uncertaintyKilometers = positionUncertaintyKilometers(innovationCovariance);
            double querySigmaMeters = Math.max(cellMeters,
                    Math.max(equivalentRadiusKilometers, uncertaintyKilometers) * 1_000.0);
            DensityEstimate estimate = smoothedDensity(centerMeters, querySigmaMeters);
            return new BirthDensityQuery(
                    estimate.densityPerCubicKilometer(),
                    Math.max(MINIMUM_LAMBDA_EX,
                            estimate.densityPerCubicKilometer()
                                    * innovationVolumeCubicKilometers),
                    estimate.exposureScanCubicKilometers(),
                    estimate.reliability(),
                    querySigmaMeters);
        }

        double meanDensityPerCubicKilometer() {
            return representativeDensityPerCubicKilometer();
        }

        SpatialDensitySnapshot snapshot(double timeSeconds) {
            return new SpatialDensitySnapshot(
                    timeSeconds,
                    representativeDensityPerCubicKilometer(),
                    peakDensityPerCubicKilometer(),
                    meanDensityPerCubicKilometerAcrossGrid(),
                    totalBirthEvidence(),
                    totalExposureScanCubicKilometers(),
                    meanReliability(),
                    priorDensityPerCubicKilometer,
                    cellMeters,
                    xCells,
                    yCells,
                    zCells,
                    bounds.minX(),
                    bounds.minY(),
                    bounds.minZ(),
                    bounds.maxX(),
                    bounds.maxY(),
                    bounds.maxZ());
        }

        private double representativeDensityPerCubicKilometer() {
            return Math.max(meanDensityPerCubicKilometerAcrossGrid(), peakDensityPerCubicKilometer());
        }

        private double peakDensityPerCubicKilometer() {
            double peak = priorDensityPerCubicKilometer;
            for (int index = 0; index < birthEvidence.length; index++) {
                peak = Math.max(peak, densityAt(index));
            }
            return clampDensity(peak);
        }

        private double meanDensityPerCubicKilometerAcrossGrid() {
            double weightedDensity = 0.0;
            double totalExposure = 0.0;
            for (int index = 0; index < birthEvidence.length; index++) {
                double cellExposure = exposure[index];
                if (cellExposure <= EPSILON) {
                    continue;
                }
                weightedDensity += densityAt(index) * cellExposure;
                totalExposure += cellExposure;
            }
            return totalExposure <= EPSILON
                    ? priorDensityPerCubicKilometer
                    : clampDensity(weightedDensity / totalExposure);
        }

        private double totalBirthEvidence() {
            double total = 0.0;
            for (double value : birthEvidence) {
                total += value;
            }
            return total;
        }

        private double totalExposureScanCubicKilometers() {
            double total = 0.0;
            for (double value : exposure) {
                total += value;
            }
            return total;
        }

        private double meanReliability() {
            double totalReliability = 0.0;
            for (double value : exposure) {
                totalReliability += reliabilityForExposureScans(
                        value / Math.max(EPSILON, cellVolumeCubicKilometers));
            }
            return exposure.length == 0 ? 0.0 : totalReliability / exposure.length;
        }

        private void initializePrior() {
            double priorExposure = PRIOR_EXPOSURE_SCANS * cellVolumeCubicKilometers;
            double priorEvidence = priorDensityPerCubicKilometer * priorExposure;
            for (int index = 0; index < birthEvidence.length; index++) {
                exposure[index] = priorExposure;
                birthEvidence[index] = priorEvidence;
            }
        }

        private void applyForgetting(double deltaTimeSeconds) {
            if (deltaTimeSeconds <= EPSILON) {
                return;
            }
            double decay = Math.pow(0.5,
                    deltaTimeSeconds / FIELD_FORGETTING_HALF_LIFE_SECONDS);
            for (int index = 0; index < birthEvidence.length; index++) {
                birthEvidence[index] *= decay;
                exposure[index] *= decay;
            }
        }

        private void addObservableExposure() {
            for (int index = 0; index < exposure.length; index++) {
                exposure[index] += cellVolumeCubicKilometers;
            }
        }

        private void depositBirthEvidence(BirthPoint birth) {
            if (birth.confidence() <= EPSILON) {
                return;
            }
            double sigmaMeters = Math.max(cellMeters, birth.uncertaintyMeters());
            int radiusCells = Math.max(1, Math.min(MAXIMUM_SMOOTHING_RADIUS_CELLS,
                    (int) Math.ceil(3.0 * sigmaMeters / cellMeters)));
            int centerX = cellX(birth.xMeters());
            int centerY = cellY(birth.yMeters());
            int centerZ = cellZ(birth.zMeters());
            int minCellX = clampCellX(centerX - radiusCells);
            int maxCellX = clampCellX(centerX + radiusCells);
            int minCellY = clampCellY(centerY - radiusCells);
            int maxCellY = clampCellY(centerY + radiusCells);
            int minCellZ = clampCellZ(centerZ - radiusCells);
            int maxCellZ = clampCellZ(centerZ + radiusCells);
            double weightSum = 0.0;
            for (int z = minCellZ; z <= maxCellZ; z++) {
                for (int y = minCellY; y <= maxCellY; y++) {
                    for (int x = minCellX; x <= maxCellX; x++) {
                        weightSum += gaussianWeight(
                                cellCenterX(x) - birth.xMeters(),
                                cellCenterY(y) - birth.yMeters(),
                                cellCenterZ(z) - birth.zMeters(),
                                sigmaMeters);
                    }
                }
            }
            if (weightSum <= EPSILON) {
                return;
            }
            for (int z = minCellZ; z <= maxCellZ; z++) {
                for (int y = minCellY; y <= maxCellY; y++) {
                    for (int x = minCellX; x <= maxCellX; x++) {
                        double weight = gaussianWeight(
                                cellCenterX(x) - birth.xMeters(),
                                cellCenterY(y) - birth.yMeters(),
                                cellCenterZ(z) - birth.zMeters(),
                                sigmaMeters);
                        birthEvidence[index(x, y, z)] += birth.confidence()
                                * weight / weightSum;
                    }
                }
            }
        }

        private DensityEstimate smoothedDensity(double[] centerMeters, double sigmaMeters) {
            int centerX = cellX(centerMeters[0]);
            int centerY = cellY(centerMeters[1]);
            int centerZ = cellZ(centerMeters[2]);
            if (!inRange(centerX, centerY, centerZ)) {
                return new DensityEstimate(
                        priorDensityPerCubicKilometer,
                        PRIOR_EXPOSURE_SCANS * cellVolumeCubicKilometers,
                        0.0);
            }
            int radiusCells = Math.max(1, Math.min(MAXIMUM_SMOOTHING_RADIUS_CELLS,
                    (int) Math.ceil(3.0 * sigmaMeters / cellMeters)));
            int minCellX = clampCellX(centerX - radiusCells);
            int maxCellX = clampCellX(centerX + radiusCells);
            int minCellY = clampCellY(centerY - radiusCells);
            int maxCellY = clampCellY(centerY + radiusCells);
            int minCellZ = clampCellZ(centerZ - radiusCells);
            int maxCellZ = clampCellZ(centerZ + radiusCells);
            double weightedDensity = 0.0;
            double weightedExposure = 0.0;
            double weightSum = 0.0;
            for (int z = minCellZ; z <= maxCellZ; z++) {
                for (int y = minCellY; y <= maxCellY; y++) {
                    for (int x = minCellX; x <= maxCellX; x++) {
                        double weight = gaussianWeight(
                                cellCenterX(x) - centerMeters[0],
                                cellCenterY(y) - centerMeters[1],
                                cellCenterZ(z) - centerMeters[2],
                                sigmaMeters);
                        int index = index(x, y, z);
                        weightedDensity += densityAt(index) * weight;
                        weightedExposure += exposure[index] * weight;
                        weightSum += weight;
                    }
                }
            }
            if (weightSum <= EPSILON) {
                return new DensityEstimate(
                        priorDensityPerCubicKilometer,
                        PRIOR_EXPOSURE_SCANS * cellVolumeCubicKilometers,
                        0.0);
            }
            double exposureScanCubicKilometers = weightedExposure / weightSum;
            return new DensityEstimate(
                    clampDensity(weightedDensity / weightSum),
                    exposureScanCubicKilometers,
                    reliabilityForExposureScans(
                            exposureScanCubicKilometers
                                    / Math.max(EPSILON, cellVolumeCubicKilometers)));
        }

        private double densityAt(int index) {
            if (index < 0 || index >= birthEvidence.length || exposure[index] <= EPSILON) {
                return priorDensityPerCubicKilometer;
            }
            return clampDensity(birthEvidence[index] / exposure[index]);
        }

        private int index(int x, int y, int z) {
            return (z * yCells + y) * xCells + x;
        }

        private int cellX(double xMeters) {
            return (int) Math.floor((xMeters - bounds.minX()) / cellMeters);
        }

        private int cellY(double yMeters) {
            return (int) Math.floor((yMeters - bounds.minY()) / cellMeters);
        }

        private int cellZ(double zMeters) {
            return (int) Math.floor((zMeters - bounds.minZ()) / cellMeters);
        }

        private boolean inRange(int x, int y, int z) {
            return x >= 0 && x < xCells
                    && y >= 0 && y < yCells
                    && z >= 0 && z < zCells;
        }

        private int clampCellX(int value) {
            return Math.max(0, Math.min(xCells - 1, value));
        }

        private int clampCellY(int value) {
            return Math.max(0, Math.min(yCells - 1, value));
        }

        private int clampCellZ(int value) {
            return Math.max(0, Math.min(zCells - 1, value));
        }

        private double cellCenterX(int x) {
            return bounds.minX() + (x + 0.5) * cellMeters;
        }

        private double cellCenterY(int y) {
            return bounds.minY() + (y + 0.5) * cellMeters;
        }

        private double cellCenterZ(int z) {
            return bounds.minZ() + (z + 0.5) * cellMeters;
        }

        private static double positionUncertaintyKilometers(double[][] covariance) {
            if (covariance == null || covariance.length < 3) {
                return 0.0;
            }
            double variance = Math.max(0.0,
                    covariance[0][0] + covariance[1][1] + covariance[2][2]);
            return Math.sqrt(variance / 3.0) / 1_000.0;
        }

        private static double gaussianWeight(
                double dxMeters,
                double dyMeters,
                double dzMeters,
                double sigmaMeters) {
            double sigma = Math.max(1.0, sigmaMeters);
            double distanceSquared = dxMeters * dxMeters
                    + dyMeters * dyMeters
                    + dzMeters * dzMeters;
            return Math.exp(-0.5 * distanceSquared / (sigma * sigma));
        }

        private static double reliabilityForExposureScans(double exposureScans) {
            double prior = PRIOR_EXPOSURE_SCANS;
            double learned = Math.max(0.0, exposureScans - prior);
            return learned / (learned + prior);
        }

        private static double clampDensity(double densityPerCubicKilometer) {
            if (!Double.isFinite(densityPerCubicKilometer)) {
                return MINIMUM_LEARNED_BIRTH_DENSITY_PER_CUBIC_KILOMETER;
            }
            return Math.max(MINIMUM_LEARNED_BIRTH_DENSITY_PER_CUBIC_KILOMETER,
                    Math.min(MAXIMUM_LEARNED_BIRTH_DENSITY_PER_CUBIC_KILOMETER,
                            densityPerCubicKilometer));
        }
    }

    private record DensityEstimate(
            double densityPerCubicKilometer,
            double exposureScanCubicKilometers,
            double reliability) {
    }

    private record Bounds(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ) {
        double widthMeters() {
            return maxX - minX;
        }

        double depthMeters() {
            return maxY - minY;
        }

        double heightMeters() {
            return maxZ - minZ;
        }

        Bounds padded(double paddingMeters) {
            return new Bounds(
                    minX - paddingMeters,
                    minY - paddingMeters,
                    minZ - paddingMeters,
                    maxX + paddingMeters,
                    maxY + paddingMeters,
                    maxZ + paddingMeters);
        }
    }

    private static final class MutableBounds {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double minZ = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private double maxZ = Double.NEGATIVE_INFINITY;

        void include(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return;
            }
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        Bounds toBounds() {
            if (!Double.isFinite(minX)) {
                return new Bounds(-500.0, -500.0, -500.0, 500.0, 500.0, 500.0);
            }
            double span = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
            if (span < MINIMUM_SPATIAL_CELL_METERS) {
                double pad = (MINIMUM_SPATIAL_CELL_METERS - span) / 2.0;
                return new Bounds(
                        minX - pad,
                        minY - pad,
                        minZ - pad,
                        maxX + pad,
                        maxY + pad,
                        maxZ + pad);
            }
            return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

}
