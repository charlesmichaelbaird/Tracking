package com.targettracker.ui;

import com.targettracker.model.SensorParameters;
import com.targettracker.model.SensorSettings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.List;
import java.util.function.DoublePredicate;

/** Independent, non-modal editor for the omniscient sensor configuration. */
final class SensorWindow extends JDialog {
    private final SensorSettings sensorSettings;
    private final Runnable onParametersChanged;
    private final JTextField lookTimingField;
    private final JTextField lookOffsetField;
    private final JTextField positionStandardDeviationField;
    private final JTextField velocityStandardDeviationField;
    private final JTextField probabilityOfDetectionField;
    private final JTextField previousMeasurementsField;
    private final List<JTextField> fields;
    private final JLabel validationLabel = new JLabel("Values apply immediately");

    SensorWindow(JFrame owner, SensorSettings sensorSettings, Runnable onParametersChanged) {
        super(owner, "God sensor parameters", false);
        this.sensorSettings = sensorSettings;
        this.onParametersChanged = onParametersChanged;
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setMinimumSize(new Dimension(410, 570));
        setSize(430, 610);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        content.setBackground(new Color(246, 248, 251));
        setContentPane(content);

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("God sensor parameters");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18.0f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        JLabel note = new JLabel("<html>Independent omniscient looks for every target.<br>"
                + "Select a value and type to overwrite it.</html>");
        note.setForeground(new Color(80, 92, 104));
        note.setAlignmentX(LEFT_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(note);
        content.add(header, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setBackground(Color.WHITE);
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(211, 218, 225)),
                BorderFactory.createEmptyBorder(12, 14, 8, 14)));
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        SensorParameters defaults = sensorSettings.parameters();
        lookTimingField = addField(
                form, "Measurement look timing (seconds)", defaults.lookIntervalSeconds());
        lookOffsetField = addField(
                form, "Measurement look offset (seconds)", defaults.lookOffsetSeconds());
        positionStandardDeviationField = addField(
                form,
                "3D position standard deviation error (meters)",
                defaults.positionStandardDeviationMeters());
        velocityStandardDeviationField = addField(
                form,
                "3D velocity standard deviation error (meters/second)",
                defaults.velocityStandardDeviationMetersPerSecond());
        probabilityOfDetectionField = addField(
                form,
                "Probability of detection (between 0 and 1)",
                defaults.probabilityOfDetection());
        previousMeasurementsField = addField(
                form,
                "How many previous measurements to show (0 to 10)",
                defaults.previousMeasurementsToShow());
        fields = List.of(
                lookTimingField,
                lookOffsetField,
                positionStandardDeviationField,
                velocityStandardDeviationField,
                probabilityOfDetectionField,
                previousMeasurementsField);
        content.add(form, BorderLayout.CENTER);

        DocumentListener inputListener = new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                commitSensorParameters();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                commitSensorParameters();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                commitSensorParameters();
            }
        };
        fields.forEach(field -> field.getDocument().addDocumentListener(inputListener));

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        validationLabel.setForeground(new Color(44, 112, 62));
        footer.add(validationLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton applyButton = new JButton("Apply");
        applyButton.addActionListener(event -> commitSensorParameters());
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(event -> setVisible(false));
        actions.add(applyButton);
        actions.add(closeButton);
        footer.add(actions, BorderLayout.EAST);
        content.add(footer, BorderLayout.SOUTH);
    }

    boolean commitSensorParameters() {
        Double lookTiming = parseDouble(lookTimingField, value -> value > 0.0);
        Double lookOffset = parseDouble(lookOffsetField, value -> value >= 0.0);
        Double positionDeviation = parseDouble(
                positionStandardDeviationField, value -> value >= 0.0);
        Double velocityDeviation = parseDouble(
                velocityStandardDeviationField, value -> value >= 0.0);
        Double probability = parseDouble(
                probabilityOfDetectionField, value -> value >= 0.0 && value <= 1.0);
        Integer previousMeasurements = parseInteger(
                previousMeasurementsField, value -> value >= 0.0 && value <= 10.0);
        if (lookTiming == null
                || lookOffset == null
                || positionDeviation == null
                || velocityDeviation == null
                || probability == null
                || previousMeasurements == null) {
            validationLabel.setText("Correct the highlighted value(s)");
            validationLabel.setForeground(new Color(177, 43, 43));
            return false;
        }

        sensorSettings.setParameters(new SensorParameters(
                lookTiming,
                lookOffset,
                positionDeviation,
                velocityDeviation,
                probability,
                previousMeasurements));
        validationLabel.setText("Values applied immediately");
        validationLabel.setForeground(new Color(44, 112, 62));
        onParametersChanged.run();
        return true;
    }

    private static JTextField addField(JPanel panel, String name, double value) {
        JLabel label = new JLabel(name);
        label.setForeground(new Color(61, 73, 84));
        label.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(label);
        panel.add(Box.createVerticalStrut(3));
        JTextField field = new JTextField(formatInputValue(value));
        field.setEditable(true);
        field.setEnabled(true);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 29));
        field.setAlignmentX(LEFT_ALIGNMENT);
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                SwingUtilities.invokeLater(field::selectAll);
            }
        });
        panel.add(field);
        panel.add(Box.createVerticalStrut(9));
        return field;
    }

    private static Double parseDouble(JTextField field, DoublePredicate isValid) {
        try {
            double value = Double.parseDouble(field.getText().trim());
            boolean valid = Double.isFinite(value) && isValid.test(value);
            field.setBackground(valid ? Color.WHITE : new Color(255, 224, 224));
            return valid ? value : null;
        } catch (NumberFormatException exception) {
            field.setBackground(new Color(255, 224, 224));
            return null;
        }
    }

    private static Integer parseInteger(JTextField field, DoublePredicate isValid) {
        try {
            int value = Integer.parseInt(field.getText().trim());
            boolean valid = isValid.test(value);
            field.setBackground(valid ? Color.WHITE : new Color(255, 224, 224));
            return valid ? value : null;
        } catch (NumberFormatException exception) {
            field.setBackground(new Color(255, 224, 224));
            return null;
        }
    }

    private static String formatInputValue(double value) {
        return value == Math.rint(value) ? "%.0f".formatted(value) : Double.toString(value);
    }
}
