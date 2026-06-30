package com.targettracker.ui;

import com.targettracker.analysis.TrackStitchingAnalyzer;
import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.tracking.TrackRecord;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
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
        TrackStitchingAnalyzer.AnalysisResult detailsResult =
                new TrackStitchingAnalyzer().analyzeDetailed(
                        scenario(),
                        new TrackStitchingAnalyzer.Configuration(
                                0.0, 6.0, 0.0, 6.0, false, 0.5));
        AtomicReference<JComponent> detailsContentReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> detailsContentReference.set(
                TrackStitchingAnalysisDetailsWindow.createContent(detailsResult.events())));
        SwingUtilities.invokeAndWait(() -> {
            JTabbedPane tabs = tabsReference.get();
            JComponent detailsContent = detailsContentReference.get();
            if (findTabbedPaneWithTitle(detailsContent, "00:05") == null
                    || findTabbedPaneWithTitle(detailsContent, "TRK-001 -> TRK-002") == null) {
                throw new AssertionError(
                        "Detailed values window should expose event and pair tabs");
            }
            if (tabs.getTabCount() != 2
                    || !"00:05".equals(tabs.getTitleAt(0))
                    || !"00:06".equals(tabs.getTitleAt(1))) {
                throw new AssertionError("Embedded stitching tabs did not populate candidate time");
            }
            if (Math.abs(playbackReference.get().elapsedSeconds() - 5.0) > 1.0e-6) {
                throw new AssertionError("Selecting the stitching tab should seek replay time");
            }
            JTable table = tableReference.get();
            JTabbedPane outputTabs = outputTabsReference.get();
            if (outputTabs == null
                    || outputTabs.getSelectedIndex() != 1
                    || !"Physics-Aware".equals(outputTabs.getTitleAt(0))
                    || !"Min log-likelihood".equals(outputTabs.getTitleAt(1))
                    || outputTabs.getTabCount() < 4
                    || !"Gaussian overlap 3D".equals(outputTabs.getTitleAt(2))
                    || !"Gaussian overlap 6D".equals(outputTabs.getTitleAt(3))) {
                throw new AssertionError("Analysis output should default to minimum NLL");
            }
            if (table == null || table.getColumnCount() != 7) {
                throw new AssertionError(
                        "Stitching metrics table should include minimum NLL and toggles");
            }
            if (!"Time of min log-likelihood".equals(table.getColumnName(1))
                    || !"NLL".equals(table.getColumnName(2))
                    || !"Bridge NLLR".equals(table.getColumnName(3))
                    || !"User-volume NLLR".equals(table.getColumnName(4))) {
                throw new AssertionError("Minimum-NLL metric columns should be visible");
            }
            List<JSlider> sliders = new ArrayList<>();
            findSliders(panelReference.get(), sliders);
            long zeroMinimumSliders = sliders.stream()
                    .filter(slider -> "Minimum allowed time".equals(slider.getToolTipText()))
                    .filter(slider -> slider.getValue() == 0)
                    .count();
            if (zeroMinimumSliders < 2) {
                throw new AssertionError(
                        "Coasted and new-track minimum sliders should default to zero");
            }
            JComponent physicsAwarePanel =
                    findTitledComponent(panelReference.get(), "Physics-Aware");
            if (physicsAwarePanel == null
                    || physicsAwarePanel.getPreferredSize().height < 100
                    || findTextFieldWithText(physicsAwarePanel, "100.0") == null) {
                throw new AssertionError(
                        "Physics-Aware config inputs should expose the P_floor std field");
            }
            if (!"State".equals(table.getColumnName(5))
                    || !"Poly".equals(table.getColumnName(6))
                    || table.getColumnClass(5) != Boolean.class
                    || table.getColumnClass(6) != Boolean.class) {
                throw new AssertionError("Overlay columns should be Boolean State/Poly toggles");
            }
            if (!metricsTitle(table).contains("candidate 00:05")) {
                throw new AssertionError("Minimum-NLL table should show selected candidate time");
            }
            String firstMinimumNllRow = rowText(table, 0);
            tabs.setSelectedIndex(1);
            if (!metricsTitle(table).contains("candidate 00:06")) {
                throw new AssertionError("Minimum-NLL table should refresh candidate time");
            }
            String secondMinimumNllRow = rowText(table, 0);
            if (firstMinimumNllRow.equals(secondMinimumNllRow)) {
                throw new AssertionError("Minimum-NLL row should refresh from scenario time");
            }
            if (table.getRowCount() > 0) {
                table.setValueAt(Boolean.TRUE, 0, 5);
                table.setValueAt(Boolean.TRUE, 0, 6);
            }
            outputTabs.setSelectedIndex(0);
            if (!metricsTitle(table).contains("candidate 00:06")) {
                throw new AssertionError("Physics-Aware table should keep selected candidate time");
            }
            String secondPhysicsAwareRow = rowText(table, 0);
            tabs.setSelectedIndex(0);
            if (!metricsTitle(table).contains("candidate 00:05")) {
                throw new AssertionError("Physics-Aware table should refresh candidate time");
            }
            String firstPhysicsAwareRow = rowText(table, 0);
            if (firstPhysicsAwareRow.equals(secondPhysicsAwareRow)) {
                throw new AssertionError("Physics-Aware row should refresh from scenario time");
            }
            if (table.getColumnCount() != 9
                    || !"Time of min C_ijl".equals(table.getColumnName(1))
                    || !"V_ijl".equals(table.getColumnName(2))
                    || !"NLL".equals(table.getColumnName(3))
                    || !"Physics term".equals(table.getColumnName(4))
                    || !"Cost C_ijl".equals(table.getColumnName(5))
                    || !"State".equals(table.getColumnName(6))
                    || !"Poly".equals(table.getColumnName(7))
                    || !"Retro".equals(table.getColumnName(8))
                    || table.getColumnClass(8) != Boolean.class) {
                throw new AssertionError("Physics-Aware metric columns should be visible");
            }
            if (table.getRowCount() > 0) {
                table.setValueAt(Boolean.TRUE, 0, 8);
            }
            JButton detailsButton = findButton(panelReference.get(), "Pop out values");
            if (detailsButton == null || !detailsButton.isEnabled()) {
                throw new AssertionError("Detailed values pop-out button should be visible");
            }
            JButton detailsExportButton = findButton(panelReference.get(), "Export values");
            if (detailsExportButton == null || !detailsExportButton.isEnabled()) {
                throw new AssertionError("Detailed values export button should be visible");
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

    private static JComponent findTitledComponent(Container container, String title) {
        for (Component component : container.getComponents()) {
            if (component instanceof JComponent candidate) {
                String candidateTitle = titledBorderTitle(candidate.getBorder());
                if (title.equals(candidateTitle)) {
                    return candidate;
                }
            }
            if (component instanceof Container child) {
                JComponent candidate = findTitledComponent(child, title);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static JTextField findTextFieldWithText(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTextField field && text.equals(field.getText())) {
                return field;
            }
            if (component instanceof Container child) {
                JTextField field = findTextFieldWithText(child, text);
                if (field != null) {
                    return field;
                }
            }
        }
        return null;
    }

    private static String titledBorderTitle(Border border) {
        if (border instanceof TitledBorder titledBorder) {
            return titledBorder.getTitle();
        }
        if (border instanceof CompoundBorder compoundBorder) {
            String outsideTitle = titledBorderTitle(compoundBorder.getOutsideBorder());
            if (outsideTitle != null) {
                return outsideTitle;
            }
            return titledBorderTitle(compoundBorder.getInsideBorder());
        }
        return null;
    }

    private static void findSliders(Container container, List<JSlider> sliders) {
        for (Component component : container.getComponents()) {
            if (component instanceof JSlider slider) {
                sliders.add(slider);
            }
            if (component instanceof Container child) {
                findSliders(child, sliders);
            }
        }
    }

    private static String metricsTitle(JTable table) {
        if (table.getParent() != null
                && table.getParent().getParent() instanceof JScrollPane scrollPane
                && scrollPane.getBorder() instanceof TitledBorder titledBorder) {
            return titledBorder.getTitle();
        }
        return "";
    }

    private static String rowText(JTable table, int row) {
        if (table.getRowCount() <= row) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (int column = 0; column < table.getColumnCount(); column++) {
            if (column > 0) {
                text.append('|');
            }
            text.append(table.getValueAt(row, column));
        }
        return text.toString();
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
        for (int second = 1; second <= 6; second++) {
            tracks.add(track("TRK-001", second, 8.0 * second, 8.0, false));
        }
        tracks.add(track("TRK-002", 5.0, 50.0, 12.0, true));
        tracks.add(track("TRK-002", 6.0, 80.0, 20.0, true));
        tracks.add(track("TRK-003", 5.0, 120.0, -5.0, true));
        tracks.add(track("TRK-003", 6.0, 90.0, -15.0, true));
        List<GroundTruthRecord> truth = new ArrayList<>();
        for (int second = 0; second <= 6; second++) {
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
        RecordedMeasurement laterMeasurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-001", "TRK-002", 6.0,
                new double[]{6_378_137.0, 80, 0, 0, 20, 0},
                covariance, 1.0, 1.0);
        RecordedMeasurement falsePairMeasurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-002", "TRK-003", 5.0,
                new double[]{6_378_137.0, 95, 0, 0, -5, 0},
                covariance, 1.0, 1.0);
        RecordedMeasurement laterFalsePairMeasurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-002", "TRK-003", 6.0,
                new double[]{6_378_137.0, 90, 0, 0, -15, 0},
                covariance, 1.0, 1.0);
        return new RecordedScenario(
                Path.of("embedded_stitching"), "Embedded stitching", 6.0,
                tracks, truth, List.of(
                        oldMeasurement,
                        measurement,
                        laterMeasurement,
                        falsePairMeasurement,
                        laterFalsePairMeasurement));
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
