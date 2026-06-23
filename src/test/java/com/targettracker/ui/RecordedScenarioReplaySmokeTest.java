package com.targettracker.ui;

import com.targettracker.model.ScenarioModel;
import com.targettracker.model.SensorSettings;
import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.recording.TrackCsvRecorder;
import com.targettracker.tracking.ImmSettings;
import com.targettracker.tracking.ImmTracker;
import com.targettracker.tracking.TrackRecord;

import java.nio.file.Path;
import java.util.List;

/** Checks that loaded CSV snapshots drive seeking, tracks, and measurements. */
public final class RecordedScenarioReplaySmokeTest {
    private RecordedScenarioReplaySmokeTest() {
    }

    public static void main(String[] args) {
        ScenarioModel model = new ScenarioModel();
        model.addTarget();
        SensorSettings settings = new SensorSettings();
        MeasurementEngine measurements = new MeasurementEngine(model, settings);
        ScenarioPlayback playback = new ScenarioPlayback(
                model,
                () -> {
                },
                measurements,
                new ImmTracker(new ImmSettings()),
                new TrackCsvRecorder());

        RecordedScenario scenario = new RecordedScenario(
                Path.of("recorded_test"),
                "Recorded test",
                4.0,
                List.of(record(0.0, true), record(1.0, false), record(2.0, true)),
                List.of(truth(0.0), truth(1.0), truth(2.0)),
                List.of(measurement(0.0), measurement(2.0)));
        playback.loadRecordedScenario(scenario);
        if (!playback.isReplayReady() || !playback.canSeek()
                || playback.durationSeconds() != 4.0) {
            throw new AssertionError("Loaded scenario should immediately become seekable");
        }
        playback.seekTo(1.0);
        if (playback.currentTrackViews().size() != 1
                || playback.currentTrackViews().get(0).tail().size() != 2) {
            throw new AssertionError("Loaded track state and full replay tail are incorrect");
        }
        if (playback.currentGroundTruthViews().size() != 1
                || playback.currentGroundTruthViews().get(0).history().size() != 2
                || playback.scenarioExtentPoints().isEmpty()) {
            throw new AssertionError("Loaded ground truth was not rebuilt for replay and map focus");
        }
        playback.seekTo(2.0);
        if (measurements.visibleMeasurementsAt(2.0).size() != 2) {
            throw new AssertionError("Associated measurements should be restored for display");
        }
        playback.seekTo(4.0);
        if (playback.currentTrackViews().size() != 1
                || !playback.currentTrackViews().get(0).dead()) {
            throw new AssertionError("Loaded replay should retain stale tracks as dead tracks");
        }
        if (!playback.rewindReplayPaused() || !playback.isPaused()) {
            throw new AssertionError("Loaded scenario should support pause/resume replay controls");
        }
        playback.stop();
        playback.loadRecordedScenario(new RecordedScenario(
                Path.of("truth_only"),
                "Truth only",
                2.0,
                List.of(),
                List.of(truth(0.0), truth(1.0), truth(2.0)),
                List.of()));
        playback.seekTo(1.0);
        if (!playback.currentTrackViews().isEmpty()
                || playback.currentGroundTruthViews().size() != 1) {
            throw new AssertionError("Truth-only recordings should also remain replayable");
        }
        System.out.println("RecordedScenarioReplaySmokeTest passed");
    }

    private static TrackRecord record(double time, boolean updated) {
        double[] state = {6_378_137.0, time * 100.0, 0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 0.0};
        double[][] covariance = new double[9][9];
        for (int index = 0; index < 9; index++) {
            covariance[index][index] = 4.0;
        }
        return new TrackRecord("TRK-001", time, state, covariance, updated);
    }

    private static GroundTruthRecord truth(double time) {
        return new GroundTruthRecord(
                "TGT-001",
                time,
                new double[]{6_378_137.0, time * 95.0, 0.0, 0.0, 95.0, 0.0, 0.0, 0.0, 0.0});
    }

    private static RecordedMeasurement measurement(double time) {
        double[] mean = {6_378_137.0, time * 100.0, 0.0, 0.0, 100.0, 0.0};
        double[][] covariance = new double[6][6];
        for (int index = 0; index < 6; index++) {
            covariance[index][index] = 1.0;
        }
        return new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-001", time, mean, covariance, 1.0, 1.0);
    }
}
