package com.targettracker.ui;

import com.targettracker.model.PresetScenarioParameters;
import com.targettracker.model.ScenarioPreset;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Headless check for preset selection wiring and default-width layout. */
public final class PresetScenarioPanelSmokeTest {
    private PresetScenarioPanelSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(PresetScenarioPanelSmokeTest::runChecks);
        System.out.println("PresetScenarioPanelSmokeTest passed");
    }

    @SuppressWarnings("unchecked")
    private static void runChecks() {
        AtomicReference<ScenarioPreset> generated = new AtomicReference<>();
        AtomicReference<PresetScenarioParameters> parameters = new AtomicReference<>();
        AtomicInteger manualSelections = new AtomicInteger();
        PresetScenarioPanel panel = new PresetScenarioPanel(null, new PresetScenarioPanel.Listener() {
            @Override
            public void generatePreset(
                    ScenarioPreset preset,
                    PresetScenarioParameters value) {
                generated.set(preset);
                parameters.set(value);
            }

            @Override
            public void selectUserGeneratedMode() {
                manualSelections.incrementAndGet();
            }
        });
        panel.setSize(1_120, 40);
        panel.doLayout();
        for (Component component : panel.getComponents()) {
            if (component.getX() < 0 || component.getX() + component.getWidth() > panel.getWidth()) {
                throw new AssertionError("Preset controls should fit the default main-window width");
            }
        }

        JComboBox<ScenarioPreset> selector = null;
        for (Component component : panel.getComponents()) {
            if (component instanceof JComboBox<?>) {
                selector = (JComboBox<ScenarioPreset>) component;
                break;
            }
        }
        if (selector == null) {
            throw new AssertionError("Preset dropdown is missing");
        }
        selector.setSelectedItem(ScenarioPreset.HARD_LEFT);
        if (generated.get() != ScenarioPreset.HARD_LEFT
                || parameters.get() == null || parameters.get().durationSeconds() != 300) {
            throw new AssertionError("Selecting a preset should generate it with the visible inputs");
        }
        selector.setSelectedItem(ScenarioPreset.USER_GENERATED);
        if (manualSelections.get() != 1) {
            throw new AssertionError("User-generated selection should restore manual mode");
        }
    }
}
