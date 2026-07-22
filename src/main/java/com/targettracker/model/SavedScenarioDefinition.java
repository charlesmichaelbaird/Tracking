package com.targettracker.model;

import java.nio.file.Path;
import java.util.List;

/** A user-authored scenario loaded from disk. */
public record SavedScenarioDefinition(
        String name,
        Path path,
        Double scenarioLengthSeconds,
        List<TargetData> targets,
        List<BlackoutRegion> blackoutRegions) {
    public SavedScenarioDefinition {
        if (scenarioLengthSeconds != null
                && (!Double.isFinite(scenarioLengthSeconds) || scenarioLengthSeconds <= 0.0)) {
            scenarioLengthSeconds = null;
        }
        targets = targets == null ? List.of() : List.copyOf(targets);
        blackoutRegions = blackoutRegions == null ? List.of() : List.copyOf(blackoutRegions);
    }

    public SavedScenarioDefinition(
            String name,
            Path path,
            List<TargetData> targets,
            List<BlackoutRegion> blackoutRegions) {
        this(name, path, null, targets, blackoutRegions);
    }

    @Override
    public String toString() {
        return name + " (saved)";
    }

    public record TargetData(
            List<GeodeticPoint> path,
            List<Double> velocitySamples,
            List<Double> altitudeSamples,
            TargetTrajectory.ExtrapolationMode extrapolationMode,
            TargetTrajectory.PlatformType platformType) {
        public TargetData(
                List<GeodeticPoint> path,
                List<Double> velocitySamples,
                List<Double> altitudeSamples) {
            this(
                    path,
                    velocitySamples,
                    altitudeSamples,
                    TargetTrajectory.ExtrapolationMode.LINEAR,
                    TargetTrajectory.PlatformType.AIR);
        }

        public TargetData(
                List<GeodeticPoint> path,
                List<Double> velocitySamples,
                List<Double> altitudeSamples,
                TargetTrajectory.ExtrapolationMode extrapolationMode) {
            this(
                    path,
                    velocitySamples,
                    altitudeSamples,
                    extrapolationMode,
                    TargetTrajectory.PlatformType.AIR);
        }

        public TargetData {
            path = path == null ? List.of() : List.copyOf(path);
            velocitySamples = velocitySamples == null ? List.of() : List.copyOf(velocitySamples);
            altitudeSamples = altitudeSamples == null ? List.of() : List.copyOf(altitudeSamples);
            extrapolationMode = extrapolationMode == null
                    ? TargetTrajectory.ExtrapolationMode.LINEAR
                    : extrapolationMode;
            platformType = platformType == null
                    ? TargetTrajectory.PlatformType.AIR
                    : platformType;
        }
    }
}
