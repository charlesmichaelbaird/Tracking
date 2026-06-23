package com.targettracker.model;

import java.nio.file.Path;
import java.util.List;

/** A user-authored scenario loaded from disk. */
public record SavedScenarioDefinition(
        String name,
        Path path,
        List<TargetData> targets,
        List<BlackoutRegion> blackoutRegions) {
    public SavedScenarioDefinition {
        targets = targets == null ? List.of() : List.copyOf(targets);
        blackoutRegions = blackoutRegions == null ? List.of() : List.copyOf(blackoutRegions);
    }

    @Override
    public String toString() {
        return name + " (saved)";
    }

    public record TargetData(
            List<GeodeticPoint> path,
            List<Double> velocitySamples,
            List<Double> altitudeSamples) {
        public TargetData {
            path = path == null ? List.of() : List.copyOf(path);
            velocitySamples = velocitySamples == null ? List.of() : List.copyOf(velocitySamples);
            altitudeSamples = altitudeSamples == null ? List.of() : List.copyOf(altitudeSamples);
        }
    }
}
