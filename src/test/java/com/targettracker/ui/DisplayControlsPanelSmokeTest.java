package com.targettracker.ui;

import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless check for shared layer toggles and history sliders. */
public final class DisplayControlsPanelSmokeTest {
    private DisplayControlsPanelSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(DisplayControlsPanelSmokeTest::runChecks);
        System.out.println("DisplayControlsPanelSmokeTest passed");
    }

    private static void runChecks() {
        DisplayHistorySettings settings = new DisplayHistorySettings();
        if (settings.groundTruthHistoryFraction() != 0.10
                || settings.measurementHistoryFraction() != 0.10) {
            throw new AssertionError("Layer history defaults should start at 10%");
        }
        AtomicInteger changes = new AtomicInteger();
        DisplayControlsPanel panel = new DisplayControlsPanel(settings, changes::incrementAndGet);
        List<JToggleButton> buttons = new ArrayList<>();
        List<JSlider> sliders = new ArrayList<>();
        for (Component component : panel.getComponents()) {
            if (component instanceof JToggleButton button) {
                buttons.add(button);
            } else if (component instanceof JSlider slider) {
                sliders.add(slider);
            }
        }
        if (buttons.size() != 4 || sliders.size() != 2) {
            throw new AssertionError("Expected four layer buttons and two history sliders");
        }
        buttons.get(0).doClick();
        buttons.get(1).doClick();
        buttons.get(2).doClick();
        buttons.get(3).doClick();
        sliders.get(0).setValue(25);
        sliders.get(1).setValue(60);
        if (settings.gridVisible()
                || settings.blackoutRegionsVisible()
                || settings.groundTruthVisible()
                || settings.measurementsVisible()
                || settings.groundTruthHistoryFraction() != 0.25
                || settings.measurementHistoryFraction() != 0.60
                || changes.get() < 6) {
            throw new AssertionError("Display controls did not update shared settings");
        }
    }
}
