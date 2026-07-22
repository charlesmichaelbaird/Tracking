package com.targettracker.ui;

import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;

import javax.swing.JComboBox;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.atomic.AtomicReference;

/** Headless check for target selector sizing and Air/Ground presets. */
public final class TargetInspectorPanelSmokeTest {
    private TargetInspectorPanelSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(TargetInspectorPanelSmokeTest::runChecks);
        System.out.println("TargetInspectorPanelSmokeTest passed");
    }

    private static void runChecks() {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory target = model.addTarget();
        AtomicReference<TargetTrajectory> selected = new AtomicReference<>(target);
        AtomicReference<TargetTrajectory.PlatformType> selectedPlatform = new AtomicReference<>();
        TargetInspectorPanel panel = new TargetInspectorPanel(
                model,
                selected::set,
                platformType -> {
                    selectedPlatform.set(platformType);
                    selected.get().applyPlatformPreset(platformType);
                },
                () -> {
                });

        panel.setSize(420, 124);
        layoutTree(panel);
        JComboBox<?> selector = findComboBox(panel);
        if (selector == null) {
            throw new AssertionError("Target selector dropdown is missing");
        }
        if (selector.getWidth() <= 0 || selector.getWidth() > panel.getWidth() * 0.65) {
            throw new AssertionError("Target selector should be constrained to the left side");
        }
        JToggleButton airButton = findToggle(panel, "Air");
        JToggleButton groundButton = findToggle(panel, "Ground");
        if (airButton == null || groundButton == null) {
            throw new AssertionError("Air/Ground buttons are missing");
        }
        if (!airButton.isSelected()) {
            throw new AssertionError("New targets should show Air selected");
        }

        groundButton.doClick();
        if (selectedPlatform.get() != TargetTrajectory.PlatformType.GROUND
                || target.platformType() != TargetTrajectory.PlatformType.GROUND
                || target.velocityProfile().maximum() != 150.0
                || target.velocityProfile().sample(50) != 20.0
                || target.altitudeProfile().sample(50) != 0.0) {
            throw new AssertionError("Ground button should apply the Ground target preset");
        }

        airButton.doClick();
        if (selectedPlatform.get() != TargetTrajectory.PlatformType.AIR
                || target.platformType() != TargetTrajectory.PlatformType.AIR
                || target.velocityProfile().maximum() != 600.0
                || target.velocityProfile().sample(50) != 150.0
                || target.altitudeProfile().sample(50) != 3_000.0) {
            throw new AssertionError("Air button should apply the Air target preset");
        }
    }

    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    private static JComboBox<?> findComboBox(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof JComboBox<?> comboBox) {
                return comboBox;
            }
            if (child instanceof Container nested) {
                JComboBox<?> result = findComboBox(nested);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static JToggleButton findToggle(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JToggleButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container nested) {
                JToggleButton result = findToggle(nested, text);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }
}
