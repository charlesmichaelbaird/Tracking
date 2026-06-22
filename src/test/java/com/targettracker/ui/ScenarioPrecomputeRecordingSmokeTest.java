package com.targettracker.ui;

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
            double wantedDuration = 5.5;
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
            recorder.setArmed(true);
            ScenarioPlayback playback = new ScenarioPlayback(
                    model,
                    () -> {
                    },
                    measurements,
                    new ImmTracker(new ImmSettings()),
                    recorder);

            if (!playback.precompute() || recorder.isActive() || !playback.canSeek()) {
                throw new AssertionError(
                        "Recorded pre-compute should finish files and leave replay seekable");
            }
            List<String> lines = Files.readAllLines(recorder.runDirectory().resolve("TRK-001.csv"));
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
}
