package com.targettracker.ui;

import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.SensorParameters;
import com.targettracker.model.SensorSettings;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.model.Wgs84;
import com.targettracker.tracking.ImmSettings;
import com.targettracker.tracking.ImmTracker;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Headless checks for deepest map zoom and rewind/pause playback behavior. */
public final class EarthMapCanvasSmokeTest {
    private EarthMapCanvasSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(EarthMapCanvasSmokeTest::runChecks);
        System.out.println("EarthMapCanvasSmokeTest passed");
    }

    private static void runChecks() {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory target = model.addTarget();
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.70, -74.02, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.71, -74.01, 0.0)));

        SensorSettings sensorSettings = new SensorSettings();
        MeasurementEngine measurementEngine = new MeasurementEngine(model, sensorSettings);
        ImmTracker immTracker = new ImmTracker(new ImmSettings());
        ScenarioPlayback playback = new ScenarioPlayback(model, () -> {
        }, measurementEngine, immTracker);
        EarthMapCanvas canvas = new EarthMapCanvas(
                model,
                playback,
                measurementEngine,
                () -> target,
                playback::isRunning,
                () -> {
                },
                ignored -> {
                });
        canvas.setSize(900, 600);

        for (int i = 0; i < 15; i++) {
            canvas.zoomIn();
        }
        if (!canvas.viewDescription().contains("32,768")) {
            throw new AssertionError("Map should reach its 32,768× deep-zoom limit");
        }

        BufferedImage rendered = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = rendered.createGraphics();
        canvas.paint(graphics);
        graphics.dispose();

        canvas.resetView();
        if (!"1.0×".equals(canvas.viewDescription())) {
            throw new AssertionError("World-view reset should restore 1.0× zoom");
        }

        sensorSettings.setParameters(new SensorParameters(15.0, 0.0, 0.0, 0.0, 1.0, 5));
        if (!playback.precompute() || !playback.rewindReplayPaused()) {
            throw new AssertionError("A runnable scenario should pre-compute and rewind successfully");
        }
        if (!playback.isRunning() || !playback.isPaused() || playback.elapsedSeconds() != 0.0) {
            throw new AssertionError("Rewind should leave playback at t=0 in a resumable paused state");
        }
        if (measurementEngine.visibleMeasurementsAt(playback.elapsedSeconds()).size() != 1) {
            throw new AssertionError("A zero-offset sensor should measure the target at reset time");
        }
        graphics = rendered.createGraphics();
        canvas.paint(graphics);
        graphics.dispose();

        playback.togglePause();
        if (playback.isPaused()) {
            throw new AssertionError("Pause toggle should resume after rewind");
        }
        TrackerFrame.clearPathForTarget(playback, target);
        if (playback.isRunning() || !target.path().isEmpty()) {
            throw new AssertionError(
                    "Clear Path should reset active playback and erase the selected trajectory");
        }
    }
}
