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
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

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
        AtomicInteger blackoutCallbacks = new AtomicInteger();
        AtomicReference<com.targettracker.model.BlackoutRegion> selectedBlackout =
                new AtomicReference<>();
        EarthMapCanvas canvas = new EarthMapCanvas(
                model,
                playback,
                measurementEngine,
                new DisplayHistorySettings(),
                () -> target,
                selectedBlackout::get,
                playback::isRunning,
                (region, center) -> {
                    com.targettracker.model.BlackoutRegion moved =
                            model.moveBlackoutRegion(region, center);
                    selectedBlackout.set(moved);
                    return moved;
                },
                () -> {
                },
                region -> {
                    selectedBlackout.set(region);
                    blackoutCallbacks.incrementAndGet();
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
        target.clearPath();
        canvas.setDrawingMode(EarthMapCanvas.DrawingMode.SEGMENTED);
        canvas.setTargetDrawingEnabled(false);
        click(canvas, 420, 280);
        if (!target.path().isEmpty()) {
            throw new AssertionError("Target clicks should be ignored outside the Targets panel");
        }
        canvas.setTargetDrawingEnabled(true);
        click(canvas, 420, 280);
        click(canvas, 480, 335);
        if (target.path().size() != 2) {
            throw new AssertionError("Segmented drawing should add clicked vertices");
        }
        canvas.finishPath();
        click(canvas, 520, 360);
        if (target.path().size() != 2) {
            throw new AssertionError(
                    "Finish Path should stop segmented clicks from extending the target");
        }
        GeodeticPoint firstBeforeMove = Wgs84.toGeodetic(target.path().get(0));
        canvas.setTargetDrawingEnabled(false);
        canvas.setMoveToolEnabled(true);
        press(canvas, 450, 308);
        drag(canvas, 500, 308);
        release(canvas, 500, 308);
        canvas.setMoveToolEnabled(false);
        GeodeticPoint firstAfterMove = Wgs84.toGeodetic(target.path().get(0));
        if (Math.abs(firstAfterMove.longitudeDegrees() - firstBeforeMove.longitudeDegrees())
                < 0.001) {
            throw new AssertionError("Move tool should translate the selected target path");
        }
        press(canvas, 500, 308);
        drag(canvas, 550, 308);
        release(canvas, 550, 308);
        GeodeticPoint firstAfterMoveDisabled = Wgs84.toGeodetic(target.path().get(0));
        if (Math.abs(firstAfterMoveDisabled.longitudeDegrees() - firstAfterMove.longitudeDegrees())
                > 1.0e-9) {
            throw new AssertionError("Target path should not move when the move tool is disabled");
        }
        canvas.setTargetDrawingEnabled(true);
        target.clearPath();
        canvas.setDrawingMode(EarthMapCanvas.DrawingMode.CIRCLE);
        click(canvas, 420, 280);
        click(canvas, 480, 280);
        click(canvas, 480, 280);
        click(canvas, 420, 220);
        if (target.path().size() < 40) {
            throw new AssertionError("Circle drawing should create a sampled closed trajectory");
        }
        target.clearPath();
        canvas.setDrawingMode(EarthMapCanvas.DrawingMode.RACETRACK);
        click(canvas, 420, 280);
        click(canvas, 520, 280);
        click(canvas, 420, 255);
        click(canvas, 520, 255);
        if (target.path().size() < 40) {
            throw new AssertionError("Racetrack drawing should create a sampled loop trajectory");
        }
        canvas.setDrawingMode(EarthMapCanvas.DrawingMode.FREE_HAND);
        canvas.focusOnPoints(target.path());
        if ("1.0×".equals(canvas.viewDescription())) {
            throw new AssertionError("Scenario extent focus should zoom into its local region");
        }
        canvas.resetView();
        canvas.startBlackoutRegionDrawing();
        click(canvas, 420, 280);
        if (!model.blackoutRegions().isEmpty()) {
            throw new AssertionError("First blackout click should only store the first corner");
        }
        click(canvas, 480, 335);
        if (model.blackoutRegions().size() != 1 || blackoutCallbacks.get() != 1) {
            throw new AssertionError("Second blackout click should create one user region");
        }
        GeodeticPoint blackoutCenterBefore = model.blackoutRegions().get(0).center();
        canvas.setBlackoutEditingEnabled(false);
        canvas.setMoveToolEnabled(true);
        press(canvas, 450, 308);
        drag(canvas, 500, 308);
        release(canvas, 500, 308);
        canvas.setMoveToolEnabled(false);
        GeodeticPoint blackoutCenterAfter = model.blackoutRegions().get(0).center();
        if (Math.abs(blackoutCenterAfter.longitudeDegrees()
                - blackoutCenterBefore.longitudeDegrees()) < 0.001) {
            throw new AssertionError("Move tool should translate blackout regions globally");
        }
        model.clearBlackoutRegions();

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

    private static void click(EarthMapCanvas canvas, int x, int y) {
        press(canvas, x, y);
    }

    private static void press(EarthMapCanvas canvas, int x, int y) {
        canvas.dispatchEvent(new MouseEvent(
                canvas,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                x,
                y,
                1,
                false,
                MouseEvent.BUTTON1));
    }

    private static void drag(EarthMapCanvas canvas, int x, int y) {
        canvas.dispatchEvent(new MouseEvent(
                canvas,
                MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(),
                0,
                x,
                y,
                0,
                false,
                MouseEvent.BUTTON1));
    }

    private static void release(EarthMapCanvas canvas, int x, int y) {
        canvas.dispatchEvent(new MouseEvent(
                canvas,
                MouseEvent.MOUSE_RELEASED,
                System.currentTimeMillis(),
                0,
                x,
                y,
                1,
                false,
                MouseEvent.BUTTON1));
    }
}
