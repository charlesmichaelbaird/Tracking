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
    private static final int MAX_STORED_MEASUREMENTS_PER_TARGET = 10;
    private static final double TIME_EPSILON_SECONDS = 1.0e-9;

    private final ScenarioModel model;
    private final SensorSettings settings;
    private final Random random;
    private final Map<TargetTrajectory, Deque<TargetMeasurement>> measurements =
            new LinkedHashMap<>();
    private final List<TargetMeasurement> newlyGeneratedMeasurements = new ArrayList<>();

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
        measurements.clear();
        newlyGeneratedMeasurements.clear();
        activeParameters = settings.parameters();
        nextLookSeconds = activeParameters.lookOffsetSeconds();
    }

    void reset() {
        measurements.clear();
        newlyGeneratedMeasurements.clear();
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
        int requestedCount = settings.parameters().previousMeasurementsToShow();
        if (requestedCount == 0) {
            return List.of();
        }

        List<TargetMeasurement> visible = new ArrayList<>();
        for (Deque<TargetMeasurement> targetMeasurements : measurements.values()) {
            int toSkip = Math.max(0, targetMeasurements.size() - requestedCount);
            int index = 0;
            for (TargetMeasurement measurement : targetMeasurements) {
                if (index++ >= toSkip) {
                    visible.add(measurement);
                }
            }
        }
        visible.sort(Comparator.comparingDouble(TargetMeasurement::timeSeconds));
        return visible;
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

            Deque<TargetMeasurement> history = measurements.computeIfAbsent(
                    target, ignored -> new ArrayDeque<>());
            history.addLast(measurement);
            while (history.size() > MAX_STORED_MEASUREMENTS_PER_TARGET) {
                history.removeFirst();
            }
        }
    }

    private boolean detected() {
        double probability = activeParameters.probabilityOfDetection();
        return probability >= 1.0 || (probability > 0.0 && random.nextDouble() < probability);
    }
}
