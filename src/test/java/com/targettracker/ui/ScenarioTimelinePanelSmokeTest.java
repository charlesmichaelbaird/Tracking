package com.targettracker.ui;

import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.SensorSettings;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.model.Wgs84;
import com.targettracker.recording.TrackCsvRecorder;
import com.targettracker.tracking.ImmSettings;
import com.targettracker.tracking.ImmTracker;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.MouseEvent;

/** Headless interaction check for the video-style replay ruler. */
public final class ScenarioTimelinePanelSmokeTest {
    private ScenarioTimelinePanelSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(ScenarioTimelinePanelSmokeTest::runChecks);
        System.out.println("ScenarioTimelinePanelSmokeTest passed");
    }

    private static void runChecks() {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory target = model.addTarget();
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(35.0, -110.0, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(35.01, -110.0, 0.0)));
        SensorSettings sensorSettings = new SensorSettings();
        MeasurementEngine measurements = new MeasurementEngine(model, sensorSettings);
        TrackCsvRecorder recorder = new TrackCsvRecorder();
        ScenarioPlayback playback = new ScenarioPlayback(
                model,
                () -> {
                },
                measurements,
                new ImmTracker(new ImmSettings()),
                recorder);
        if (!playback.precompute()) {
            throw new AssertionError("Timeline test scenario should pre-compute");
        }

        ScenarioTimelinePanel panel = new ScenarioTimelinePanel(model, playback, recorder);
        panel.setSize(900, 150);
        panel.doLayout();
        JComponent ruler = findTimelineCanvas(panel);
        if (ruler == null || !ruler.isEnabled()) {
            throw new AssertionError("Replay-ready timeline ruler should be enabled");
        }
        int centerX = Math.max(1, ruler.getWidth() / 2);
        ruler.dispatchEvent(new MouseEvent(
                ruler,
                MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(),
                0,
                centerX,
                Math.max(1, ruler.getHeight() - 31),
                1,
                false));
        if (Math.abs(playback.elapsedSeconds() - model.durationSeconds() / 2.0) > 1.0) {
            throw new AssertionError("Dragging the ruler midpoint should seek near scenario midpoint");
        }

        recorder.setArmed(true);
        panel.refresh();
        if (!ruler.isEnabled()) {
            throw new AssertionError(
                    "Completed pre-compute should remain seekable after recording becomes inactive");
        }
        verifyRunWindowDrag();
    }

    private static void verifyRunWindowDrag() {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory target = model.addTarget();
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(35.0, -110.0, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(35.01, -110.0, 0.0)));
        model.setScenarioLengthSeconds(100.0);
        boolean[] changed = {false};

        ScenarioPlayback playback = new ScenarioPlayback(
                model,
                () -> {
                },
                new MeasurementEngine(model, new SensorSettings()),
                new ImmTracker(new ImmSettings()),
                new TrackCsvRecorder());
        ScenarioTimelinePanel panel = new ScenarioTimelinePanel(
                model,
                playback,
                new TrackCsvRecorder(),
                () -> false,
                () -> changed[0] = true);
        panel.setSize(900, 150);
        panel.doLayout();
        JComponent canvas = findTimelineCanvas(panel);
        if (canvas == null) {
            throw new AssertionError("Combined timeline canvas should be present");
        }

        drag(canvas, 882, 40, 480, 40);
        if (!changed[0]
                || Math.abs(model.runStopSeconds() - 50.0) > 2.0
                || Math.abs(model.runStartSeconds()) > 1.0e-9) {
            throw new AssertionError("Dragging the stop handle should shorten the run window");
        }

        drag(canvas, 280, 40, 360, 40);
        if (Math.abs(model.runStartSeconds() - 10.0) > 2.0
                || Math.abs(model.runStopSeconds() - 60.0) > 2.0) {
            throw new AssertionError("Dragging the grey run window should preserve its length");
        }
    }

    private static JComponent findTimelineCanvas(ScenarioTimelinePanel panel) {
        for (Component component : panel.getComponents()) {
            if (component.getClass().getSimpleName().equals("TimelineCanvas")) {
                return (JComponent) component;
            }
        }
        return null;
    }

    private static void drag(JComponent component, int startX, int startY, int endX, int endY) {
        long when = System.currentTimeMillis();
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_PRESSED,
                when,
                0,
                startX,
                startY,
                1,
                false));
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_DRAGGED,
                when + 1,
                0,
                endX,
                endY,
                1,
                false));
        component.dispatchEvent(new MouseEvent(
                component,
                MouseEvent.MOUSE_RELEASED,
                when + 2,
                0,
                endX,
                endY,
                1,
                false));
    }
}
