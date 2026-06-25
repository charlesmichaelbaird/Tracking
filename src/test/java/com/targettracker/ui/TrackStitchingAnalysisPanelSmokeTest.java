package com.targettracker.ui;

import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.tracking.TrackRecord;

import javax.swing.JButton;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Headless check for embedded stitching analysis driving the main replay time. */
public final class TrackStitchingAnalysisPanelSmokeTest {
    private TrackStitchingAnalysisPanelSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        AtomicReference<ScenarioPlayback> playbackReference = new AtomicReference<>();
        AtomicReference<TrackStitchingAnalysisPanel> panelReference = new AtomicReference<>();
        AtomicReference<JTabbedPane> tabsReference = new AtomicReference<>();
        AtomicReference<JTabbedPane> outputTabsReference = new AtomicReference<>();
        AtomicReference<JTable> tableReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            RecordedScenario scenario = scenario();
            var model = new com.targettracker.model.ScenarioModel();
            var sensorSettings = new com.targettracker.model.SensorSettings();
            var measurements = new MeasurementEngine(model, sensorSettings);
            var recorder = new com.targettracker.recording.TrackCsvRecorder();
            ScenarioPlayback playback = new ScenarioPlayback(
                    model,
                    () -> {
                    },
                    measurements,
                    new com.targettracker.tracking.ImmTracker(
                            new com.targettracker.tracking.ImmSettings()),
                    recorder);
            playback.loadRecordedScenario(scenario);
            var timeline = new ScenarioTimelinePanel(model, playback, recorder);
            var canvas = new EarthMapCanvas(
                    model,
                    playback,
                    measurements,
                    new DisplayHistorySettings(),
                    () -> null,
                    () -> null,
                    () -> true,
                    (region, center) -> region,
                    () -> {
                    },
                    ignored -> {
                    },
                    ignored -> {
                    });
            TrackStitchingAnalysisPanel panel = new TrackStitchingAnalysisPanel(
                    scenario,
                    playback,
                    canvas,
                    timeline,
                    () -> {
                    },
                    ignored -> {
                    });
            panelReference.set(panel);
            playbackReference.set(playback);
            tabsReference.set((JTabbedPane) panel.tabStrip());
            outputTabsReference.set(findTabbedPaneWithTitle(panel, "Gaussian overlap 3D"));
            tableReference.set(findTable(panel));
        });

        waitForAnalysis(tabsReference.get());
        SwingUtilities.invokeAndWait(() -> {
            JTabbedPane tabs = tabsReference.get();
            if (tabs.getTabCount() != 1 || !"00:05.0".equals(tabs.getTitleAt(0))) {
                throw new AssertionError("Embedded stitching tabs did not populate candidate time");
            }
            if (Math.abs(playbackReference.get().elapsedSeconds() - 5.0) > 1.0e-6) {
                throw new AssertionError("Selecting the stitching tab should seek replay time");
            }
            JTable table = tableReference.get();
            JTabbedPane outputTabs = outputTabsReference.get();
            if (outputTabs == null
                    || outputTabs.getSelectedIndex() != 0
                    || !"Gaussian overlap 3D".equals(outputTabs.getTitleAt(0))
                    || outputTabs.getTabCount() < 2
                    || !"Gaussian overlap 6D".equals(outputTabs.getTitleAt(1))) {
                throw new AssertionError("Analysis output should default to 3D Gaussian overlap");
            }
            if (table == null || table.getColumnCount() != 7) {
                throw new AssertionError(
                        "Stitching metrics table should include overlap metrics and toggles");
            }
            if (!"Bhattacharyya Distance".equals(table.getColumnName(2))
                    || !"Bhattacharyya Coefficient".equals(table.getColumnName(3))
                    || !"Hellinger Distance".equals(table.getColumnName(4))) {
                throw new AssertionError("Gaussian-overlap metric columns should be visible");
            }
            if (!"State".equals(table.getColumnName(5))
                    || !"Poly".equals(table.getColumnName(6))
                    || table.getColumnClass(5) != Boolean.class
                    || table.getColumnClass(6) != Boolean.class) {
                throw new AssertionError("Overlay columns should be Boolean State/Poly toggles");
            }
            if (table.getRowCount() > 0) {
                table.setValueAt(Boolean.TRUE, 0, 5);
                table.setValueAt(Boolean.TRUE, 0, 6);
            }
            JButton rocButton = findButton(panelReference.get(), "ROC Curve");
            if (rocButton == null) {
                throw new AssertionError("Top-level ROC Curve button should be visible");
            }
            rocButton.doClick();
        });
        waitForRoc(panelReference.get());
        SwingUtilities.invokeAndWait(() -> {
            JTabbedPane rocTabs = findTabbedPaneWithTitle(panelReference.get(), "ROC");
            if (rocTabs == null || rocTabs.getTabCount() != 1) {
                throw new AssertionError("ROC output should show one combined curve tab");
            }
            if (!"RocCurveChart".equals(rocTabs.getComponentAt(0).getClass().getSimpleName())) {
                throw new AssertionError("ROC tab should contain the generated curve graphic");
            }
        });
        System.out.println("TrackStitchingAnalysisPanelSmokeTest passed");
    }

    private static JButton findButton(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton button = findButton(child, text);
                if (button != null) {
                    return button;
                }
            }
        }
        return null;
    }

    private static JTable findTable(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTable table) {
                return table;
            }
            if (component instanceof Container child) {
                JTable table = findTable(child);
                if (table != null) {
                    return table;
                }
            }
        }
        return null;
    }

    private static JTabbedPane findTabbedPaneWithTitle(Container container, String title) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTabbedPane tabs) {
                for (int index = 0; index < tabs.getTabCount(); index++) {
                    if (title.equals(tabs.getTitleAt(index))) {
                        return tabs;
                    }
                }
            }
            if (component instanceof Container child) {
                JTabbedPane tabs = findTabbedPaneWithTitle(child, title);
                if (tabs != null) {
                    return tabs;
                }
            }
        }
        return null;
    }

    private static void waitForAnalysis(JTabbedPane tabs) throws Exception {
        long deadlineMillis = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < deadlineMillis) {
            AtomicReference<String> title = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() ->
                    title.set(tabs.getTabCount() == 0 ? "" : tabs.getTitleAt(0)));
            if (!"Analyzing...".equals(title.get())) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Embedded stitching analysis did not finish");
    }

    private static void waitForRoc(Container container) throws Exception {
        long deadlineMillis = System.currentTimeMillis() + 4_000;
        while (System.currentTimeMillis() < deadlineMillis) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> {
                JTabbedPane tabs = findTabbedPaneWithTitle(container, "ROC");
                ready.set(tabs != null
                        && tabs.getTabCount() == 1
                        && "RocCurveChart".equals(
                        tabs.getComponentAt(0).getClass().getSimpleName()));
            });
            if (ready.get()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("ROC curve computation did not finish");
    }

    private static RecordedScenario scenario() {
        List<TrackRecord> tracks = new ArrayList<>();
        tracks.add(track("TRK-001", 0.0, 0.0, 8.0, true));
        for (int second = 1; second <= 5; second++) {
            tracks.add(track("TRK-001", second, 8.0 * second, 8.0, false));
        }
        tracks.add(track("TRK-002", 5.0, 50.0, 12.0, true));
        tracks.add(track("TRK-003", 5.0, 120.0, -5.0, true));
        List<GroundTruthRecord> truth = new ArrayList<>();
        for (int second = 0; second <= 5; second++) {
            truth.add(new GroundTruthRecord(
                    "TGT-001", second,
                    new double[]{6_378_137.0, second * 10.0, 0, 0, 10, 0, 0, 0, 0}));
            truth.add(new GroundTruthRecord(
                    "TGT-002", second,
                    new double[]{6_378_137.0, 120.0 - second * 5.0, 0, 0, -5, 0, 0, 0, 0}));
        }
        double[][] covariance = new double[6][6];
        for (int index = 0; index < 6; index++) {
            covariance[index][index] = 1.0;
        }
        RecordedMeasurement oldMeasurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-001", "TRK-001", 0.0,
                new double[]{6_378_137.0, 0, 0, 0, 10, 0},
                covariance, 1.0, 1.0);
        RecordedMeasurement measurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-001", "TRK-002", 5.0,
                new double[]{6_378_137.0, 50, 0, 0, 10, 0},
                covariance, 1.0, 1.0);
        RecordedMeasurement falsePairMeasurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-002", "TRK-003", 5.0,
                new double[]{6_378_137.0, 95, 0, 0, -5, 0},
                covariance, 1.0, 1.0);
        return new RecordedScenario(
                Path.of("embedded_stitching"), "Embedded stitching", 5.0,
                tracks, truth, List.of(oldMeasurement, measurement, falsePairMeasurement));
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
