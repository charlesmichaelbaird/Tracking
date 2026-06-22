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
        panel.setSize(900, 84);
        panel.doLayout();
        JComponent ruler = null;
        for (Component component : panel.getComponents()) {
            if (component.getClass().getSimpleName().equals("TimelineRuler")) {
                ruler = (JComponent) component;
                break;
            }
        }
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
                Math.max(1, ruler.getHeight() / 2),
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
    }
}
