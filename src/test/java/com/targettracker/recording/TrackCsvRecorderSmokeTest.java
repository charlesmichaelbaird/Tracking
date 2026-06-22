package com.targettracker.recording;

import com.targettracker.tracking.TrackRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/** Verifies the fixed CSV schema, predicted/update rows, and unique run folders. */
public final class TrackCsvRecorderSmokeTest {
    private TrackCsvRecorderSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory(Path.of("."), "recording-smoke-");
        try {
            Clock clock = Clock.fixed(Instant.parse("2026-06-22T15:04:05.123Z"), ZoneOffset.UTC);
            TrackCsvRecorder recorder = new TrackCsvRecorder(clock);
            recorder.setOutputParent(parent);
            recorder.setArmed(true);

            if (!recorder.beginRun() || !recorder.isActive()) {
                throw new AssertionError("Armed recorder should begin a run");
            }
            Path firstRun = recorder.runDirectory();
            recorder.recordSamples(List.of(
                    record("TRK-001", 5.0, true),
                    record("TRK-001", 20.0, true),
                    record("TRK-002", 20.0, false)));
            recorder.finishRun();

            if (!firstRun.getFileName().toString().equals("scenario_2026-06-22_15-04-05_123")) {
                throw new AssertionError("Run directory should use the run date and time");
            }
            Path trackFile = firstRun.resolve("TRK-001.csv");
            List<String> lines = Files.readAllLines(trackFile);
            if (lines.size() != 3) {
                throw new AssertionError("Expected a header and two update rows");
            }
            if (lines.get(0).split(",", -1).length != 93
                    || lines.get(1).split(",", -1).length != 93) {
                throw new AssertionError("CSV must contain ID, time, updated, 9 state, and 81 covariance fields");
            }
            if (!lines.get(0).startsWith("track_id,time_s,updated,x_m,y_m,z_m")
                    || !lines.get(1).startsWith("TRK-001,5.0,true,")) {
                throw new AssertionError("CSV schema or values are incorrect");
            }
            List<String> coastLines = Files.readAllLines(firstRun.resolve("TRK-002.csv"));
            if (coastLines.size() != 2 || !coastLines.get(1).startsWith("TRK-002,20.0,false,")) {
                throw new AssertionError("Predicted/coasted rows must be exported with updated=false");
            }
            if (!Files.exists(firstRun.resolve("README.txt"))) {
                throw new AssertionError("Each run should include MATLAB import guidance");
            }

            if (!recorder.beginRun()) {
                throw new AssertionError("A second armed run should start");
            }
            if (firstRun.equals(recorder.runDirectory())) {
                throw new AssertionError("Repeated timestamps must still produce unique run folders");
            }
            recorder.finishRun();
            System.out.println("TrackCsvRecorderSmokeTest passed");
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

    private static TrackRecord record(String id, double time, boolean updated) {
        double[] state = new double[9];
        double[][] covariance = new double[9][9];
        for (int index = 0; index < 9; index++) {
            state[index] = index + time;
            covariance[index][index] = index + 1.0;
        }
        return new TrackRecord(id, time, state, covariance, updated);
    }
}
