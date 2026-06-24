package com.targettracker.ui;

import com.targettracker.model.PresetScenarioParameters;
import com.targettracker.model.SavedScenarioDefinition;
import com.targettracker.model.ScenarioPreset;

import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
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
        AtomicReference<SavedScenarioDefinition> loadedSaved = new AtomicReference<>();
        AtomicReference<String> savedName = new AtomicReference<>();
        AtomicReference<Double> manualLength = new AtomicReference<>();
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
            public void loadSavedScenario(SavedScenarioDefinition scenario) {
                loadedSaved.set(scenario);
            }

            @Override
            public void selectUserGeneratedMode() {
                manualSelections.incrementAndGet();
            }

            @Override
            public void saveUserScenario(String scenarioName) {
                savedName.set(scenarioName);
            }

            @Override
            public void setUserScenarioLength(Double durationSeconds) {
                manualLength.set(durationSeconds);
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
        JButton setLengthButton = findButton(panel, "Set length");
        if (setLengthButton == null) {
            throw new AssertionError("Manual scenario length button is missing");
        }
        if (!"My scenario".equals(panel.scenarioNameForRecording("fallback"))) {
            throw new AssertionError("Manual recording should use the typed scenario name");
        }
        SavedScenarioDefinition savedScenario = new SavedScenarioDefinition(
                "Saved smoke",
                Path.of("saved_smoke.scenario"),
                List.of(),
                List.of());
        panel.setSavedScenarios(List.of(savedScenario));
        selector.setSelectedItem(savedScenario);
        if (loadedSaved.get() != savedScenario) {
            throw new AssertionError("Saved scenario selection should load from the dropdown");
        }
        setLengthButton = findButton(panel, "Set length");
        setLengthButton.doClick();
        if (manualLength.get() != null) {
            throw new AssertionError("Blank manual scenario length should clear the override");
        }
        if (!"Saved smoke".equals(panel.scenarioNameForRecording("Saved smoke"))) {
            throw new AssertionError("Saved scenarios should keep their loaded scenario name");
        }
        JButton saveButton = findButton(panel, "Save user scenario");
        if (saveButton == null) {
            throw new AssertionError("Scenario save button is missing");
        }
        saveButton.doClick();
        if (savedName.get() == null || savedName.get().isBlank()) {
            throw new AssertionError("Save button should forward the scenario name");
        }
    }

    private static JButton findButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container nested) {
                JButton result = findButton(nested, text);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }
}
