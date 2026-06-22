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

/** Ensures fractional look times retain their associated measurement rows. */
public final class ScenarioFractionalMeasurementRecordingSmokeTest {
    private ScenarioFractionalMeasurementRecordingSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory(Path.of("."), "fractional-recording-");
        try {
            ScenarioModel model = new ScenarioModel();
            TargetTrajectory target = model.addTarget();
            target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(10.0, 20.0, 0.0)));
            target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(10.001, 20.0, 0.0)));
            double speed = target.surfaceLengthMeters() / 2.2;
            for (int index = 0; index < target.velocityProfile().sampleCount(); index++) {
                target.velocityProfile().setSample(index, speed);
            }

            SensorSettings settings = new SensorSettings();
            settings.setParameters(new SensorParameters(1.0, 0.5, 1.0, 1.0, 1.0, 10));
            TrackCsvRecorder recorder = new TrackCsvRecorder();
            recorder.setOutputParent(parent);
            recorder.setArmed(true);
            ScenarioPlayback playback = new ScenarioPlayback(
                    model,
                    () -> {
                    },
                    new MeasurementEngine(model, settings, new Random(3)),
                    new ImmTracker(new ImmSettings()),
                    recorder);
            if (!playback.precompute("fractional looks")) {
                throw new AssertionError("Fractional-look scenario did not pre-compute");
            }

            List<String> lines = Files.readAllLines(
                    recorder.runDirectory().resolve(TrackCsvRecorder.TRACK_DIRECTORY)
                            .resolve("TRK-001.csv"));
            boolean fractionalMeasurementFound = false;
            boolean integerCoastFound = false;
            for (int index = 1; index < lines.size(); index++) {
                String[] columns = lines.get(index).split(",", -1);
                double time = Double.parseDouble(columns[1]);
                if (Math.abs(time - 0.5) < 1.0e-9
                        && Boolean.parseBoolean(columns[2])) {
                    fractionalMeasurementFound = true;
                }
                if (Math.abs(time - 1.0) < 1.0e-9
                        && !Boolean.parseBoolean(columns[2])) {
                    integerCoastFound = true;
                }
            }
            if (!fractionalMeasurementFound || !integerCoastFound) {
                throw new AssertionError(
                        "Fractional update rows and integer coast rows should both be retained");
            }
            List<String> measurementLines = Files.readAllLines(recorder.runDirectory()
                    .resolve(TrackCsvRecorder.MEASUREMENT_DIRECTORY)
                    .resolve(TrackCsvRecorder.MEASUREMENT_FILE));
            if (measurementLines.stream().noneMatch(line -> line.contains(",0.5,"))) {
                throw new AssertionError("Fractional measurement row was not saved separately");
            }
            System.out.println("ScenarioFractionalMeasurementRecordingSmokeTest passed");
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
