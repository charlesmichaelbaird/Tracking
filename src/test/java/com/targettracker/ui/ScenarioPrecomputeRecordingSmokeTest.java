package com.targettracker.ui;

import com.targettracker.model.EcefVector;
import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.SensorParameters;
import com.targettracker.model.SensorSettings;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.model.Wgs84;
import com.targettracker.recording.TrackCsvRecorder;
import com.targettracker.tracking.ImmSettings;
import com.targettracker.tracking.ImmTracker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Verifies exact one-second predicted/update output during fast pre-compute. */
public final class ScenarioPrecomputeRecordingSmokeTest {
    private ScenarioPrecomputeRecordingSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory(Path.of("."), "precompute-recording-");
        try {
            ScenarioModel model = new ScenarioModel();
            TargetTrajectory target = model.addTarget();
            target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(20.0, 10.0, 0.0)));
            target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(20.005, 10.0, 0.0)));
            double wantedDuration = 5.55;
            double speed = target.surfaceLengthMeters() / wantedDuration;
            for (int index = 0; index < target.velocityProfile().sampleCount(); index++) {
                target.velocityProfile().setSample(index, speed);
            }

            SensorSettings sensorSettings = new SensorSettings();
            sensorSettings.setParameters(new SensorParameters(
                    2.0, 0.0, 1.0, 1.0, 1.0, 10));
            MeasurementEngine measurements = new MeasurementEngine(
                    model, sensorSettings, new Random(4));
            TrackCsvRecorder recorder = new TrackCsvRecorder();
            recorder.setOutputParent(parent);
            ScenarioPlayback playback = new ScenarioPlayback(
                    model,
                    () -> {
                    },
                    measurements,
                    new ImmTracker(new ImmSettings()),
                    recorder);

            if (!playback.precompute() || recorder.isActive() || !playback.canSeek()) {
                throw new AssertionError(
                        "Pre-compute should finish and leave replay seekable");
            }
            if (recorder.runDirectory() != null) {
                throw new AssertionError("Pre-compute should not save CSV files automatically");
            }
            if (!playback.canExportRecording() || !playback.saveRecording("precompute recording")) {
                throw new AssertionError("Explicit CSV export should save pre-computed data");
            }
            List<String> lines = Files.readAllLines(recorder.runDirectory()
                    .resolve(TrackCsvRecorder.TRACK_DIRECTORY).resolve("TRK-001.csv"));
            if (lines.size() != 7) {
                throw new AssertionError("Expected header plus samples at seconds 0 through 5");
            }
            boolean[] expectedUpdated = {true, false, true, false, true, false};
            double predictedVarianceAtOne = 0.0;
            double updatedVarianceAtTwo = 0.0;
            for (int second = 0; second <= 5; second++) {
                String[] columns = lines.get(second + 1).split(",", -1);
                if (Double.parseDouble(columns[1]) != second
                        || Boolean.parseBoolean(columns[2]) != expectedUpdated[second]) {
                    throw new AssertionError(
                            "Incorrect time/update indicator at second " + second);
                }
                if (second == 1) {
                    predictedVarianceAtOne = Double.parseDouble(columns[12]);
                } else if (second == 2) {
                    updatedVarianceAtTwo = Double.parseDouble(columns[12]);
                }
            }
            if (!(predictedVarianceAtOne > updatedVarianceAtTwo)) {
                throw new AssertionError(
                        "Measurement update should reduce covariance below the prior coast prediction");
            }
            List<String> measurementLines = Files.readAllLines(recorder.runDirectory()
                    .resolve(TrackCsvRecorder.MEASUREMENT_DIRECTORY)
                    .resolve(TrackCsvRecorder.MEASUREMENT_FILE));
            if (measurementLines.size() != 4) {
                throw new AssertionError("Expected measurements at seconds 0, 2, and 4");
            }
            List<String> truthLines = Files.readAllLines(recorder.runDirectory()
                    .resolve(TrackCsvRecorder.GROUND_TRUTH_DIRECTORY).resolve("TGT-001.csv"));
            if (truthLines.size() != 57) {
                throw new AssertionError(
                        "Ground truth should stop on the rounded-down 0.1-second step");
            }
            String[] truthHeader = truthLines.get(0).split(",", -1);
            String[] lastTruth = truthLines.get(truthLines.size() - 1).split(",", -1);
            if (truthHeader.length != 12
                    || !"IsInBlackoutRegion".equals(truthHeader[11])
                    || lastTruth.length != 12) {
                throw new AssertionError("Ground truth should include blackout membership column");
            }
            requireClose(5.5, Double.parseDouble(lastTruth[1]), 1.0e-9,
                    "rounded-down truth endpoint");
            EcefVector expectedVelocity = target.ecefVelocityAt(5.5);
            requireClose(expectedVelocity.x(), Double.parseDouble(lastTruth[5]), 1.0e-9,
                    "last truth vx");
            requireClose(expectedVelocity.y(), Double.parseDouble(lastTruth[6]), 1.0e-9,
                    "last truth vy");
            requireClose(expectedVelocity.z(), Double.parseDouble(lastTruth[7]), 1.0e-9,
                    "last truth vz");
            double accelerationMagnitude = Math.sqrt(
                    Math.pow(Double.parseDouble(lastTruth[8]), 2.0)
                            + Math.pow(Double.parseDouble(lastTruth[9]), 2.0)
                            + Math.pow(Double.parseDouble(lastTruth[10]), 2.0));
            if (accelerationMagnitude > 1.0) {
                throw new AssertionError("Terminal truth acceleration should stay physically small");
            }
            verifyExplicitScenarioLengthCapsRecording(parent);
            System.out.println("ScenarioPrecomputeRecordingSmokeTest passed");
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

    private static void verifyExplicitScenarioLengthCapsRecording(Path parent) throws Exception {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory target = model.addTarget();
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(20.0, 11.0, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(20.005, 11.0, 0.0)));
        double wantedDuration = 5.55;
        double speed = target.surfaceLengthMeters() / wantedDuration;
        for (int index = 0; index < target.velocityProfile().sampleCount(); index++) {
            target.velocityProfile().setSample(index, speed);
        }
        double scenarioLength = 2.35;
        model.setScenarioLengthSeconds(scenarioLength);

        SensorSettings sensorSettings = new SensorSettings();
        sensorSettings.setParameters(new SensorParameters(
                1.0, 0.0, 1.0, 1.0, 1.0, 10));
        TrackCsvRecorder recorder = new TrackCsvRecorder();
        recorder.setOutputParent(parent);
        ScenarioPlayback playback = new ScenarioPlayback(
                model,
                () -> {
                },
                new MeasurementEngine(model, sensorSettings, new Random(12)),
                new ImmTracker(new ImmSettings()),
                recorder);

        if (!playback.precompute("capped")) {
            throw new AssertionError("Capped scenario should pre-compute");
        }
        if (recorder.runDirectory() != null) {
            throw new AssertionError("Pre-compute should not save capped CSV files automatically");
        }
        if (!playback.saveRecording("capped")) {
            throw new AssertionError("Explicit CSV export should save capped scenario data");
        }
        Path truthFile = recorder.runDirectory()
                .resolve(TrackCsvRecorder.GROUND_TRUTH_DIRECTORY)
                .resolve("TGT-001.csv");
        List<String> truthLines = Files.readAllLines(truthFile);
        double lastTruthTime = Double.parseDouble(
                truthLines.get(truthLines.size() - 1).split(",", -1)[1]);
        requireClose(scenarioLength, lastTruthTime, 1.0e-9,
                "explicit scenario truth endpoint");
        requireNoTimesBeyond(
                truthLines,
                1,
                scenarioLength,
                "ground-truth target CSV");
        try (var paths = Files.list(recorder.runDirectory()
                .resolve(TrackCsvRecorder.TRACK_DIRECTORY))) {
            for (Path trackFile : paths.filter(path -> path.toString().endsWith(".csv")).toList()) {
                requireNoTimesBeyond(
                        Files.readAllLines(trackFile),
                        1,
                        scenarioLength,
                        "track CSV " + trackFile.getFileName());
            }
        }
        verifyRunWindowCapsRecording(parent);
    }

    private static void verifyRunWindowCapsRecording(Path parent) throws Exception {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory target = model.addTarget();
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(20.0, 12.0, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(20.005, 12.0, 0.0)));
        double wantedDuration = 5.55;
        double speed = target.surfaceLengthMeters() / wantedDuration;
        for (int index = 0; index < target.velocityProfile().sampleCount(); index++) {
            target.velocityProfile().setSample(index, speed);
        }
        double runStart = 1.25;
        double runStop = 2.35;
        model.setRunWindowSeconds(runStart, runStop);

        SensorSettings sensorSettings = new SensorSettings();
        sensorSettings.setParameters(new SensorParameters(
                1.0, 0.0, 1.0, 1.0, 1.0, 10));
        TrackCsvRecorder recorder = new TrackCsvRecorder();
        recorder.setOutputParent(parent);
        ScenarioPlayback playback = new ScenarioPlayback(
                model,
                () -> {
                },
                new MeasurementEngine(model, sensorSettings, new Random(21)),
                new ImmTracker(new ImmSettings()),
                recorder);

        if (!playback.precompute("windowed")) {
            throw new AssertionError("Windowed scenario should pre-compute");
        }
        if (!playback.saveRecording("windowed")) {
            throw new AssertionError("Explicit CSV export should save windowed scenario data");
        }
        Path truthFile = recorder.runDirectory()
                .resolve(TrackCsvRecorder.GROUND_TRUTH_DIRECTORY)
                .resolve("TGT-001.csv");
        List<String> truthLines = Files.readAllLines(truthFile);
        double firstTruthTime = Double.parseDouble(truthLines.get(1).split(",", -1)[1]);
        double lastTruthTime = Double.parseDouble(
                truthLines.get(truthLines.size() - 1).split(",", -1)[1]);
        requireClose(runStart, firstTruthTime, 1.0e-9, "windowed truth start");
        requireClose(runStop, lastTruthTime, 1.0e-9, "windowed truth stop");
        requireNoTimesBefore(truthLines, 1, runStart, "windowed ground-truth target CSV");
        requireNoTimesBeyond(truthLines, 1, runStop, "windowed ground-truth target CSV");
        try (var paths = Files.list(recorder.runDirectory()
                .resolve(TrackCsvRecorder.TRACK_DIRECTORY))) {
            for (Path trackFile : paths.filter(path -> path.toString().endsWith(".csv")).toList()) {
                List<String> trackLines = Files.readAllLines(trackFile);
                requireNoTimesBefore(
                        trackLines,
                        1,
                        runStart,
                        "windowed track CSV " + trackFile.getFileName());
                requireNoTimesBeyond(
                        trackLines,
                        1,
                        runStop,
                        "windowed track CSV " + trackFile.getFileName());
            }
        }
    }

    private static void requireNoTimesBeyond(
            List<String> lines,
            int timeColumn,
            double maximumSeconds,
            String label) {
        for (int index = 1; index < lines.size(); index++) {
            double timeSeconds = Double.parseDouble(lines.get(index).split(",", -1)[timeColumn]);
            if (timeSeconds > maximumSeconds + 1.0e-9) {
                throw new AssertionError("%s should not include time %.3f past %.3f"
                        .formatted(label, timeSeconds, maximumSeconds));
            }
        }
    }

    private static void requireNoTimesBefore(
            List<String> lines,
            int timeColumn,
            double minimumSeconds,
            String label) {
        for (int index = 1; index < lines.size(); index++) {
            double timeSeconds = Double.parseDouble(lines.get(index).split(",", -1)[timeColumn]);
            if (timeSeconds < minimumSeconds - 1.0e-9) {
                throw new AssertionError("%s should not include time %.3f before %.3f"
                        .formatted(label, timeSeconds, minimumSeconds));
            }
        }
    }

    private static void requireClose(
            double expected,
            double actual,
            double tolerance,
            String label) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError("%s: expected %f but got %f".formatted(
                    label, expected, actual));
        }
    }
}
