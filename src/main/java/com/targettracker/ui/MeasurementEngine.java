package com.targettracker.ui;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.EcefVector;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.SensorParameters;
import com.targettracker.model.SensorSettings;
import com.targettracker.model.TargetMeasurement;
import com.targettracker.model.TargetTrajectory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Generates scheduled, noisy detections from an omniscient scenario sensor. */
final class MeasurementEngine {
    private static final double TIME_EPSILON_SECONDS = 1.0e-9;

    private final ScenarioModel model;
    private final SensorSettings settings;
    private final Random random;
    private final List<TargetMeasurement> newlyGeneratedMeasurements = new ArrayList<>();
    private final List<TargetMeasurement> allMeasurements = new ArrayList<>();

    private SensorParameters activeParameters;
    private double nextLookSeconds = Double.POSITIVE_INFINITY;

    MeasurementEngine(ScenarioModel model, SensorSettings settings) {
        this(model, settings, new Random());
    }

    MeasurementEngine(ScenarioModel model, SensorSettings settings, Random random) {
        this.model = model;
        this.settings = settings;
        this.random = random;
    }

    void beginScenario() {
        newlyGeneratedMeasurements.clear();
        allMeasurements.clear();
        activeParameters = settings.parameters();
        nextLookSeconds = activeParameters.lookOffsetSeconds();
    }

    void reset() {
        newlyGeneratedMeasurements.clear();
        allMeasurements.clear();
        activeParameters = null;
        nextLookSeconds = Double.POSITIVE_INFINITY;
    }

    /** Replaces generated measurements with measurements restored from a recorded run. */
    void loadRecordedMeasurements(List<TargetMeasurement> measurements) {
        newlyGeneratedMeasurements.clear();
        allMeasurements.clear();
        allMeasurements.addAll(measurements);
        allMeasurements.sort(Comparator.comparingDouble(TargetMeasurement::timeSeconds));
        activeParameters = null;
        nextLookSeconds = Double.POSITIVE_INFINITY;
    }

    void parametersChanged(double elapsedSeconds) {
        if (activeParameters == null) {
            return;
        }
        activeParameters = settings.parameters();
        double offset = activeParameters.lookOffsetSeconds();
        double interval = activeParameters.lookIntervalSeconds();
        if (elapsedSeconds + TIME_EPSILON_SECONDS < offset) {
            nextLookSeconds = offset;
            return;
        }
        double completedIntervals = Math.floor(
                (elapsedSeconds - offset) / interval + TIME_EPSILON_SECONDS);
        nextLookSeconds = offset + (completedIntervals + 1.0) * interval;
    }

    void advanceTo(double elapsedSeconds) {
        if (activeParameters == null) {
            return;
        }
        while (nextLookSeconds <= elapsedSeconds + TIME_EPSILON_SECONDS) {
            makeLook(nextLookSeconds);
            nextLookSeconds += activeParameters.lookIntervalSeconds();
        }
    }

    List<TargetMeasurement> visibleMeasurements() {
        return visibleMeasurementsAt(Double.POSITIVE_INFINITY);
    }

    List<TargetMeasurement> visibleMeasurementsAt(double elapsedSeconds) {
        int requestedCount = settings.parameters().previousMeasurementsToShow();
        if (requestedCount == 0) {
            return List.of();
        }

        Map<String, Deque<TargetMeasurement>> byTarget = new LinkedHashMap<>();
        for (TargetMeasurement measurement : allMeasurements) {
            if (measurement.timeSeconds() > elapsedSeconds + TIME_EPSILON_SECONDS) {
                continue;
            }
            Deque<TargetMeasurement> history = byTarget.computeIfAbsent(
                    measurement.targetId(), ignored -> new ArrayDeque<>());
            history.addLast(measurement);
            while (history.size() > requestedCount) {
                history.removeFirst();
            }
        }
        List<TargetMeasurement> visible = new ArrayList<>();
        byTarget.values().forEach(visible::addAll);
        visible.sort(Comparator.comparingDouble(TargetMeasurement::timeSeconds));
        return visible;
    }

    /** Returns the newest requested fraction of all measurement history per target. */
    List<TargetMeasurement> measurementHistoryAt(
            double elapsedSeconds,
            double historyFraction) {
        double fraction = Math.max(0.0, Math.min(1.0, historyFraction));
        if (fraction <= 0.0) {
            return List.of();
        }
        Map<String, List<TargetMeasurement>> byTarget = new LinkedHashMap<>();
        for (TargetMeasurement measurement : allMeasurements) {
            if (measurement.timeSeconds() <= elapsedSeconds + TIME_EPSILON_SECONDS) {
                byTarget.computeIfAbsent(measurement.targetId(), ignored -> new ArrayList<>())
                        .add(measurement);
            }
        }
        List<TargetMeasurement> visible = new ArrayList<>();
        for (List<TargetMeasurement> history : byTarget.values()) {
            int count = Math.max(1, (int) Math.ceil(history.size() * fraction));
            visible.addAll(history.subList(Math.max(0, history.size() - count), history.size()));
        }
        visible.sort(Comparator.comparingDouble(TargetMeasurement::timeSeconds));
        return List.copyOf(visible);
    }

    List<TargetMeasurement> drainNewMeasurements() {
        List<TargetMeasurement> drained = List.copyOf(newlyGeneratedMeasurements);
        newlyGeneratedMeasurements.clear();
        return drained;
    }

    private void makeLook(double lookTimeSeconds) {
        for (TargetTrajectory target : model.targets()) {
            if (!target.isRunnable() || !detected()) {
                continue;
            }
            EcefPoint truePosition = target.positionAt(lookTimeSeconds);
            EcefVector trueVelocity = target.ecefVelocityAt(lookTimeSeconds);
            double positionSigma = activeParameters.positionStandardDeviationMeters();
            double velocitySigma = activeParameters.velocityStandardDeviationMetersPerSecond();
            TargetMeasurement measurement = new TargetMeasurement(
                    target.id(),
                    lookTimeSeconds,
                    new EcefPoint(
                            truePosition.x() + random.nextGaussian() * positionSigma,
                            truePosition.y() + random.nextGaussian() * positionSigma,
                            truePosition.z() + random.nextGaussian() * positionSigma),
                    new EcefVector(
                            trueVelocity.x() + random.nextGaussian() * velocitySigma,
                            trueVelocity.y() + random.nextGaussian() * velocitySigma,
                            trueVelocity.z() + random.nextGaussian() * velocitySigma),
                    positionSigma * positionSigma,
                    velocitySigma * velocitySigma);
            newlyGeneratedMeasurements.add(measurement);
            allMeasurements.add(measurement);

        }
    }

    private boolean detected() {
        double probability = activeParameters.probabilityOfDetection();
        return probability >= 1.0 || (probability > 0.0 && random.nextDouble() < probability);
    }
}
