package com.targettracker.ui;

import com.targettracker.model.PresetScenarioParameters;
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
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;

/** Main-window controls for loading deterministic maneuver scenarios. */
final class PresetScenarioPanel extends JPanel {
    interface Listener {
        void generatePreset(ScenarioPreset preset, PresetScenarioParameters parameters);

        void selectUserGeneratedMode();
    }

    private final Component dialogParent;
    private final Listener listener;
    private final JComboBox<ScenarioPreset> presetSelector =
            new JComboBox<>(ScenarioPreset.values());
    private final JTextField latitudeField = field("40.7000", 7);
    private final JTextField longitudeField = field("-74.0000", 8);
    private final JTextField speedField = field("100", 5);
    private final JTextField altitudeField = field("1000", 6);
    private final JTextField durationField = field("05:00", 5);
    private final JButton applyButton = new JButton("Apply preset");
    private final JLabel modeLabel = new JLabel("Manual editing");

    PresetScenarioPanel(Component dialogParent, Listener listener) {
        this.dialogParent = dialogParent;
        this.listener = listener;
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(232, 235, 239)),
                BorderFactory.createEmptyBorder(5, 14, 5, 14)));

        JLabel title = new JLabel("Scenario:");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title);
        add(Box.createHorizontalStrut(7));

        presetSelector.setMaximumSize(new Dimension(285, 28));
        presetSelector.setPreferredSize(new Dimension(285, 28));
        presetSelector.setToolTipText("Choose User generated to unlock manual target editing");
        add(presetSelector);
        add(Box.createHorizontalStrut(10));

        add(labeled("Lat", latitudeField, "Scenario center latitude in degrees"));
        add(Box.createHorizontalStrut(7));
        add(labeled("Lon", longitudeField, "Scenario center longitude in degrees"));
        add(Box.createHorizontalStrut(7));
        add(labeled("Avg m/s", speedField, "Center of the target speed spread"));
        add(Box.createHorizontalStrut(7));
        add(labeled("Alt m", altitudeField, "WGS-84 ellipsoidal altitude"));
        add(Box.createHorizontalStrut(7));
        add(labeled("Time", durationField, "Duration in mm:ss; minimum 05:00"));
        add(Box.createHorizontalStrut(8));

        applyButton.setEnabled(false);
        applyButton.addActionListener(event -> applySelectedPreset());
        add(applyButton);
        add(Box.createHorizontalStrut(8));
        modeLabel.setForeground(new Color(91, 103, 115));
        add(modeLabel);

        presetSelector.addActionListener(event -> presetSelectionChanged());
        latitudeField.addActionListener(event -> applySelectedPreset());
        longitudeField.addActionListener(event -> applySelectedPreset());
        speedField.addActionListener(event -> applySelectedPreset());
        altitudeField.addActionListener(event -> applySelectedPreset());
        durationField.addActionListener(event -> applySelectedPreset());
    }

    private void presetSelectionChanged() {
        ScenarioPreset preset = (ScenarioPreset) presetSelector.getSelectedItem();
        if (preset == null || preset.isUserGenerated()) {
            applyButton.setEnabled(false);
            modeLabel.setText("Manual editing");
            modeLabel.setForeground(new Color(91, 103, 115));
            listener.selectUserGeneratedMode();
            return;
        }
        applyButton.setEnabled(true);
        applySelectedPreset();
    }

    private void applySelectedPreset() {
        ScenarioPreset preset = (ScenarioPreset) presetSelector.getSelectedItem();
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

    private static JPanel labeled(String label, JTextField field, String tooltip) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        JLabel text = new JLabel(label + ":");
        text.setToolTipText(tooltip);
        field.setToolTipText(tooltip);
        panel.add(text);
        panel.add(Box.createHorizontalStrut(3));
        panel.add(field);
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
}
