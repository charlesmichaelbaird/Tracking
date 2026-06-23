package com.targettracker.ui;

import com.targettracker.model.BlackoutRegion;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.SensorParameters;
import com.targettracker.model.SensorSettings;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.List;
import java.util.function.DoublePredicate;

/** Embedded editor for the omniscient sensor configuration. */
final class SensorParametersPanel extends JPanel {
    private final SensorSettings sensorSettings;
    private final ScenarioModel model;
    private final Runnable onParametersChanged;
    private final Runnable onAddBlackoutRegionRequested;
    private final JTextField lookTimingField;
    private final JTextField lookOffsetField;
    private final JTextField positionStandardDeviationField;
    private final JTextField velocityStandardDeviationField;
    private final JTextField probabilityOfDetectionField;
    private final JPanel blackoutListPanel = new JPanel();
    private final JLabel validationLabel = new JLabel("Values apply immediately");

    SensorParametersPanel(
            SensorSettings sensorSettings,
            ScenarioModel model,
            Runnable onParametersChanged,
            Runnable onAddBlackoutRegionRequested) {
        super(new BorderLayout(0, 12));
        this.sensorSettings = sensorSettings;
        this.model = model;
        this.onParametersChanged = onParametersChanged;
        this.onAddBlackoutRegionRequested = onAddBlackoutRegionRequested;
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        setBackground(new Color(246, 248, 251));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("God sensor parameters");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18.0f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        JLabel note = new JLabel("<html>Omniscient looks for every target.<br>"
                + "Select a value and type to overwrite it.</html>");
        note.setForeground(new Color(80, 92, 104));
        note.setAlignmentX(LEFT_ALIGNMENT);
        header.add(title);
        header.add(Box.createVerticalStrut(4));
        header.add(note);
        add(header, BorderLayout.NORTH);

        JPanel form = new JPanel();
        form.setBackground(Color.WHITE);
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(211, 218, 225)),
                BorderFactory.createEmptyBorder(12, 14, 8, 14)));
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        SensorParameters defaults = sensorSettings.parameters();
        lookTimingField = addField(form, "Measurement look timing (seconds)",
                defaults.lookIntervalSeconds());
        lookOffsetField = addField(form, "Measurement look offset (seconds)",
                defaults.lookOffsetSeconds());
        positionStandardDeviationField = addField(form,
                "3D position standard deviation error (meters)",
                defaults.positionStandardDeviationMeters());
        velocityStandardDeviationField = addField(form,
                "3D velocity standard deviation error (meters/second)",
                defaults.velocityStandardDeviationMetersPerSecond());
        probabilityOfDetectionField = addField(form,
                "Probability of detection (between 0 and 1)",
                defaults.probabilityOfDetection());
        form.add(createBlackoutRegionSection());
        add(form, BorderLayout.CENTER);

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
        List.of(lookTimingField, lookOffsetField, positionStandardDeviationField,
                        velocityStandardDeviationField, probabilityOfDetectionField)
                .forEach(field -> field.getDocument().addDocumentListener(inputListener));

        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        validationLabel.setForeground(new Color(44, 112, 62));
        footer.add(validationLabel, BorderLayout.CENTER);
        JButton applyButton = new JButton("Apply");
        applyButton.addActionListener(event -> commitSensorParameters());
        footer.add(applyButton, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
    }

    boolean commitSensorParameters() {
        Double lookTiming = parseDouble(lookTimingField, value -> value > 0.0);
        Double lookOffset = parseDouble(lookOffsetField, value -> value >= 0.0);
        Double positionDeviation = parseDouble(positionStandardDeviationField, value -> value >= 0.0);
        Double velocityDeviation = parseDouble(velocityStandardDeviationField, value -> value >= 0.0);
        Double probability = parseDouble(probabilityOfDetectionField,
                value -> value >= 0.0 && value <= 1.0);
        if (lookTiming == null || lookOffset == null || positionDeviation == null
                || velocityDeviation == null || probability == null) {
            validationLabel.setText("Correct the highlighted value(s)");
            validationLabel.setForeground(new Color(177, 43, 43));
            return false;
        }

        sensorSettings.setParameters(new SensorParameters(
                lookTiming, lookOffset, positionDeviation, velocityDeviation,
                probability, sensorSettings.parameters().previousMeasurementsToShow()));
        validationLabel.setText("Values applied immediately");
        validationLabel.setForeground(new Color(44, 112, 62));
        onParametersChanged.run();
        return true;
    }

    void refreshBlackoutRegions() {
        blackoutListPanel.removeAll();
        List<BlackoutRegion> regions = model.blackoutRegions();
        if (regions.isEmpty()) {
            JLabel empty = new JLabel("No blackout regions defined");
            empty.setForeground(new Color(91, 103, 115));
            blackoutListPanel.add(empty);
        } else {
            for (BlackoutRegion region : regions) {
                JLabel row = new JLabel("<html><b>%s</b> &nbsp; %.2f km × %.2f km</html>"
                        .formatted(
                                region.name(),
                                region.widthMeters() / 1_000.0,
                                region.heightMeters() / 1_000.0));
                row.setForeground(new Color(55, 65, 75));
                blackoutListPanel.add(row);
            }
        }
        blackoutListPanel.revalidate();
        blackoutListPanel.repaint();
    }

    private JPanel createBlackoutRegionSection() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Blackout regions"),
                BorderFactory.createEmptyBorder(4, 6, 6, 6)));

        JLabel note = new JLabel("<html>Sensor looks are suppressed inside shaded regions.</html>");
        note.setForeground(new Color(80, 92, 104));
        JButton addButton = new JButton("+");
        addButton.setToolTipText("Add a user-defined blackout rectangle with two map clicks");
        addButton.addActionListener(event -> onAddBlackoutRegionRequested.run());
        JPanel topRow = new JPanel(new BorderLayout(8, 0));
        topRow.setOpaque(false);
        topRow.add(note, BorderLayout.CENTER);
        topRow.add(addButton, BorderLayout.EAST);
        panel.add(topRow, BorderLayout.NORTH);

        blackoutListPanel.setOpaque(false);
        blackoutListPanel.setLayout(new GridLayout(0, 1, 0, 3));
        JScrollPane scrollPane = new JScrollPane(blackoutListPanel);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setPreferredSize(new Dimension(1, 76));
        panel.add(scrollPane, BorderLayout.CENTER);
        refreshBlackoutRegions();
        return panel;
    }

    private static JTextField addField(JPanel panel, String name, double value) {
        JLabel label = new JLabel(name);
        label.setForeground(new Color(61, 73, 84));
        label.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(label);
        panel.add(Box.createVerticalStrut(3));
        JTextField field = new JTextField(formatInputValue(value));
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

    private static String formatInputValue(double value) {
        return value == Math.rint(value) ? "%.0f".formatted(value) : Double.toString(value);
    }
}
