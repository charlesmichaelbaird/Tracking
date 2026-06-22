package com.targettracker.recording;

import com.targettracker.tracking.AssociatedMeasurement;
import com.targettracker.tracking.TrackRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/** Verifies named run folders and a complete writer/reader round trip. */
public final class TrackCsvReaderSmokeTest {
    private TrackCsvReaderSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory(Path.of("."), "recording-reader-");
        try {
            TrackCsvRecorder recorder = new TrackCsvRecorder(Clock.fixed(
                    Instant.parse("2026-06-22T15:04:05.123Z"), ZoneOffset.UTC));
            recorder.setOutputParent(parent);
            recorder.setArmed(true);
            recorder.beginRun("1 target — hard left turn", 20.0);
            recorder.recordSamples(List.of(
                    record(5.0, true),
                    record(6.0, false)));
            recorder.recordGroundTruth(List.of(new GroundTruthRecord(
                    "TGT-007", 5.0,
                    new double[]{100, 101, 102, 10, 11, 12, 1, 2, 3})));
            recorder.finishRun();

            if (!recorder.runDirectory().getFileName().toString()
                    .equals("1_target_hard_left_turn_2026-06-22_15-04-05_123")) {
                throw new AssertionError("Named scenario folder was not sanitized as expected");
            }
            RecordedScenario loaded = TrackCsvReader.read(recorder.runDirectory());
            if (!loaded.scenarioName().equals("1 target — hard left turn")
                    || loaded.durationSeconds() != 20.0
                    || loaded.records().size() != 2
                    || loaded.groundTruth().size() != 1
                    || loaded.measurements().size() != 1) {
                throw new AssertionError("Recorded scenario metadata did not round-trip");
            }
            TrackRecord update = loaded.records().get(0);
            TrackRecord coast = loaded.records().get(1);
            RecordedMeasurement measurement = loaded.measurements().get(0);
            if (!update.updated() || update.measurement() != null
                    || !measurement.targetId().equals("TGT-007")
                    || measurement.mean()[2] != 102.0
                    || measurement.covariance()[5][5] != 6.0) {
                throw new AssertionError("Separate measurement data did not round-trip");
            }
            if (coast.updated() || coast.measurement() != null) {
                throw new AssertionError("Coast rows must retain empty measurement fields");
            }

            Path legacyFolder = Files.createDirectory(parent.resolve("legacy_run"));
            List<String> currentLines = Files.readAllLines(
                    recorder.runDirectory().resolve(TrackCsvRecorder.TRACK_DIRECTORY)
                            .resolve("TRK-001.csv"));
            Files.write(legacyFolder.resolve("TRK-001.csv"), currentLines);
            RecordedScenario legacy = TrackCsvReader.read(legacyFolder);
            if (legacy.records().size() != 2
                    || legacy.records().get(0).measurement() != null
                    || legacy.durationSeconds() != 6.0) {
                throw new AssertionError("Legacy 93-column recordings should remain readable");
            }

            Path legacyV2Folder = Files.createDirectory(parent.resolve("legacy_v2_run"));
            Files.write(legacyV2Folder.resolve("TRK-001.csv"), List.of(
                    currentLines.get(0) + legacyMeasurementHeader(),
                    currentLines.get(1) + legacyMeasurementValues(),
                    currentLines.get(2) + ",".repeat(43)));
            RecordedScenario legacyV2 = TrackCsvReader.read(legacyV2Folder);
            if (legacyV2.measurements().size() != 1
                    || legacyV2.records().get(0).measurement() == null) {
                throw new AssertionError("Legacy inline-measurement recordings should remain readable");
            }
            System.out.println("TrackCsvReaderSmokeTest passed");
        } finally {
            try (var paths = Files.walk(parent)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }

    private static TrackRecord record(double time, boolean updated) {
        double[] state = new double[9];
        double[][] covariance = new double[9][9];
        for (int index = 0; index < 9; index++) {
            state[index] = 1_000.0 + time + index;
            covariance[index][index] = index + 1.0;
        }
        AssociatedMeasurement measurement = null;
        if (updated) {
            double[] mean = {100.0, 101.0, 102.0, 10.0, 11.0, 12.0};
            double[][] measurementCovariance = new double[6][6];
            for (int index = 0; index < 6; index++) {
                measurementCovariance[index][index] = index + 1.0;
            }
            measurement = new AssociatedMeasurement(
                    "TGT-007", mean, measurementCovariance);
        }
        return new TrackRecord(
                "TRK-001", time, state, covariance, updated, measurement);
    }

    private static String legacyMeasurementHeader() {
        StringBuilder header = new StringBuilder(
                ",measurement_target_id,meas_x_m,meas_y_m,meas_z_m,"
                        + "meas_vx_mps,meas_vy_mps,meas_vz_mps");
        for (int row = 0; row < 6; row++) {
            for (int column = 0; column < 6; column++) {
                header.append(",r_").append(row).append(column);
            }
        }
        return header.toString();
    }

    private static String legacyMeasurementValues() {
        StringBuilder values = new StringBuilder(",TGT-007,100,101,102,10,11,12");
        for (int row = 0; row < 6; row++) {
            for (int column = 0; column < 6; column++) {
                values.append(',').append(row == column ? row + 1.0 : 0.0);
            }
        }
        return values.toString();
    }
}
