package com.targettracker.tracking;

import com.targettracker.math.LinearAlgebra;
import com.targettracker.model.EcefPoint;
import com.targettracker.model.TargetMeasurement;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/** ECEF interacting-multiple-model tracker with greedy Mahalanobis association. */
public final class ImmTracker {
    private static final int SPATIAL_DIMENSIONS = 3;
    private static final int STATE_DERIVATIVE_COUNT = 3;
    private static final int MEASUREMENT_DERIVATIVE_COUNT = 2;
    private static final int POSITION_OFFSET = 0;
    private static final int VELOCITY_OFFSET = POSITION_OFFSET + SPATIAL_DIMENSIONS;
    private static final int ACCELERATION_OFFSET = VELOCITY_OFFSET + SPATIAL_DIMENSIONS;
    private static final int STATE_SIZE = SPATIAL_DIMENSIONS * STATE_DERIVATIVE_COUNT;
    private static final int MEASUREMENT_SIZE = SPATIAL_DIMENSIONS * MEASUREMENT_DERIVATIVE_COUNT;
    private static final int MEASUREMENT_POSITION_OFFSET = 0;
    private static final int MEASUREMENT_VELOCITY_OFFSET =
            MEASUREMENT_POSITION_OFFSET + SPATIAL_DIMENSIONS;
    private static final double MINIMUM_PROBABILITY = 1.0e-300;
    private static final double[][] OBSERVATION_MATRIX = observationMatrix();
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
        advanceTo(scenarioTimeSeconds, true);
    }

    /** Advances pre-computation without retaining a second internal copy of replay history. */
    public void advanceToForReplay(double scenarioTimeSeconds) {
        advanceTo(scenarioTimeSeconds, false);
    }

    private void advanceTo(double scenarioTimeSeconds, boolean retainTail) {
        ImmParameters parameters = settings.parameters();
        archiveBrokenTracks(scenarioTimeSeconds, parameters);
        List<TrackView> views = new ArrayList<>();
        for (Track track : activeTracks) {
            TrackView view = track.viewAt(scenarioTimeSeconds, parameters, false);
            if (retainTail) {
                track.appendTail(view.meanPosition());
            } else {
                view = withoutTail(view);
            }
            views.add(view);
        }
        if (retainTail) {
            views.addAll(deadTrackViews);
        } else {
            deadTrackViews.stream().map(ImmTracker::withoutTail).forEach(views::add);
        }
        currentViews = List.copyOf(views);
    }

    public List<TrackView> currentViews() {
        return currentViews;
    }

    List<double[]> activeModelProbabilities() {
        return activeTracks.stream()
                .map(track -> track.modelProbabilities.clone())
                .toList();
    }

    private static TrackView withoutTail(TrackView view) {
        return new TrackView(
                view.id(),
                view.meanPosition(),
                view.positionCovariance(),
                List.of(),
                view.color(),
                view.dead(),
                view.uncertaintyRadiusMeters(),
                view.deadReason());
    }

    /** Returns and clears track snapshots produced by measurement updates. */
    public List<TrackRecord> drainUpdatedRecords() {
        List<TrackRecord> drained = List.copyOf(updatedRecords);
        updatedRecords.clear();
        return drained;
    }

    /** Returns predicted or updated fused 9D snapshots for every currently live track. */
    public List<TrackRecord> recordsAt(
            double scenarioTimeSeconds,
            Set<String> updatedTrackIds) {
        ImmParameters parameters = settings.parameters();
        List<TrackRecord> records = new ArrayList<>(activeTracks.size());
        for (Track track : activeTracks) {
            records.add(track.recordAt(
                    scenarioTimeSeconds,
                    parameters,
                    updatedTrackIds.contains(track.id)));
        }
        return List.copyOf(records);
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
                updatedRecords.add(track.updatedRecord(measurement.timeSeconds(), measurement));
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
                updatedRecords.add(track.updatedRecord(measurement.timeSeconds(), measurement));
                nextTrackNumber++;
            }
        }
    }

    private void archiveBrokenTracks(double timeSeconds, ImmParameters parameters) {
        List<Track> broken = new ArrayList<>();
        for (Track track : activeTracks) {
            FusedState prediction = track.fusedAt(timeSeconds, parameters);
            double radius = uncertaintyRadius(prediction.covariance());
            double timeSinceUpdate = timeSeconds - track.lastUpdateSeconds;
            if (timeSinceUpdate > parameters.timeoutSeconds()
                    || radius > parameters.uncertaintyRadiusMeters()) {
                String reason = timeSinceUpdate > parameters.timeoutSeconds()
                        ? "timeout"
                        : "covariance radius";
                TrackView deadView = track.viewFrom(prediction, true, radius, reason);
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
        double[] predictedMeasurement =
                LinearAlgebra.multiply(OBSERVATION_MATRIX, prediction.mean());
        double[] residual = LinearAlgebra.subtract(measurementVector, predictedMeasurement);
        double[][] innovation = innovationCovariance(prediction.covariance(), measurement);
        return LinearAlgebra.mahalanobisDistance(residual, innovation);
    }

    private static double uncertaintyRadius(double[][] covariance) {
        double[][] positionCovariance = stateBlock(
                covariance, POSITION_OFFSET, SPATIAL_DIMENSIONS);
        return Math.sqrt(LinearAlgebra.largestEigenvalueSymmetric3(positionCovariance));
    }

    private static double[] measurementVector(TargetMeasurement measurement) {
        double[] vector = new double[MEASUREMENT_SIZE];
        double[] position = {
                measurement.measuredPosition().x(),
                measurement.measuredPosition().y(),
                measurement.measuredPosition().z()};
        double[] velocity = {
                measurement.measuredVelocity().x(),
                measurement.measuredVelocity().y(),
                measurement.measuredVelocity().z()};
        for (int axis = 0; axis < SPATIAL_DIMENSIONS; axis++) {
            vector[MEASUREMENT_POSITION_OFFSET + axis] = position[axis];
            vector[MEASUREMENT_VELOCITY_OFFSET + axis] = velocity[axis];
        }
        return vector;
    }

    private static double[][] measurementCovariance(TargetMeasurement measurement) {
        double[][] covariance = new double[MEASUREMENT_SIZE][MEASUREMENT_SIZE];
        for (int axis = 0; axis < SPATIAL_DIMENSIONS; axis++) {
            covariance[MEASUREMENT_POSITION_OFFSET + axis][MEASUREMENT_POSITION_OFFSET + axis] =
                    measurement.positionVarianceMetersSquared();
            covariance[MEASUREMENT_VELOCITY_OFFSET + axis][MEASUREMENT_VELOCITY_OFFSET + axis] =
                    measurement.velocityVarianceMetersSquaredPerSecondSquared();
        }
        return covariance;
    }

    private static double[][] innovationCovariance(
            double[][] stateCovariance,
            TargetMeasurement measurement) {
        return LinearAlgebra.add(
                LinearAlgebra.multiply(
                        LinearAlgebra.multiply(OBSERVATION_MATRIX, stateCovariance),
                        LinearAlgebra.transpose(OBSERVATION_MATRIX)),
                measurementCovariance(measurement));
    }

    private static double[][] observationMatrix() {
        double[][] matrix = new double[MEASUREMENT_SIZE][STATE_SIZE];
        for (int axis = 0; axis < SPATIAL_DIMENSIONS; axis++) {
            matrix[MEASUREMENT_POSITION_OFFSET + axis][POSITION_OFFSET + axis] = 1.0;
            matrix[MEASUREMENT_VELOCITY_OFFSET + axis][VELOCITY_OFFSET + axis] = 1.0;
        }
        return matrix;
    }

    private static double[][] stateBlock(
            double[][] matrix,
            int offset,
            int size) {
        double[][] block = new double[size][size];
        for (int row = 0; row < size; row++) {
            System.arraycopy(matrix[offset + row], offset, block[row], 0, size);
        }
        return block;
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
        covariance = LinearAlgebra.symmetrized(covariance, 1.0e-12);
        return new ModelState(
                LinearAlgebra.multiply(transition, state.mean()), covariance);
    }

    private static UpdateResult updateModel(ModelState prediction, TargetMeasurement measurement) {
        double[] measurementVector = measurementVector(measurement);
        double[] predictedMeasurement =
                LinearAlgebra.multiply(OBSERVATION_MATRIX, prediction.mean());
        double[] residual = LinearAlgebra.subtract(measurementVector, predictedMeasurement);
        double[][] innovation = innovationCovariance(prediction.covariance(), measurement);
        LinearAlgebra.SpdInverse inverse = LinearAlgebra.inverseSpd(innovation);
        LinearAlgebra.GaussianLikelihood likelihood =
                LinearAlgebra.gaussianLikelihood(residual, innovation);

        double[][] crossCovariance = LinearAlgebra.multiply(
                prediction.covariance(), LinearAlgebra.transpose(OBSERVATION_MATRIX));
        double[][] gain = LinearAlgebra.multiply(crossCovariance, inverse.inverse());
        double[] updatedMean = LinearAlgebra.add(
                prediction.mean(), LinearAlgebra.multiply(gain, residual));

        double[][] gainObservation = LinearAlgebra.multiply(gain, OBSERVATION_MATRIX);
        double[][] identityMinusKh = LinearAlgebra.subtract(
                LinearAlgebra.identity(STATE_SIZE),
                gainObservation);
        double[][] measurementCovariance = measurementCovariance(measurement);
        double[][] updatedCovariance = LinearAlgebra.add(
                LinearAlgebra.multiply(
                        LinearAlgebra.multiply(identityMinusKh, prediction.covariance()),
                        LinearAlgebra.transpose(identityMinusKh)),
                LinearAlgebra.multiply(
                        LinearAlgebra.multiply(gain, measurementCovariance),
                        LinearAlgebra.transpose(gain)));
        updatedCovariance = LinearAlgebra.symmetrized(updatedCovariance, 1.0e-12);
        double logLikelihood = -likelihood.negativeLogLikelihood();
        return new UpdateResult(new ModelState(updatedMean, updatedCovariance), logLikelihood);
    }

    private static double[][] transition(ImmModel model, double dt) {
        double[][] transition = LinearAlgebra.identity(STATE_SIZE);
        for (int axis = 0; axis < SPATIAL_DIMENSIONS; axis++) {
            int position = POSITION_OFFSET + axis;
            int velocity = VELOCITY_OFFSET + axis;
            int acceleration = ACCELERATION_OFFSET + axis;
            transition[position][velocity] = dt;
            if (model == ImmModel.CA) {
                transition[position][acceleration] = 0.5 * dt * dt;
                transition[velocity][acceleration] = dt;
            } else {
                transition[acceleration][acceleration] = 0.0;
            }
        }
        return transition;
    }

    private static double[][] processCovariance(ImmModel model, double dt, double noise) {
        double[][] covariance = new double[STATE_SIZE][STATE_SIZE];
        for (int axis = 0; axis < SPATIAL_DIMENSIONS; axis++) {
            int position = POSITION_OFFSET + axis;
            int velocity = VELOCITY_OFFSET + axis;
            int acceleration = ACCELERATION_OFFSET + axis;
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

    private static ImmCycleResult runImmCycle(
            List<ImmModel> models,
            ModelState[] previousStates,
            double[] previousModelProbabilities,
            double[][] modelTransitionMatrix,
            double elapsedSeconds,
            TargetMeasurement measurement,
            ImmParameters parameters) {
        // One canonical IMM cycle:
        // 1) Markov-predict model probabilities c_j.
        // 2) Compute conditional mixing weights mu_{i|j}.
        // 3) Mix prior states/covariances for each destination model j.
        // 4) Predict and update each model-conditioned Kalman filter.
        // 5) Use model likelihoods to update posterior model probabilities.
        // 6) Fuse model-conditioned estimates with posterior probabilities.
        double[] predictedModelProbabilities = predictModelProbabilities(
                previousModelProbabilities, modelTransitionMatrix);
        double[][] mixingWeights = mixingWeights(
                previousModelProbabilities,
                modelTransitionMatrix,
                predictedModelProbabilities);
        ModelState[] mixedStates = mixStates(previousStates, mixingWeights);
        ModelState[] predictedStates = predictModels(
                models, mixedStates, elapsedSeconds, parameters);
        UpdateResult[] updateResults = updateModels(predictedStates, measurement);
        ModelState[] updatedStates = new ModelState[updateResults.length];
        double[] logLikelihoods = new double[updateResults.length];
        for (int modelIndex = 0; modelIndex < updateResults.length; modelIndex++) {
            updatedStates[modelIndex] = updateResults[modelIndex].state();
            logLikelihoods[modelIndex] = updateResults[modelIndex].logLikelihood();
        }
        double[] posteriorModelProbabilities = updateModelProbabilities(
                predictedModelProbabilities,
                logLikelihoods);
        return new ImmCycleResult(updatedStates, posteriorModelProbabilities);
    }

    private static double[] predictModelProbabilities(
            double[] previousModelProbabilities,
            double[][] modelTransitionMatrix) {
        int modelCount = previousModelProbabilities.length;
        double[] predicted = new double[modelCount];
        for (int destination = 0; destination < modelCount; destination++) {
            for (int source = 0; source < modelCount; source++) {
                predicted[destination] += previousModelProbabilities[source]
                        * modelTransitionMatrix[source][destination];
            }
            predicted[destination] = Math.max(predicted[destination], MINIMUM_PROBABILITY);
        }
        return normalizeProbabilities(predicted);
    }

    private static double[][] mixingWeights(
            double[] previousModelProbabilities,
            double[][] modelTransitionMatrix,
            double[] predictedModelProbabilities) {
        int modelCount = previousModelProbabilities.length;
        double[][] weights = new double[modelCount][modelCount];
        for (int source = 0; source < modelCount; source++) {
            for (int destination = 0; destination < modelCount; destination++) {
                weights[source][destination] = previousModelProbabilities[source]
                        * modelTransitionMatrix[source][destination]
                        / Math.max(predictedModelProbabilities[destination],
                        MINIMUM_PROBABILITY);
            }
        }
        return weights;
    }

    private static ModelState[] mixStates(
            ModelState[] previousStates,
            double[][] mixingWeights) {
        int modelCount = previousStates.length;
        ModelState[] mixedStates = new ModelState[modelCount];
        for (int destination = 0; destination < modelCount; destination++) {
            double[] destinationWeights = new double[modelCount];
            for (int source = 0; source < modelCount; source++) {
                destinationWeights[source] = mixingWeights[source][destination];
            }
            FusedState mixed = combineModelStates(previousStates, destinationWeights);
            mixedStates[destination] = new ModelState(mixed.mean(), mixed.covariance());
        }
        return mixedStates;
    }

    private static ModelState[] predictModels(
            List<ImmModel> models,
            ModelState[] mixedStates,
            double elapsedSeconds,
            ImmParameters parameters) {
        ModelState[] predictions = new ModelState[mixedStates.length];
        for (int modelIndex = 0; modelIndex < mixedStates.length; modelIndex++) {
            ImmModel model = models.get(modelIndex);
            predictions[modelIndex] = predict(
                    mixedStates[modelIndex],
                    model,
                    elapsedSeconds,
                    parameters.processNoiseFor(model));
        }
        return predictions;
    }

    private static UpdateResult[] updateModels(
            ModelState[] predictedStates,
            TargetMeasurement measurement) {
        UpdateResult[] results = new UpdateResult[predictedStates.length];
        for (int modelIndex = 0; modelIndex < predictedStates.length; modelIndex++) {
            results[modelIndex] = updateModel(predictedStates[modelIndex], measurement);
        }
        return results;
    }

    private static double[] updateModelProbabilities(
            double[] predictedModelProbabilities,
            double[] logLikelihoods) {
        double[] logWeights = new double[predictedModelProbabilities.length];
        double largestLogWeight = Double.NEGATIVE_INFINITY;
        for (int modelIndex = 0; modelIndex < logWeights.length; modelIndex++) {
            logWeights[modelIndex] = Math.log(Math.max(
                    predictedModelProbabilities[modelIndex],
                    MINIMUM_PROBABILITY)) + logLikelihoods[modelIndex];
            largestLogWeight = Math.max(largestLogWeight, logWeights[modelIndex]);
        }
        double[] posterior = new double[logWeights.length];
        for (int modelIndex = 0; modelIndex < logWeights.length; modelIndex++) {
            posterior[modelIndex] = Math.exp(logWeights[modelIndex] - largestLogWeight);
        }
        return normalizeProbabilities(posterior);
    }

    private static double[] normalizeProbabilities(double[] probabilities) {
        double sum = 0.0;
        for (double probability : probabilities) {
            sum += probability;
        }
        if (!Double.isFinite(sum) || sum <= 0.0) {
            return uniformModelProbabilities(probabilities.length);
        }
        double[] normalized = new double[probabilities.length];
        for (int index = 0; index < probabilities.length; index++) {
            normalized[index] = probabilities[index] / sum;
        }
        return normalized;
    }

    private static double[] uniformModelProbabilities(int modelCount) {
        double[] probabilities = new double[modelCount];
        for (int index = 0; index < modelCount; index++) {
            probabilities[index] = 1.0 / modelCount;
        }
        return probabilities;
    }

    private final class Track {
        private final String id;
        private final Color color;
        private final List<EcefPoint> tail = new ArrayList<>();
        private final List<EcefPoint> readOnlyTail = Collections.unmodifiableList(tail);
        private List<ImmModel> models;
        private ModelState[] states;
        private double[] modelProbabilities;
        private double lastUpdateSeconds;
        private TargetMeasurement lastMeasurement;

        Track(String id, Color color, TargetMeasurement measurement, ImmParameters parameters) {
            this.id = id;
            this.color = color;
            this.models = parameters.enabledModels();
            this.states = new ModelState[models.size()];
            this.modelProbabilities = uniformModelProbabilities(models.size());
            double[] initialMean = new double[STATE_SIZE];
            double[] value = measurementVector(measurement);
            System.arraycopy(value, MEASUREMENT_POSITION_OFFSET,
                    initialMean, POSITION_OFFSET, SPATIAL_DIMENSIONS);
            System.arraycopy(value, MEASUREMENT_VELOCITY_OFFSET,
                    initialMean, VELOCITY_OFFSET, SPATIAL_DIMENSIONS);
            double[][] initialCovariance = new double[STATE_SIZE][STATE_SIZE];
            for (int axis = 0; axis < SPATIAL_DIMENSIONS; axis++) {
                initialCovariance[POSITION_OFFSET + axis][POSITION_OFFSET + axis] = Math.max(1.0e-6,
                        measurement.positionVarianceMetersSquared());
                initialCovariance[VELOCITY_OFFSET + axis][VELOCITY_OFFSET + axis] = Math.max(1.0e-6,
                        measurement.velocityVarianceMetersSquaredPerSecondSquared());
                initialCovariance[ACCELERATION_OFFSET + axis][ACCELERATION_OFFSET + axis] = 100.0;
            }
            for (int i = 0; i < models.size(); i++) {
                states[i] = new ModelState(initialMean.clone(),
                        LinearAlgebra.copy(initialCovariance));
            }
            lastUpdateSeconds = measurement.timeSeconds();
            lastMeasurement = measurement;
            appendTail(measurement.measuredPosition());
        }

        void reconcileModels(ImmParameters parameters) {
            if (models.equals(parameters.enabledModels())) {
                return;
            }
            FusedState fused = fusedStoredState();
            models = parameters.enabledModels();
            states = new ModelState[models.size()];
            modelProbabilities = uniformModelProbabilities(models.size());
            for (int i = 0; i < models.size(); i++) {
                states[i] = new ModelState(fused.mean().clone(),
                        LinearAlgebra.copy(fused.covariance()));
            }
        }

        void update(TargetMeasurement measurement, ImmParameters parameters) {
            reconcileModels(parameters);
            double elapsed = Math.max(0.0, measurement.timeSeconds() - lastUpdateSeconds);
            ImmCycleResult cycle = runImmCycle(
                    models,
                    states,
                    modelProbabilities,
                    parameters.transitionProbabilityMatrix(),
                    elapsed,
                    measurement,
                    parameters);
            states = cycle.updatedStates();
            modelProbabilities = cycle.posteriorModelProbabilities();
            lastUpdateSeconds = measurement.timeSeconds();
            lastMeasurement = measurement;
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
            return combineModelStates(predictions, modelProbabilities);
        }

        FusedState fusedStoredState() {
            return combineModelStates(states, modelProbabilities);
        }

        TrackRecord updatedRecord(double timeSeconds, TargetMeasurement measurement) {
            FusedState fused = fusedStoredState();
            return new TrackRecord(
                    id,
                    timeSeconds,
                    fused.mean(),
                    fused.covariance(),
                    true,
                    AssociatedMeasurement.from(measurement));
        }

        TrackRecord recordAt(
                double timeSeconds,
                ImmParameters parameters,
                boolean updated) {
            FusedState fused = Math.abs(timeSeconds - lastUpdateSeconds) < 1.0e-9
                    ? fusedStoredState()
                    : fusedAt(timeSeconds, parameters);
            AssociatedMeasurement measurement = updated
                    && lastMeasurement != null
                    && Math.abs(timeSeconds - lastMeasurement.timeSeconds()) < 1.0e-9
                    ? AssociatedMeasurement.from(lastMeasurement)
                    : null;
            return new TrackRecord(
                    id, timeSeconds, fused.mean(), fused.covariance(), updated, measurement);
        }

        TrackView viewAt(double timeSeconds, ImmParameters parameters, boolean dead) {
            FusedState fused = fusedAt(timeSeconds, parameters);
            return viewFrom(fused, dead, uncertaintyRadius(fused.covariance()), "");
        }

        TrackView viewFrom(FusedState fused, boolean dead, double radius, String deadReason) {
            double[][] positionCovariance = stateBlock(
                    fused.covariance(), POSITION_OFFSET, SPATIAL_DIMENSIONS);
            return new TrackView(
                    id,
                    new EcefPoint(fused.mean()[0], fused.mean()[1], fused.mean()[2]),
                    positionCovariance,
                    readOnlyTail,
                    color,
                    dead,
                    radius,
                    deadReason);
        }

        void appendTail(EcefPoint point) {
            EcefPoint last = tail.isEmpty() ? null : tail.get(tail.size() - 1);
            if (last == null || last.distanceTo(point) >= 1.0) {
                tail.add(point);
            }
        }
    }

    private static FusedState combineModelStates(ModelState[] states, double[] probabilities) {
        double[] mean = new double[STATE_SIZE];
        for (int modelIndex = 0; modelIndex < states.length; modelIndex++) {
            mean = LinearAlgebra.add(
                    mean,
                    LinearAlgebra.scale(probabilities[modelIndex], states[modelIndex].mean()));
        }
        double[][] covariance = new double[STATE_SIZE][STATE_SIZE];
        for (int modelIndex = 0; modelIndex < states.length; modelIndex++) {
            double[] difference = LinearAlgebra.subtract(states[modelIndex].mean(), mean);
            double[][] contribution = LinearAlgebra.add(
                    states[modelIndex].covariance(), LinearAlgebra.outer(difference, difference));
            covariance = LinearAlgebra.add(
                    covariance,
                    LinearAlgebra.scale(probabilities[modelIndex], contribution));
        }
        return new FusedState(mean, LinearAlgebra.symmetrized(covariance, 1.0e-12));
    }

    private record ModelState(double[] mean, double[][] covariance) {
    }

    private record FusedState(double[] mean, double[][] covariance) {
    }

    private record UpdateResult(ModelState state, double logLikelihood) {
    }

    private record ImmCycleResult(
            ModelState[] updatedStates,
            double[] posteriorModelProbabilities) {
    }

    private record AssociationCandidate(int trackIndex, int measurementIndex, double distance) {
    }
}
