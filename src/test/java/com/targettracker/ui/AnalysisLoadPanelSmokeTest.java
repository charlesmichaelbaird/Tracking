package com.targettracker.ui;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

/** Headless check for recorded-run folder discovery and selection. */
public final class AnalysisLoadPanelSmokeTest {
    private AnalysisLoadPanelSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path parent = Files.createTempDirectory(Path.of("."), "analysis-panel-");
        try {
            Path run = Files.createDirectory(parent.resolve("hard_left_2026-06-22_12-00-00_000"));
            Files.writeString(run.resolve("TRK-001.csv"), "track_id,time_s,updated\n");
            AtomicReference<Path> selected = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                AnalysisLoadPanel panel = new AnalysisLoadPanel(null, parent, selected::set);
                JComboBox<?> selector = null;
                JButton loadButton = null;
                JButton stitchingButton = null;
                for (Component component : panel.getComponents()) {
                    if (component instanceof JComboBox<?> comboBox) {
                        selector = comboBox;
                    } else if (component instanceof JButton button
                            && "Load scenario".equals(button.getText())) {
                        loadButton = button;
                    } else if (component instanceof JButton button
                            && "Track Stitching Analysis".equals(button.getText())) {
                        stitchingButton = button;
                    }
                }
                if (selector == null || selector.getItemCount() != 1
                        || loadButton == null || stitchingButton == null
                        || stitchingButton.isEnabled()) {
                    throw new AssertionError("Recorded scenario dropdown was not populated");
                }
                panel.setStitchingEnabled(true);
                if (!stitchingButton.isEnabled()) {
                    throw new AssertionError("Stitching button should enable after a scenario load");
                }
                loadButton.doClick();
            });
            if (!run.toAbsolutePath().normalize().equals(selected.get())) {
                throw new AssertionError("Load action did not return the selected run folder");
            }
            System.out.println("AnalysisLoadPanelSmokeTest passed");
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
