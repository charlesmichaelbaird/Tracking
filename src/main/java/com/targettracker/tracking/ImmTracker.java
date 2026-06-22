package com.targettracker.tracking;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.TargetMeasurement;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** ECEF interacting-multiple-model tracker with greedy Mahalanobis association. */
public final class ImmTracker {
    private static final int STATE_SIZE = 9;
    private static final int MEASUREMENT_SIZE = 6;
    private static final int TAIL_POINTS = 180;
    private static final double LOG_TWO_PI = Math.log(2.0 * Math.PI);
    private static final Color[] TRACK_COLORS = {
            new Color(255, 214, 10),
            new Color(255, 92, 166),
            new Color(72, 232, 255),
            new Color(188, 255, 92),
            new Color(255, 154, 61),
            new Color(190, 132, 255)
    };

    private final ImmSettings settings;
    private final List<Track> activeTracks = new ArrayList<>();
    private final List<TrackView> deadTrackViews = new ArrayList<>();
    private final List<TrackRecord> updatedRecords = new ArrayList<>();
    private List<TrackView> currentViews = List.of();
    private int nextTrackNumber = 1;

    public ImmTracker(ImmSettings settings) {
        this.settings = settings;
    }

    public void reset() {
        activeTracks.clear();
        deadTrackViews.clear();
        updatedRecords.clear();
        currentViews = List.of();
        nextTrackNumber = 1;
    }

    public void parametersChanged(double scenarioTimeSeconds) {
        ImmParameters parameters = settings.parameters();
        activeTracks.forEach(track -> track.reconcileModels(parameters));
        advanceTo(scenarioTimeSeconds);
    }

    public void processMeasurements(List<TargetMeasurement> measurements) {
        if (measurements.isEmpty()) {
            return;
        }
        TreeMap<Double, List<TargetMeasurement>> byTime = new TreeMap<>();
        for (TargetMeasurement measurement : measurements) {
            byTime.computeIfAbsent(measurement.timeSeconds(), ignored -> new ArrayList<>())
                    .add(measurement);
        }
        byTime.forEach(this::processMeasurementBatch);
    }

    public void advanceTo(double scenarioTimeSeconds) {
        ImmParameters parameters = settings.parameters();
        archiveBrokenTracks(scenarioTimeSeconds, parameters);
        List<TrackView> views = new ArrayList<>();
        for (Track track : activeTracks) {
            TrackView view = track.viewAt(scenarioTimeSeconds, parameters, false);
            track.appendTail(view.meanPosition());
            views.add(track.viewAt(scenarioTimeSeconds, parameters, false));
        }
        views.addAll(deadTrackViews);
        currentViews = List.copyOf(views);
    }

    public List<TrackView> currentViews() {
        return currentViews;
    }

    /** Returns and clears track snapshots produced by measurement updates. */
    public List<TrackRecord> drainUpdatedRecords() {
        List<TrackRecord> drained = List.copyOf(updatedRecords);
        updatedRecords.clear();
        return drained;
    }

    private void processMeasurementBatch(double timeSeconds, List<TargetMeasurement> measurements) {
        ImmParameters parameters = settings.parameters();
        activeTracks.forEach(track -> track.reconcileModels(parameters));
        archiveBrokenTracks(timeSeconds, parameters);

        List<AssociationCandidate> candidates = new ArrayList<>();
        for (int trackIndex = 0; trackIndex < activeTracks.size(); trackIndex++) {
            Track track = activeTracks.get(trackIndex);
            FusedState prediction = track.fusedAt(timeSeconds, parameters);
            for (int measurementIndex = 0; measurementIndex < measurements.size(); measurementIndex++) {
                TargetMeasurement measurement = measurements.get(measurementIndex);
                double distance = mahalanobisDistance(prediction, measurement);
                if (distance <= parameters.associationMahalanobisThreshold()) {
                    candidates.add(new AssociationCandidate(
                            trackIndex, measurementIndex, distance));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(AssociationCandidate::distance));

        Set<Integer> assignedTracks = new HashSet<>();
        Set<Integer> assignedMeasurements = new HashSet<>();
        for (AssociationCandidate candidate : candidates) {
            if (!assignedTracks.contains(candidate.trackIndex())
                    && !assignedMeasurements.contains(candidate.measurementIndex())) {
                assignedTracks.add(candidate.trackIndex());
                assignedMeasurements.add(candidate.measurementIndex());
                Track track = activeTracks.get(candidate.trackIndex());
                TargetMeasurement measurement = measurements.get(candidate.measurementIndex());
                track.update(measurement, parameters);
                updatedRecords.add(track.updatedRecord(measurement.timeSeconds()));
            }
        }

        for (int measurementIndex = 0; measurementIndex < measurements.size(); measurementIndex++) {
            if (!assignedMeasurements.contains(measurementIndex)) {
                TargetMeasurement measurement = measurements.get(measurementIndex);
                Track track = new Track(
                        "TRK-%03d".formatted(nextTrackNumber),
                        TRACK_COLORS[(nextTrackNumber - 1) % TRACK_COLORS.length],
                        measurement,
                        parameters);
                activeTracks.add(track);
                updatedRecords.add(track.updatedRecord(measurement.timeSeconds()));
                nextTrackNumber++;
            }
        }
    }

    private void archiveBrokenTracks(double timeSeconds, ImmParameters parameters) {
        List<Track> broken = new ArrayList<>();
        for (Track track : activeTracks) {
            FusedState prediction = track.fusedAt(timeSeconds, parameters);
            double radius = uncertaintyRadius(prediction.covariance());
            if (timeSeconds - track.lastUpdateSeconds > parameters.timeoutSeconds()
                    || radius > parameters.uncertaintyRadiusMeters()) {
                TrackView deadView = track.viewFrom(prediction, true, radius);
                deadTrackViews.add(deadView);
                broken.add(track);
            }
        }
        activeTracks.removeAll(broken);
    }

    private static double mahalanobisDistance(
            FusedState prediction,
            TargetMeasurement measurement) {
        double[] measurementVector = measurementVector(measurement);
        double[] residual = new double[MEASUREMENT_SIZE];
        for (int i = 0; i < MEASUREMENT_SIZE; i++) {
            residual[i] = measurementVector[i] - prediction.mean()[i];
        }
        double[][] innovation = new double[MEASUREMENT_SIZE][MEASUREMENT_SIZE];
        for (int row = 0; row < MEASUREMENT_SIZE; row++) {
            System.arraycopy(prediction.covariance()[row], 0,
                    innovation[row], 0, MEASUREMENT_SIZE);
        }
        addMeasurementVariance(innovation, measurement);
        double squared = LinearAlgebra.quadraticForm(
                residual, LinearAlgebra.inverseSpd(innovation).inverse());
        return Math.sqrt(Math.max(0.0, squared));
    }

    private static double uncertaintyRadius(double[][] covariance) {
        double[][] positionCovariance = new double[3][3];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(covariance[row], 0, positionCovariance[row], 0, 3);
        }
        return Math.sqrt(LinearAlgebra.largestEigenvalueSymmetric3(positionCovariance));
    }

    private static double[] measurementVector(TargetMeasurement measurement) {
        return new double[]{
                measurement.measuredPosition().x(),
                measurement.measuredPosition().y(),
                measurement.measuredPosition().z(),
                measurement.measuredVelocity().x(),
                measurement.measuredVelocity().y(),
                measurement.measuredVelocity().z()
        };
    }

    private static void addMeasurementVariance(
            double[][] matrix,
            TargetMeasurement measurement) {
        for (int i = 0; i < 3; i++) {
            matrix[i][i] += measurement.positionVarianceMetersSquared();
            matrix[i + 3][i + 3] += measurement.velocityVarianceMetersSquaredPerSecondSquared();
        }
    }

    private static ModelState predict(
            ModelState state,
            ImmModel model,
            double elapsedSeconds,
            double processNoise) {
        double dt = Math.max(0.0, elapsedSeconds);
        double[][] transition = transition(model, dt);
        double[][] covariance = LinearAlgebra.add(
                LinearAlgebra.multiply(
                        LinearAlgebra.multiply(transition, state.covariance()),
                        LinearAlgebra.transpose(transition)),
                processCovariance(model, dt, processNoise));
        return new ModelState(
                LinearAlgebra.multiply(transition, state.mean()), covariance);
    }

    private static UpdateResult updateModel(ModelState prediction, TargetMeasurement measurement) {
        double[] measurementVector = measurementVector(measurement);
        double[] residual = new double[MEASUREMENT_SIZE];
        for (int i = 0; i < MEASUREMENT_SIZE; i++) {
            residual[i] = measurementVector[i] - prediction.mean()[i];
        }
        double[][] innovation = new double[MEASUREMENT_SIZE][MEASUREMENT_SIZE];
        for (int row = 0; row < MEASUREMENT_SIZE; row++) {
            System.arraycopy(prediction.covariance()[row], 0,
                    innovation[row], 0, MEASUREMENT_SIZE);
        }
        addMeasurementVariance(innovation, measurement);
        LinearAlgebra.SpdInverse inverse = LinearAlgebra.inverseSpd(innovation);

        double[][] crossCovariance = new double[STATE_SIZE][MEASUREMENT_SIZE];
        for (int row = 0; row < STATE_SIZE; row++) {
            System.arraycopy(prediction.covariance()[row], 0,
                    crossCovariance[row], 0, MEASUREMENT_SIZE);
        }
        double[][] gain = LinearAlgebra.multiply(crossCovariance, inverse.inverse());
        double[] updatedMean = LinearAlgebra.add(
                prediction.mean(), LinearAlgebra.multiply(gain, residual));

        double[][] identityMinusKh = LinearAlgebra.identity(STATE_SIZE);
        for (int row = 0; row < STATE_SIZE; row++) {
            for (int column = 0; column < MEASUREMENT_SIZE; column++) {
                identityMinusKh[row][column] -= gain[row][column];
            }
        }
        double[][] measurementCovariance = new double[MEASUREMENT_SIZE][MEASUREMENT_SIZE];
        addMeasurementVariance(measurementCovariance, measurement);
        double[][] updatedCovariance = LinearAlgebra.add(
                LinearAlgebra.multiply(
                        LinearAlgebra.multiply(identityMinusKh, prediction.covariance()),
                        LinearAlgebra.transpose(identityMinusKh)),
                LinearAlgebra.multiply(
                        LinearAlgebra.multiply(gain, measurementCovariance),
                        LinearAlgebra.transpose(gain)));
        double squaredDistance = LinearAlgebra.quadraticForm(residual, inverse.inverse());
        double logLikelihood = -0.5
                * (squaredDistance + inverse.logDeterminant() + MEASUREMENT_SIZE * LOG_TWO_PI);
        return new UpdateResult(new ModelState(updatedMean, updatedCovariance), logLikelihood);
    }

    private static double[][] transition(ImmModel model, double dt) {
        double[][] transition = LinearAlgebra.identity(STATE_SIZE);
        for (int axis = 0; axis < 3; axis++) {
            transition[axis][axis + 3] = dt;
            if (model == ImmModel.CA) {
                transition[axis][axis + 6] = 0.5 * dt * dt;
                transition[axis + 3][axis + 6] = dt;
            } else {
                transition[axis + 6][axis + 6] = 0.0;
            }
        }
        return transition;
    }

    private static double[][] processCovariance(ImmModel model, double dt, double noise) {
        double[][] covariance = new double[STATE_SIZE][STATE_SIZE];
        for (int axis = 0; axis < 3; axis++) {
            int position = axis;
            int velocity = axis + 3;
            int acceleration = axis + 6;
            if (model == ImmModel.CV) {
                covariance[position][position] = noise * dt * dt * dt / 3.0;
                covariance[position][velocity] = noise * dt * dt / 2.0;
                covariance[velocity][position] = covariance[position][velocity];
                covariance[velocity][velocity] = noise * dt;
                covariance[acceleration][acceleration] = 1.0e-9;
            } else {
                double dt2 = dt * dt;
                double dt3 = dt2 * dt;
                double dt4 = dt3 * dt;
                double dt5 = dt4 * dt;
                covariance[position][position] = noise * dt5 / 20.0;
                covariance[position][velocity] = noise * dt4 / 8.0;
                covariance[position][acceleration] = noise * dt3 / 6.0;
                covariance[velocity][position] = covariance[position][velocity];
                covariance[velocity][velocity] = noise * dt3 / 3.0;
                covariance[velocity][acceleration] = noise * dt2 / 2.0;
                covariance[acceleration][position] = covariance[position][acceleration];
                covariance[acceleration][velocity] = covariance[velocity][acceleration];
                covariance[acceleration][acceleration] = noise * dt;
            }
        }
        return covariance;
    }

    private final class Track {
        private final String id;
        private final Color color;
        private final Deque<EcefPoint> tail = new ArrayDeque<>();
        private List<ImmModel> models;
        private ModelState[] states;
        private double[] probabilities;
        private double lastUpdateSeconds;

        Track(String id, Color color, TargetMeasurement measurement, ImmParameters parameters) {
            this.id = id;
            this.color = color;
            this.models = parameters.enabledModels();
            this.states = new ModelState[models.size()];
            this.probabilities = new double[models.size()];
            double[] initialMean = new double[STATE_SIZE];
            double[] value = measurementVector(measurement);
            System.arraycopy(value, 0, initialMean, 0, MEASUREMENT_SIZE);
            double[][] initialCovariance = new double[STATE_SIZE][STATE_SIZE];
            for (int i = 0; i < 3; i++) {
                initialCovariance[i][i] = Math.max(1.0e-6,
                        measurement.positionVarianceMetersSquared());
                initialCovariance[i + 3][i + 3] = Math.max(1.0e-6,
                        measurement.velocityVarianceMetersSquaredPerSecondSquared());
                initialCovariance[i + 6][i + 6] = 100.0;
            }
            for (int i = 0; i < models.size(); i++) {
                states[i] = new ModelState(initialMean.clone(),
                        LinearAlgebra.copy(initialCovariance));
                probabilities[i] = 1.0 / models.size();
            }
            lastUpdateSeconds = measurement.timeSeconds();
            appendTail(measurement.measuredPosition());
        }

        void reconcileModels(ImmParameters parameters) {
            if (models.equals(parameters.enabledModels())) {
                return;
            }
            FusedState fused = fusedStoredState();
            models = parameters.enabledModels();
            states = new ModelState[models.size()];
            probabilities = new double[models.size()];
            for (int i = 0; i < models.size(); i++) {
                states[i] = new ModelState(fused.mean().clone(),
                        LinearAlgebra.copy(fused.covariance()));
                probabilities[i] = 1.0 / models.size();
            }
        }

        void update(TargetMeasurement measurement, ImmParameters parameters) {
            reconcileModels(parameters);
            int count = models.size();
            double[][] transitionProbabilities = parameters.transitionProbabilityMatrix();
            double[] normalizers = new double[count];
            for (int destination = 0; destination < count; destination++) {
                for (int source = 0; source < count; source++) {
                    normalizers[destination] += probabilities[source]
                            * transitionProbabilities[source][destination];
                }
                normalizers[destination] = Math.max(normalizers[destination], 1.0e-300);
            }

            ModelState[] predictions = new ModelState[count];
            double elapsed = Math.max(0.0, measurement.timeSeconds() - lastUpdateSeconds);
            for (int destination = 0; destination < count; destination++) {
                double[] mixedMean = new double[STATE_SIZE];
                for (int source = 0; source < count; source++) {
                    double mixingWeight = probabilities[source]
                            * transitionProbabilities[source][destination]
                            / normalizers[destination];
                    for (int element = 0; element < STATE_SIZE; element++) {
                        mixedMean[element] += mixingWeight * states[source].mean()[element];
                    }
                }
                double[][] mixedCovariance = new double[STATE_SIZE][STATE_SIZE];
                for (int source = 0; source < count; source++) {
                    double mixingWeight = probabilities[source]
                            * transitionProbabilities[source][destination]
                            / normalizers[destination];
                    double[] difference = LinearAlgebra.subtract(states[source].mean(), mixedMean);
                    double[][] contribution = LinearAlgebra.add(
                            states[source].covariance(), LinearAlgebra.outer(difference, difference));
                    for (int row = 0; row < STATE_SIZE; row++) {
                        for (int column = 0; column < STATE_SIZE; column++) {
                            mixedCovariance[row][column] += mixingWeight * contribution[row][column];
                        }
                    }
                }
                predictions[destination] = predict(
                        new ModelState(mixedMean, mixedCovariance),
                        models.get(destination),
                        elapsed,
                        parameters.processNoiseFor(models.get(destination)));
            }

            ModelState[] updatedStates = new ModelState[count];
            double[] logWeights = new double[count];
            double largestLogWeight = Double.NEGATIVE_INFINITY;
            for (int modelIndex = 0; modelIndex < count; modelIndex++) {
                UpdateResult update = updateModel(predictions[modelIndex], measurement);
                updatedStates[modelIndex] = update.state();
                logWeights[modelIndex] = Math.log(normalizers[modelIndex]) + update.logLikelihood();
                largestLogWeight = Math.max(largestLogWeight, logWeights[modelIndex]);
            }
            double weightSum = 0.0;
            for (int modelIndex = 0; modelIndex < count; modelIndex++) {
                probabilities[modelIndex] = Math.exp(logWeights[modelIndex] - largestLogWeight);
                weightSum += probabilities[modelIndex];
            }
            for (int modelIndex = 0; modelIndex < count; modelIndex++) {
                probabilities[modelIndex] /= weightSum;
            }
            states = updatedStates;
            lastUpdateSeconds = measurement.timeSeconds();
        }

        FusedState fusedAt(double timeSeconds, ImmParameters parameters) {
            ModelState[] predictions = new ModelState[states.length];
            double elapsed = Math.max(0.0, timeSeconds - lastUpdateSeconds);
            for (int modelIndex = 0; modelIndex < states.length; modelIndex++) {
                predictions[modelIndex] = predict(
                        states[modelIndex],
                        models.get(modelIndex),
                        elapsed,
                        parameters.processNoiseFor(models.get(modelIndex)));
            }
            return fuse(predictions, probabilities);
        }

        FusedState fusedStoredState() {
            return fuse(states, probabilities);
        }

        TrackRecord updatedRecord(double timeSeconds) {
            FusedState fused = fusedStoredState();
            return new TrackRecord(id, timeSeconds, fused.mean(), fused.covariance(), true);
        }

        TrackView viewAt(double timeSeconds, ImmParameters parameters, boolean dead) {
            FusedState fused = fusedAt(timeSeconds, parameters);
            return viewFrom(fused, dead, uncertaintyRadius(fused.covariance()));
        }

        TrackView viewFrom(FusedState fused, boolean dead, double radius) {
            double[][] positionCovariance = new double[3][3];
            for (int row = 0; row < 3; row++) {
                System.arraycopy(fused.covariance()[row], 0,
                        positionCovariance[row], 0, 3);
            }
            return new TrackView(
                    id,
                    new EcefPoint(fused.mean()[0], fused.mean()[1], fused.mean()[2]),
                    positionCovariance,
                    List.copyOf(tail),
                    color,
                    dead,
                    radius);
        }

        void appendTail(EcefPoint point) {
            EcefPoint last = tail.peekLast();
            if (last == null || last.distanceTo(point) >= 1.0) {
                tail.addLast(point);
            }
            while (tail.size() > TAIL_POINTS) {
                tail.removeFirst();
            }
        }
    }

    private static FusedState fuse(ModelState[] states, double[] probabilities) {
        double[] mean = new double[STATE_SIZE];
        for (int modelIndex = 0; modelIndex < states.length; modelIndex++) {
            for (int element = 0; element < STATE_SIZE; element++) {
                mean[element] += probabilities[modelIndex] * states[modelIndex].mean()[element];
            }
        }
        double[][] covariance = new double[STATE_SIZE][STATE_SIZE];
        for (int modelIndex = 0; modelIndex < states.length; modelIndex++) {
            double[] difference = LinearAlgebra.subtract(states[modelIndex].mean(), mean);
            double[][] contribution = LinearAlgebra.add(
                    states[modelIndex].covariance(), LinearAlgebra.outer(difference, difference));
            for (int row = 0; row < STATE_SIZE; row++) {
                for (int column = 0; column < STATE_SIZE; column++) {
                    covariance[row][column] += probabilities[modelIndex]
                            * contribution[row][column];
                }
            }
        }
        return new FusedState(mean, covariance);
    }

    private record ModelState(double[] mean, double[][] covariance) {
    }

    private record FusedState(double[] mean, double[][] covariance) {
    }

    private record UpdateResult(ModelState state, double logLikelihood) {
    }

    private record AssociationCandidate(int trackIndex, int measurementIndex, double distance) {
    }
}
