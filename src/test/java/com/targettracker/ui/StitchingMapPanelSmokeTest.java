package com.targettracker.ui;

import com.targettracker.analysis.TrackStitchingAnalyzer;
import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.tracking.TrackRecord;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Headless rendering check for timestamp-tab stitching maps. */
public final class StitchingMapPanelSmokeTest {
    private StitchingMapPanelSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        RecordedScenario scenario = scenario();
        TrackStitchingAnalyzer.EventResult event = new TrackStitchingAnalyzer()
                .analyze(scenario, new TrackStitchingAnalyzer.Configuration(
                        1.0, 10.0, 0.0, 1.0, false, 0.5))
                .get(0);
        SwingUtilities.invokeAndWait(() -> {
            StitchingMapPanel panel = new StitchingMapPanel(scenario, event);
            panel.setSize(900, 600);
            BufferedImage image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            panel.paint(graphics);
            graphics.dispose();
            if (image.getRGB(450, 300) == 0) {
                throw new AssertionError("Stitching map did not render scenario content");
            }
        });
        System.out.println("StitchingMapPanelSmokeTest passed");
    }

    private static RecordedScenario scenario() {
        List<TrackRecord> tracks = new ArrayList<>();
        tracks.add(track("TRK-001", 0.0, 0.0, 8.0, true));
        for (int second = 1; second <= 5; second++) {
            tracks.add(track("TRK-001", second, 8.0 * second, 8.0, false));
        }
        tracks.add(track("TRK-002", 5.0, 50.0, 12.0, true));
        List<GroundTruthRecord> truth = new ArrayList<>();
        for (int second = 0; second <= 5; second++) {
            truth.add(new GroundTruthRecord(
                    "TGT-001", second,
                    new double[]{6_378_137.0, second * 10.0, 0, 0, 10, 0, 0, 0, 0}));
        }
        double[][] covariance = new double[6][6];
        for (int index = 0; index < 6; index++) {
            covariance[index][index] = 1.0;
        }
        RecordedMeasurement measurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-001", 5.0,
                new double[]{6_378_137.0, 50, 0, 0, 10, 0},
                covariance, 1.0, 1.0);
        return new RecordedScenario(
                Path.of("stitching_map"), "Stitching map", 5.0,
                tracks, truth, List.of(measurement));
    }

    private static TrackRecord track(
            String id, double time, double alongTrack, double speed, boolean updated) {
        double[] state = {6_378_137.0, alongTrack, 0, 0, speed, 0, 0, 0, 0};
        double[][] covariance = new double[9][9];
        for (int index = 0; index < 9; index++) {
            covariance[index][index] = 4.0;
        }
        return new TrackRecord(id, time, state, covariance, updated);
    }
}
