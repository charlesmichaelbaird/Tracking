package com.targettracker.analysis;

import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
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
    private static final int MEASUREMENT_SIZE = 6;
    private static final double EPSILON = 1.0e-8;
    private static final double LIVE_SAMPLE_TOLERANCE_SECONDS = 1.01;
    private static final double PROCESS_NOISE_SPECTRAL_DENSITY = 1.0;
    private static final double MINIMUM_LAMBDA_EX = 1.0e-300;
    private static final double CUBIC_METERS_PER_CUBIC_KILOMETER = 1.0e9;

    public List<EventResult> analyze(RecordedScenario scenario, Configuration configuration) {
        Map<String, List<TrackRecord>> tracks = groupTracks(scenario.records());
        Map<String, List<GroundTruthRecord>> truth = groupTruth(scenario.groundTruth());
        List<BirthPoint> birthPoints = birthPoints(tracks);
        Map<String, List<RecordedMeasurement>> measurementsByTrack =
                associateMeasurements(tracks, scenario.measurements());
        TreeSet<Double> measurementTimes = new TreeSet<>();
        scenario.measurements().stream()
                .map(RecordedMeasurement::timeSeconds)
                .forEach(measurementTimes::add);

        List<EventResult> events = new ArrayList<>();
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
            double learnedBirthDensity = learnedBirthDensityPerCubicKilometerSecond(
                    birthPoints, eventTime);
            oldSegments.sort(Comparator.comparing(Segment::trackId));
            newSegments.sort(Comparator.comparing(Segment::trackId));
            boolean hasDistinctPair = oldSegments.stream().anyMatch(oldSegment ->
                    newSegments.stream().anyMatch(newSegment ->
                            !oldSegment.trackId().equals(newSegment.trackId())));
            if (!hasDistinctPair) {
                continue;
            }

            List<PairResult> pairs = new ArrayList<>();
            for (Segment oldSegment : oldSegments) {
                for (Segment newSegment : newSegments) {
                    if (!oldSegment.trackId().equals(newSegment.trackId())) {
                        pairs.add(analyzePair(
                                oldSegment,
                                newSegment,
                                truth,
                                measurementsByTrack.getOrDefault(newSegment.trackId(), List.of()),
                                configuration,
                                learnedBirthDensity));
                    }
                }
            }
            events.add(new EventResult(
                    eventTime,
                    List.copyOf(allSegments),
                    List.copyOf(oldSegments),
                    List.copyOf(newSegments),
                    List.copyOf(pairs),
                    optimalAssignments(oldSegments, newSegments, pairs, Metric.NLL),
                    optimalAssignments(oldSegments, newSegments, pairs, Metric.MAHALANOBIS),
                    optimalAssignments(oldSegments, newSegments, pairs, Metric.STATIC_NLLR),
                    optimalAssignments(oldSegments, newSegments, pairs, Metric.LEARNED_NLLR),
                    learnedBirthDensity));
        }
        return List.copyOf(events);
    }

    private static PairResult analyzePair(
            Segment oldSegment,
            Segment newSegment,
            Map<String, List<GroundTruthRecord>> truth,
            List<RecordedMeasurement> newTrackMeasurements,
            Configuration configuration,
            double learnedBirthDensityPerCubicKilometerSecond) {
        TrackRecord oldAnchor = oldSegment.lastUpdateRecord();
        TrackRecord newAnchor = joinTimingAnchor(oldSegment, newSegment);
        double oldTime = oldAnchor.timeSeconds();
        double newTime = newAnchor.timeSeconds();
        double bankStart = Math.min(oldTime, newTime);
        double bankEnd = Math.max(oldTime, newTime);
        List<Double> timeBank = timeBank(bankStart, bankEnd, configuration.resolutionSeconds());

        double simpleTime = (oldTime + newTime) / 2.0;
        double kinematicTime = kinematicJoinTime(oldAnchor, newAnchor, bankStart, bankEnd);
        List<ScoredTime> mahalanobisScores = new ArrayList<>();
        List<TruthScore> truthScores = new ArrayList<>();
        for (double time : timeBank) {
            PropagatedState oldState = predictOld(oldAnchor, time);
            PropagatedState newState = retrodictNew(
                    newSegment, newTrackMeasurements, time);
            InnovationScore innovation = innovationScore(oldState, newState);
            mahalanobisScores.add(new ScoredTime(time, innovation.mahalanobisDistance()));
            TruthScore truthScore = truthScore(time, oldState.state(), newState.state(), truth);
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

        JoinMetrics simpleMetrics = joinMetrics(oldAnchor, newSegment, newTrackMeasurements,
                simpleTime, configuration, learnedBirthDensityPerCubicKilometerSecond);
        JoinMetrics kinematicMetrics = joinMetrics(oldAnchor, newSegment, newTrackMeasurements,
                kinematicTime, configuration, learnedBirthDensityPerCubicKilometerSecond);
        JoinMetrics statisticalMetrics = joinMetrics(oldAnchor, newSegment, newTrackMeasurements,
                statisticalTime, configuration, learnedBirthDensityPerCubicKilometerSecond);
        JoinMetrics actualMetrics = Double.isFinite(actualTime)
                ? joinMetrics(oldAnchor, newSegment, newTrackMeasurements,
                        actualTime, configuration, learnedBirthDensityPerCubicKilometerSecond)
                : JoinMetrics.nan();

        return new PairResult(
                oldSegment.trackId(),
                newSegment.trackId(),
                truthTargetId,
                simpleTime,
                kinematicTime,
                statisticalTime,
                actualTime,
                simpleMetrics.negativeLogLikelihood(),
                kinematicMetrics.negativeLogLikelihood(),
                statisticalMetrics.negativeLogLikelihood(),
                actualMetrics.negativeLogLikelihood(),
                simpleMetrics.mahalanobisDistance(),
                kinematicMetrics.mahalanobisDistance(),
                statisticalMetrics.mahalanobisDistance(),
                actualMetrics.mahalanobisDistance(),
                simpleMetrics.staticNegativeLogLikelihoodRatio(),
                kinematicMetrics.staticNegativeLogLikelihoodRatio(),
                statisticalMetrics.staticNegativeLogLikelihoodRatio(),
                actualMetrics.staticNegativeLogLikelihoodRatio(),
                simpleMetrics.learnedNegativeLogLikelihoodRatio(),
                kinematicMetrics.learnedNegativeLogLikelihoodRatio(),
                statisticalMetrics.learnedNegativeLogLikelihoodRatio(),
                actualMetrics.learnedNegativeLogLikelihoodRatio());
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
                    costs[row][column] = score.score();
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
                .filter(score -> Double.isFinite(score.score()))
                .min(Comparator.comparingDouble(VariantScore::score))
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
        return new VariantScore(label, joinTimeSeconds, score);
    }

    private static TrackRecord joinTimingAnchor(Segment oldSegment, Segment newSegment) {
        if (oldSegment.deadAtEvent()) {
            return newSegment.formationRecord();
        }
        return newSegment.mostFutureRecord();
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
        double resolution = Math.max(0.001, resolutionSeconds);
        List<Double> bank = new ArrayList<>();
        for (double time = start; time < end - EPSILON; time += resolution) {
            bank.add(time);
        }
        bank.add(end);
        return bank;
    }

    private static JoinMetrics joinMetrics(
            TrackRecord oldAnchor,
            Segment newSegment,
            List<RecordedMeasurement> newTrackMeasurements,
            double timeSeconds,
            Configuration configuration,
            double learnedBirthDensityPerCubicKilometerSecond) {
        InnovationScore score = innovationScore(
                predictOld(oldAnchor, timeSeconds),
                retrodictNew(newSegment, newTrackMeasurements, timeSeconds));
        double nll = canonicalNegativeLogLikelihood(score);
        double innovationVolume = innovationVolumeCubicKilometers(score.innovationCovariance());
        double staticDensity = configuration.falseAlarmRatePerCubicKilometer()
                + configuration.birthRatePerCubicKilometer();
        double staticNllr = nll + Math.log(lambdaEx(staticDensity, innovationVolume));
        double learnedNllr = nll + Math.log(lambdaEx(
                learnedBirthDensityPerCubicKilometerSecond, innovationVolume));
        return new JoinMetrics(
                nll,
                score.mahalanobisDistance(),
                innovationVolume,
                staticNllr,
                learnedNllr);
    }

    private static double lambdaEx(double densityPerCubicKilometer, double volumeCubicKilometers) {
        return Math.max(MINIMUM_LAMBDA_EX,
                Math.max(0.0, densityPerCubicKilometer)
                        * Math.max(0.0, volumeCubicKilometers));
    }

    private static double innovationVolumeCubicKilometers(double[][] innovationCovariance) {
        double[][] positionCovariance = new double[3][3];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(innovationCovariance[row], 0, positionCovariance[row], 0, 3);
        }
        double determinant = determinant3(positionCovariance);
        double volumeCubicMeters = 4.0 / 3.0 * Math.PI
                * Math.sqrt(Math.max(0.0, determinant));
        if (!Double.isFinite(volumeCubicMeters)) {
            return 0.0;
        }
        return volumeCubicMeters / CUBIC_METERS_PER_CUBIC_KILOMETER;
    }

    private static double determinant3(double[][] matrix) {
        return matrix[0][0] * (matrix[1][1] * matrix[2][2] - matrix[1][2] * matrix[2][1])
                - matrix[0][1] * (matrix[1][0] * matrix[2][2] - matrix[1][2] * matrix[2][0])
                + matrix[0][2] * (matrix[1][0] * matrix[2][1] - matrix[1][1] * matrix[2][0]);
    }

    static double canonicalNegativeLogLikelihood(InnovationScore score) {
        double logLikelihood = -0.5 * (STATE_SIZE * Math.log(2.0 * Math.PI)
                + score.logDeterminant()
                + score.innovationQuadratic());
        return -logLikelihood;
    }

    static InnovationScore innovationScore(
            PropagatedState oldState,
            PropagatedState newState) {
        double[] innovation = new double[STATE_SIZE];
        for (int index = 0; index < STATE_SIZE; index++) {
            innovation[index] = oldState.state()[index] - newState.state()[index];
        }
        double[][] covariance = add(oldState.covariance(), newState.covariance());
        CholeskyResult cholesky = cholesky(covariance);
        double[] solved = solveCholesky(cholesky.lower(), innovation);
        double innovationQuadratic = Math.max(0.0, dot(innovation, solved));
        return new InnovationScore(
                innovation,
                covariance,
                Math.sqrt(innovationQuadratic),
                innovationQuadratic,
                cholesky.logDeterminant());
    }

    private static PropagatedState predictOld(TrackRecord record, double wantedTime) {
        return propagate(
                new PropagatedState(record.state(), record.covariance()),
                record.timeSeconds(),
                wantedTime);
    }

    static PropagatedState retrodictNew(
            Segment newSegment,
            List<RecordedMeasurement> measurements,
            double wantedTime) {
        TrackRecord futureRecord = newSegment.mostFutureRecord();
        PropagatedState state = new PropagatedState(
                futureRecord.state(), futureRecord.covariance());
        double currentTime = futureRecord.timeSeconds();
        double futureTime = currentTime;
        List<RecordedMeasurement> descending = measurements.stream()
                .filter(measurement -> measurement.timeSeconds() < futureTime - EPSILON
                        && measurement.timeSeconds() > wantedTime + EPSILON)
                .sorted(Comparator.comparingDouble(RecordedMeasurement::timeSeconds).reversed())
                .toList();
        for (RecordedMeasurement measurement : descending) {
            state = propagate(state, currentTime, measurement.timeSeconds());
            state = measurementUpdate(state, measurement);
            currentTime = measurement.timeSeconds();
        }
        return propagate(state, currentTime, wantedTime);
    }

    static PropagatedState propagate(
            PropagatedState source,
            double sourceTime,
            double wantedTime) {
        double dt = wantedTime - sourceTime;
        double[][] transition = identity(STATE_SIZE);
        for (int axis = 0; axis < 3; axis++) {
            transition[axis][axis + 3] = dt;
            transition[axis][axis + 6] = 0.5 * dt * dt;
            transition[axis + 3][axis + 6] = dt;
        }
        double positiveDt = Math.abs(dt);
        double[] propagatedState = multiply(transition, source.state());
        double[][] propagatedCovariance = add(
                multiply(multiply(transition, source.covariance()), transpose(transition)),
                processCovariance(positiveDt));
        double regularization = 1.0e-9 * (1.0 + positiveDt);
        for (int index = 0; index < STATE_SIZE; index++) {
            propagatedCovariance[index][index] += regularization;
        }
        return new PropagatedState(propagatedState, propagatedCovariance);
    }

    private static PropagatedState measurementUpdate(
            PropagatedState prediction,
            RecordedMeasurement measurement) {
        double[] measurementMean = measurement.mean();
        double[] innovation = new double[MEASUREMENT_SIZE];
        for (int index = 0; index < MEASUREMENT_SIZE; index++) {
            innovation[index] = measurementMean[index] - prediction.state()[index];
        }
        double[][] innovationCovariance = new double[MEASUREMENT_SIZE][MEASUREMENT_SIZE];
        double[][] measurementCovariance = measurement.covariance();
        for (int row = 0; row < MEASUREMENT_SIZE; row++) {
            for (int column = 0; column < MEASUREMENT_SIZE; column++) {
                innovationCovariance[row][column] = prediction.covariance()[row][column]
                        + measurementCovariance[row][column];
            }
        }
        double[][] inverseInnovation = inverseSpd(innovationCovariance);
        double[][] crossCovariance = new double[STATE_SIZE][MEASUREMENT_SIZE];
        for (int row = 0; row < STATE_SIZE; row++) {
            System.arraycopy(prediction.covariance()[row], 0,
                    crossCovariance[row], 0, MEASUREMENT_SIZE);
        }
        double[][] gain = multiply(crossCovariance, inverseInnovation);
        double[] updatedState = prediction.state().clone();
        double[] correction = multiply(gain, innovation);
        for (int index = 0; index < STATE_SIZE; index++) {
            updatedState[index] += correction[index];
        }

        double[][] identityMinusKh = identity(STATE_SIZE);
        for (int row = 0; row < STATE_SIZE; row++) {
            for (int column = 0; column < MEASUREMENT_SIZE; column++) {
                identityMinusKh[row][column] -= gain[row][column];
            }
        }
        double[][] updatedCovariance = add(
                multiply(multiply(identityMinusKh, prediction.covariance()),
                        transpose(identityMinusKh)),
                multiply(multiply(gain, measurementCovariance), transpose(gain)));
        symmetrize(updatedCovariance);
        return new PropagatedState(updatedState, updatedCovariance);
    }

    private static double[][] processCovariance(double dt) {
        double[][] covariance = new double[STATE_SIZE][STATE_SIZE];
        double dt2 = dt * dt;
        double dt3 = dt2 * dt;
        double dt4 = dt3 * dt;
        double dt5 = dt4 * dt;
        double q = PROCESS_NOISE_SPECTRAL_DENSITY;
        for (int axis = 0; axis < 3; axis++) {
            int position = axis;
            int velocity = axis + 3;
            int acceleration = axis + 6;
            covariance[position][position] = q * dt5 / 20.0;
            covariance[position][velocity] = q * dt4 / 8.0;
            covariance[position][acceleration] = q * dt3 / 6.0;
            covariance[velocity][position] = covariance[position][velocity];
            covariance[velocity][velocity] = q * dt3 / 3.0;
            covariance[velocity][acceleration] = q * dt2 / 2.0;
            covariance[acceleration][position] = covariance[position][acceleration];
            covariance[acceleration][velocity] = covariance[velocity][acceleration];
            covariance[acceleration][acceleration] = q * dt;
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

    private static List<BirthPoint> birthPoints(Map<String, List<TrackRecord>> tracks) {
        List<BirthPoint> births = new ArrayList<>();
        for (List<TrackRecord> records : tracks.values()) {
            if (records.isEmpty()) {
                continue;
            }
            TrackRecord birth = records.get(0);
            double[] state = birth.state();
            births.add(new BirthPoint(
                    birth.timeSeconds(),
                    state[0],
                    state[1],
                    state[2]));
        }
        births.sort(Comparator.comparingDouble(BirthPoint::timeSeconds));
        return List.copyOf(births);
    }

    private static double learnedBirthDensityPerCubicKilometerSecond(
            List<BirthPoint> births,
            double eventTime) {
        List<BirthPoint> observed = births.stream()
                .filter(birth -> birth.timeSeconds() <= eventTime + EPSILON)
                .toList();
        if (observed.isEmpty()) {
            return 0.0;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (BirthPoint birth : observed) {
            minX = Math.min(minX, birth.xMeters());
            maxX = Math.max(maxX, birth.xMeters());
            minY = Math.min(minY, birth.yMeters());
            maxY = Math.max(maxY, birth.yMeters());
            minZ = Math.min(minZ, birth.zMeters());
            maxZ = Math.max(maxZ, birth.zMeters());
        }
        double widthKilometers = Math.max(1.0, (maxX - minX) / 1_000.0);
        double heightKilometers = Math.max(1.0, (maxY - minY) / 1_000.0);
        double depthKilometers = Math.max(1.0, (maxZ - minZ) / 1_000.0);
        double volumeCubicKilometers = widthKilometers * heightKilometers * depthKilometers;
        double firstTime = observed.get(0).timeSeconds();
        double durationSeconds = Math.max(1.0, eventTime - firstTime + 1.0);
        return observed.size() / (volumeCubicKilometers * durationSeconds);
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

    private static double[][] identity(int size) {
        double[][] result = new double[size][size];
        for (int index = 0; index < size; index++) {
            result[index][index] = 1.0;
        }
        return result;
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[matrix.length];
        for (int row = 0; row < matrix.length; row++) {
            for (int column = 0; column < vector.length; column++) {
                result[row] += matrix[row][column] * vector[column];
            }
        }
        return result;
    }

    private static double[][] multiply(double[][] left, double[][] right) {
        double[][] result = new double[left.length][right[0].length];
        for (int row = 0; row < left.length; row++) {
            for (int inner = 0; inner < right.length; inner++) {
                if (left[row][inner] == 0.0) {
                    continue;
                }
                for (int column = 0; column < right[0].length; column++) {
                    result[row][column] += left[row][inner] * right[inner][column];
                }
            }
        }
        return result;
    }

    private static double[][] transpose(double[][] matrix) {
        double[][] result = new double[matrix[0].length][matrix.length];
        for (int row = 0; row < matrix.length; row++) {
            for (int column = 0; column < matrix[row].length; column++) {
                result[column][row] = matrix[row][column];
            }
        }
        return result;
    }

    private static double[][] add(double[][] left, double[][] right) {
        double[][] result = new double[left.length][left[0].length];
        for (int row = 0; row < left.length; row++) {
            for (int column = 0; column < left[row].length; column++) {
                result[row][column] = left[row][column] + right[row][column];
            }
        }
        return result;
    }

    private static double[][] inverseSpd(double[][] matrix) {
        CholeskyResult cholesky = cholesky(matrix);
        double[][] inverse = new double[matrix.length][matrix.length];
        for (int column = 0; column < matrix.length; column++) {
            double[] basis = new double[matrix.length];
            basis[column] = 1.0;
            double[] solution = solveCholesky(cholesky.lower(), basis);
            for (int row = 0; row < matrix.length; row++) {
                inverse[row][column] = solution[row];
            }
        }
        symmetrize(inverse);
        return inverse;
    }

    private static void symmetrize(double[][] matrix) {
        for (int row = 0; row < matrix.length; row++) {
            matrix[row][row] = Math.max(matrix[row][row], 1.0e-12);
            for (int column = row + 1; column < matrix.length; column++) {
                double average = (matrix[row][column] + matrix[column][row]) / 2.0;
                matrix[row][column] = average;
                matrix[column][row] = average;
            }
        }
    }

    private static CholeskyResult cholesky(double[][] source) {
        double jitter = 1.0e-9;
        for (int attempt = 0; attempt < 8; attempt++) {
            double[][] lower = new double[source.length][source.length];
            boolean valid = true;
            for (int row = 0; row < source.length && valid; row++) {
                for (int column = 0; column <= row; column++) {
                    double sum = source[row][column];
                    if (row == column) {
                        sum += jitter;
                    }
                    for (int inner = 0; inner < column; inner++) {
                        sum -= lower[row][inner] * lower[column][inner];
                    }
                    if (row == column) {
                        if (!(sum > 0.0) || !Double.isFinite(sum)) {
                            valid = false;
                            break;
                        }
                        lower[row][column] = Math.sqrt(sum);
                    } else {
                        lower[row][column] = sum / lower[column][column];
                    }
                }
            }
            if (valid) {
                double logDeterminant = 0.0;
                for (int index = 0; index < source.length; index++) {
                    logDeterminant += 2.0 * Math.log(lower[index][index]);
                }
                return new CholeskyResult(lower, logDeterminant);
            }
            jitter *= 100.0;
        }
        throw new IllegalArgumentException("Innovation covariance is not positive definite");
    }

    private static double[] solveCholesky(double[][] lower, double[] rightHandSide) {
        double[] forward = new double[rightHandSide.length];
        for (int row = 0; row < lower.length; row++) {
            double value = rightHandSide[row];
            for (int column = 0; column < row; column++) {
                value -= lower[row][column] * forward[column];
            }
            forward[row] = value / lower[row][row];
        }
        double[] result = new double[rightHandSide.length];
        for (int row = lower.length - 1; row >= 0; row--) {
            double value = forward[row];
            for (int column = row + 1; column < lower.length; column++) {
                value -= lower[column][row] * result[column];
            }
            result[row] = value / lower[row][row];
        }
        return result;
    }

    private static double dot(double[] left, double[] right) {
        double result = 0.0;
        for (int index = 0; index < left.length; index++) {
            result += left[index] * right[index];
        }
        return result;
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
            List<OptimalAssignment> nllAssignments,
            List<OptimalAssignment> mahalanobisAssignments,
            List<OptimalAssignment> staticNllrAssignments,
            List<OptimalAssignment> learnedNllrAssignments,
            double learnedBirthDensityPerCubicKilometerSecond) {
    }

    public record PairResult(
            String oldTrackId,
            String newTrackId,
            String truthTargetId,
            double simpleJoinTimeSeconds,
            double kinematicJoinTimeSeconds,
            double statisticalJoinTimeSeconds,
            double actualJoinTimeSeconds,
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

    record PropagatedState(double[] state, double[][] covariance) {
    }

    record InnovationScore(
            double[] innovation,
            double[][] innovationCovariance,
            double mahalanobisDistance,
            double innovationQuadratic,
            double logDeterminant) {
    }

    private record ScoredTime(double timeSeconds, double score) {
    }

    private record TruthScore(double timeSeconds, double score, String targetId) {
    }

    private enum Metric {
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
            double score) {
    }

    private record JoinMetrics(
            double negativeLogLikelihood,
            double mahalanobisDistance,
            double innovationVolumeCubicKilometers,
            double staticNegativeLogLikelihoodRatio,
            double learnedNegativeLogLikelihoodRatio) {
        static JoinMetrics nan() {
            return new JoinMetrics(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }

    private record BirthPoint(
            double timeSeconds,
            double xMeters,
            double yMeters,
            double zMeters) {
    }

    private record CholeskyResult(double[][] lower, double logDeterminant) {
    }
}
