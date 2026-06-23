package com.targettracker.ui;

import com.targettracker.model.PresetScenarioParameters;
import com.targettracker.model.SavedScenarioDefinition;
import com.targettracker.model.ScenarioPreset;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;

/** Main-window controls for loading deterministic maneuver scenarios. */
final class PresetScenarioPanel extends JPanel {
    interface Listener {
        void generatePreset(ScenarioPreset preset, PresetScenarioParameters parameters);

        void loadSavedScenario(SavedScenarioDefinition scenario);

        void selectUserGeneratedMode();

        void saveUserScenario(String scenarioName);
    }

    private final Component dialogParent;
    private final Listener listener;
    private final JComboBox<Object> presetSelector = new JComboBox<>();
    private final JTextField latitudeField = field("40.7000", 7);
    private final JTextField longitudeField = field("-74.0000", 8);
    private final JTextField speedField = field("100", 5);
    private final JTextField altitudeField = field("1000", 6);
    private final JTextField durationField = field("05:00", 5);
    private final JTextField saveNameField = field("My scenario", 12);
    private final JButton applyButton = new JButton("Apply preset");
    private final JButton saveButton = new JButton("Save user scenario");
    private final JLabel modeLabel = new JLabel("Manual editing");
    private boolean generationEnabled = true;
    private boolean synchronizing;

    PresetScenarioPanel(Component dialogParent, Listener listener) {
        this.dialogParent = dialogParent;
        this.listener = listener;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(246, 248, 251));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        JLabel title = new JLabel("Scenario");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18.0f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(4));
        JLabel note = new JLabel("<html>Load a generated maneuver scenario, or select "
                + "User generated to unlock target editing.</html>");
        note.setForeground(new Color(80, 92, 104));
        note.setAlignmentX(LEFT_ALIGNMENT);
        add(note);
        add(Box.createVerticalStrut(16));

        JLabel presetLabel = new JLabel("Scenario type");
        presetLabel.setForeground(new Color(61, 73, 84));
        presetLabel.setAlignmentX(LEFT_ALIGNMENT);
        add(presetLabel);
        add(Box.createVerticalStrut(4));

        presetSelector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        presetSelector.setAlignmentX(LEFT_ALIGNMENT);
        presetSelector.setToolTipText("Choose User generated to unlock manual target editing");
        setSavedScenarios(List.of());
        add(presetSelector);
        add(Box.createVerticalStrut(14));

        add(labeled("Center latitude (degrees)", latitudeField,
                "Scenario center latitude in degrees"));
        add(Box.createVerticalStrut(9));
        add(labeled("Center longitude (degrees)", longitudeField,
                "Scenario center longitude in degrees"));
        add(Box.createVerticalStrut(9));
        add(labeled("Average speed (m/s)", speedField,
                "Center of the target speed spread"));
        add(Box.createVerticalStrut(9));
        add(labeled("Altitude (m)", altitudeField,
                "WGS-84 ellipsoidal altitude"));
        add(Box.createVerticalStrut(9));
        add(labeled("Scenario time (mm:ss)", durationField,
                "Duration in mm:ss; minimum 05:00"));
        add(Box.createVerticalStrut(16));

        applyButton.setEnabled(false);
        applyButton.addActionListener(event -> applySelectedPreset());
        applyButton.setAlignmentX(LEFT_ALIGNMENT);
        applyButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        add(applyButton);
        add(Box.createVerticalStrut(10));
        modeLabel.setForeground(new Color(91, 103, 115));
        modeLabel.setAlignmentX(LEFT_ALIGNMENT);
        add(modeLabel);
        add(Box.createVerticalStrut(18));
        add(createSavePanel());

        presetSelector.addActionListener(event -> presetSelectionChanged());
        latitudeField.addActionListener(event -> applySelectedPreset());
        longitudeField.addActionListener(event -> applySelectedPreset());
        speedField.addActionListener(event -> applySelectedPreset());
        altitudeField.addActionListener(event -> applySelectedPreset());
        durationField.addActionListener(event -> applySelectedPreset());
    }

    private void presetSelectionChanged() {
        if (synchronizing) {
            return;
        }
        Object selected = presetSelector.getSelectedItem();
        if (selected instanceof SavedScenarioDefinition scenario) {
            applyButton.setEnabled(false);
            modeLabel.setText("Saved user scenario");
            modeLabel.setForeground(new Color(44, 112, 62));
            listener.loadSavedScenario(scenario);
            return;
        }
        ScenarioPreset preset = selected instanceof ScenarioPreset value ? value : null;
        if (preset == null || preset.isUserGenerated()) {
            applyButton.setEnabled(false);
            modeLabel.setText("Manual editing");
            modeLabel.setForeground(new Color(91, 103, 115));
            listener.selectUserGeneratedMode();
            return;
        }
        durationField.setText(formatDuration(preset.defaultDurationSeconds()));
        applyButton.setEnabled(generationEnabled);
        applySelectedPreset();
    }

    private void applySelectedPreset() {
        if (!generationEnabled) {
            return;
        }
        Object selected = presetSelector.getSelectedItem();
        ScenarioPreset preset = selected instanceof ScenarioPreset value ? value : null;
        if (preset == null || preset.isUserGenerated()) {
            return;
        }
        try {
            PresetScenarioParameters parameters = new PresetScenarioParameters(
                    parseDouble(latitudeField, "latitude"),
                    parseDouble(longitudeField, "longitude"),
                    parseDouble(speedField, "average speed"),
                    parseDouble(altitudeField, "altitude"),
                    parseDuration(durationField.getText()));
            listener.generatePreset(preset, parameters);
            modeLabel.setText("Preset locked");
            modeLabel.setForeground(new Color(132, 74, 17));
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(
                    dialogParent,
                    exception.getMessage(),
                    "Invalid preset scenario",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    void setGenerationEnabled(boolean enabled) {
        generationEnabled = enabled;
        presetSelector.setEnabled(enabled);
        latitudeField.setEnabled(enabled);
        longitudeField.setEnabled(enabled);
        speedField.setEnabled(enabled);
        altitudeField.setEnabled(enabled);
        durationField.setEnabled(enabled);
        saveNameField.setEnabled(enabled);
        saveButton.setEnabled(enabled);
        Object selected = presetSelector.getSelectedItem();
        ScenarioPreset preset = selected instanceof ScenarioPreset value ? value : null;
        applyButton.setEnabled(enabled && preset != null && !preset.isUserGenerated());
        if (!enabled) {
            modeLabel.setText("Disabled in Analysis Mode");
            modeLabel.setForeground(new Color(132, 74, 17));
        } else if (selected instanceof SavedScenarioDefinition) {
            modeLabel.setText("Saved user scenario");
            modeLabel.setForeground(new Color(44, 112, 62));
        } else if (preset == null || preset.isUserGenerated()) {
            modeLabel.setText("Manual editing");
            modeLabel.setForeground(new Color(91, 103, 115));
        } else {
            modeLabel.setText("Preset selected — press Apply");
            modeLabel.setForeground(new Color(132, 74, 17));
        }
    }

    void setSavedScenarios(List<SavedScenarioDefinition> scenarios) {
        Object selected = presetSelector.getSelectedItem();
        synchronizing = true;
        presetSelector.removeAllItems();
        for (ScenarioPreset preset : ScenarioPreset.values()) {
            presetSelector.addItem(preset);
        }
        for (SavedScenarioDefinition scenario : scenarios) {
            presetSelector.addItem(scenario);
        }
        if (selected != null) {
            presetSelector.setSelectedItem(selected);
        }
        if (presetSelector.getSelectedItem() == null) {
            presetSelector.setSelectedItem(ScenarioPreset.USER_GENERATED);
        }
        synchronizing = false;
    }

    private JPanel createSavePanel() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        JLabel label = new JLabel("Save user-generated scenario");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14.0f));
        label.setForeground(new Color(61, 73, 84));
        label.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(label);
        panel.add(Box.createVerticalStrut(6));
        panel.add(labeled("Scenario name", saveNameField,
                "Name used in the scenario dropdown and saved file"));
        panel.add(Box.createVerticalStrut(8));
        saveButton.setAlignmentX(LEFT_ALIGNMENT);
        saveButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        saveButton.addActionListener(event -> listener.saveUserScenario(saveNameField.getText()));
        panel.add(saveButton);
        return panel;
    }

    private static JPanel labeled(String label, JTextField field, String tooltip) {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        JLabel text = new JLabel(label);
        text.setToolTipText(tooltip);
        field.setToolTipText(tooltip);
        field.setPreferredSize(new Dimension(105, 28));
        field.setMaximumSize(new Dimension(105, 28));
        panel.add(text, BorderLayout.CENTER);
        panel.add(field, BorderLayout.EAST);
        return panel;
    }

    private static JTextField field(String value, int columns) {
        JTextField field = new JTextField(value, columns);
        Dimension preferred = field.getPreferredSize();
        field.setMaximumSize(new Dimension(preferred.width, 27));
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent event) {
                field.selectAll();
            }
        });
        return field;
    }

    private static double parseDouble(JTextField field, String name) {
        try {
            return Double.parseDouble(field.getText().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Enter a valid " + name, exception);
        }
    }

    private static int parseDuration(String text) {
        String[] parts = text.trim().split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Enter duration as minutes:seconds, for example 05:00");
        }
        try {
            int minutes = Integer.parseInt(parts[0]);
            int seconds = Integer.parseInt(parts[1]);
            if (minutes < 0 || seconds < 0 || seconds > 59) {
                throw new NumberFormatException();
            }
            return Math.addExact(Math.multiplyExact(minutes, 60), seconds);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Enter duration as non-negative minutes and 00-59 seconds", exception);
        }
    }

    private static String formatDuration(int durationSeconds) {
        return "%02d:%02d".formatted(durationSeconds / 60, durationSeconds % 60);
    }
}
