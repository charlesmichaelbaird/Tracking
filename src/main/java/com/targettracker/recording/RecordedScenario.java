package com.targettracker.recording;

import com.targettracker.model.BlackoutRegion;
import com.targettracker.tracking.TrackRecord;

import java.nio.file.Path;
import java.util.List;

/** Track snapshots and metadata loaded from one recorded scenario folder. */
public record RecordedScenario(
        Path folder,
        String scenarioName,
        double durationSeconds,
        List<TrackRecord> records,
        List<GroundTruthRecord> groundTruth,
        List<RecordedMeasurement> measurements,
        List<BlackoutRegion> blackoutRegions) {
    public RecordedScenario {
        if (folder == null) {
            throw new IllegalArgumentException("A recorded scenario folder is required");
        }
        folder = folder.toAbsolutePath().normalize();
        if (scenarioName == null || scenarioName.isBlank()) {
            scenarioName = folder.getFileName().toString();
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0) {
            throw new IllegalArgumentException("Recorded duration must be finite and non-negative");
        }
        records = records == null ? List.of() : List.copyOf(records);
        groundTruth = groundTruth == null ? List.of() : List.copyOf(groundTruth);
        measurements = measurements == null ? List.of() : List.copyOf(measurements);
        blackoutRegions = blackoutRegions == null ? List.of() : List.copyOf(blackoutRegions);
        if (records.isEmpty() && groundTruth.isEmpty() && measurements.isEmpty()) {
            throw new IllegalArgumentException("The scenario folder contains no replay data");
        }
    }

    public RecordedScenario(
            Path folder,
            String scenarioName,
            double durationSeconds,
            List<TrackRecord> records,
            List<GroundTruthRecord> groundTruth,
            List<RecordedMeasurement> measurements) {
        this(folder, scenarioName, durationSeconds, records,
                groundTruth, measurements, List.of());
    }

    public RecordedScenario(
            Path folder,
            String scenarioName,
            double durationSeconds,
            List<TrackRecord> records) {
        this(folder, scenarioName, durationSeconds, records, List.of(), List.of(), List.of());
    }
}
