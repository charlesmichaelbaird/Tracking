package com.targettracker.ui;

import com.targettracker.model.BlackoutRegion;
import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.SensorSettings;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Headless checks for the sensor editor and user blackout-region controls. */
public final class SensorParametersPanelSmokeTest {
    private SensorParametersPanelSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(SensorParametersPanelSmokeTest::runChecks);
        System.out.println("SensorParametersPanelSmokeTest passed");
    }

    private static void runChecks() {
        ScenarioModel model = new ScenarioModel();
        SensorSettings settings = new SensorSettings();
        AtomicInteger addRequests = new AtomicInteger();
        AtomicInteger removeRequests = new AtomicInteger();
        SensorParametersPanel panel = new SensorParametersPanel(
                settings,
                model,
                () -> {
                },
                addRequests::incrementAndGet,
                ignored -> {
                },
                region -> {
                    removeRequests.incrementAndGet();
                    model.removeBlackoutRegion(region);
                });

        List<String> labels = new ArrayList<>();
        collectLabels(panel, labels);
        if (labels.stream().anyMatch(text -> text.contains("previous measurements"))) {
            throw new AssertionError("Previous measurement count should not be visible");
        }

        JButton addButton = findButton(panel, "+");
        if (addButton == null) {
            throw new AssertionError("Blackout '+' button is missing");
        }
        addButton.doClick();
        if (addRequests.get() != 1) {
            throw new AssertionError("Blackout '+' button should arm map drawing");
        }

        model.addBlackoutRegion(new BlackoutRegion(
                "BLK-001",
                new GeodeticPoint(0.0, 0.0, 0.0),
                1_000.0,
                2_000.0));
        panel.refreshBlackoutRegions();
        labels.clear();
        collectLabels(panel, labels);
        if (labels.stream().anyMatch(text -> text.contains("BLK-001"))) {
            throw new AssertionError("Blackout list should not show internal region IDs");
        }
        if (labels.stream().noneMatch(text -> text.contains("1.00 km"))) {
            throw new AssertionError("Blackout list should still show region dimensions");
        }
        JButton removeButton = findButton(panel, "Remove");
        if (removeButton == null) {
            throw new AssertionError("Blackout remove button is missing");
        }
        removeButton.doClick();
        panel.refreshBlackoutRegions();
        if (removeRequests.get() != 1 || !model.blackoutRegions().isEmpty()) {
            throw new AssertionError("Blackout remove button should remove the selected region");
        }
    }

    private static JButton findButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container nested) {
                JButton found = findButton(nested, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectLabels(Container container, List<String> labels) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label) {
                labels.add(label.getText());
            }
            if (child instanceof Container nested) {
                collectLabels(nested, labels);
            }
        }
    }
}
