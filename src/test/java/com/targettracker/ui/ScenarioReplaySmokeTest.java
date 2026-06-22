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

import javax.swing.SwingUtilities;
import java.util.Random;

/** Checks pre-compute capture, bidirectional seeking, filtering, and visual replay. */
public final class ScenarioReplaySmokeTest {
    private ScenarioReplaySmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory target = model.addTarget();
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.70, -74.02, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.72, -74.02, 0.0)));
        for (int index = 0; index < target.velocityProfile().sampleCount(); index++) {
            target.velocityProfile().setSample(index, 50.0);
            target.altitudeProfile().setSample(index, 1_000.0);
        }

        SensorSettings sensorSettings = new SensorSettings();
        sensorSettings.setParameters(new SensorParameters(1.0, 0.0, 0.0, 0.0, 1.0, 3));
        MeasurementEngine measurements = new MeasurementEngine(model, sensorSettings, new Random(9));
        ImmTracker tracker = new ImmTracker(new ImmSettings());
        TrackCsvRecorder recorder = new TrackCsvRecorder();
        ScenarioPlayback playback = new ScenarioPlayback(
                model, () -> {
                }, measurements, tracker, recorder);

        if (playback.canSeek()) {
            throw new AssertionError("Seeking should be locked before pre-computation");
        }
        if (!playback.precompute() || !playback.isReplayReady() || !playback.canSeek()) {
            throw new AssertionError("Pre-compute should create seekable replay data");
        }
        if (playback.replayFrameCount() < 300 || playback.elapsedSeconds() != 0.0) {
            throw new AssertionError("Pre-compute should capture a dense timeline and rewind to zero");
        }

        double duration = model.durationSeconds();
        if (!playback.seekTo(duration)) {
            throw new AssertionError("Completed pre-compute should seek to the end");
        }
        int completeTailSize = playback.currentTrackViews().get(0).tail().size();
        if (completeTailSize <= 180) {
            throw new AssertionError("Replay track tail should retain the track's complete lifetime");
        }

        playback.seekTo(5.0);
        int earlyTailSize = playback.currentTrackViews().get(0).tail().size();
        if (earlyTailSize >= completeTailSize || Math.abs(playback.elapsedSeconds() - 5.0) > 1.0e-9) {
            throw new AssertionError("Backward seek should restore the earlier track and time state");
        }
        if (measurements.visibleMeasurementsAt(2.1).size() != 3
                || measurements.visibleMeasurementsAt(0.1).size() != 1) {
            throw new AssertionError("Replay measurement display should be filtered by selected time");
        }

        recorder.setArmed(true);
        if (!playback.canSeek() || !playback.seekTo(10.0)) {
            throw new AssertionError("Armed-but-inactive recording should not block replay seeking");
        }
        recorder.setArmed(false);
        verifyVisualReplayCompletion();
        System.out.println("ScenarioReplaySmokeTest passed");
    }

    private static void verifyVisualReplayCompletion() throws Exception {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory target = model.addTarget();
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(0.0, 0.0, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(0.0002, 0.0, 0.0)));
        SensorSettings settings = new SensorSettings();
        MeasurementEngine engine = new MeasurementEngine(model, settings, new Random(2));
        ScenarioPlayback playback = new ScenarioPlayback(
                model, () -> {
                }, engine, new ImmTracker(new ImmSettings()), new TrackCsvRecorder());
        if (!playback.precompute()) {
            throw new AssertionError("Short scenario should pre-compute");
        }
        SwingUtilities.invokeAndWait(() -> {
            if (!playback.startReplay()) {
                throw new AssertionError("Pre-computed visual replay should start");
            }
        });
        Thread.sleep(400L);
        SwingUtilities.invokeAndWait(() -> {
        });
        if (playback.isRunning() || !playback.isReplayReady() || !playback.canSeek()) {
            throw new AssertionError("A completed visual replay should remain seekable");
        }
    }
}
