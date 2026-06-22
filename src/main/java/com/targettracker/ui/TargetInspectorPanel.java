package com.targettracker.ui;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.model.Wgs84;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.function.Consumer;

final class TargetInspectorPanel extends JPanel {
    private final JComboBox<TargetTrajectory> targetSelector = new JComboBox<>();
    private final JLabel idValue = valueLabel();
    private final JLabel latitudeValue = valueLabel();
    private final JLabel longitudeValue = valueLabel();
    private final JLabel altitudeValue = valueLabel();
    private final JLabel velocityValue = valueLabel();
    private final JLabel ecefXValue = valueLabel();
    private final JLabel ecefYValue = valueLabel();
    private final JLabel ecefZValue = valueLabel();
    private final JLabel durationValue = valueLabel();
    private boolean synchronizing;

    TargetInspectorPanel(
            ScenarioModel model,
            Runnable onOpenSensorWindow,
            Runnable onOpenImmWindow,
            Consumer<TargetTrajectory> onSelectionChanged) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(214, 220, 227)),
                BorderFactory.createEmptyBorder(18, 18, 18, 18)));
        setPreferredSize(new Dimension(250, 0));
        setBackground(Color.WHITE);

        JLabel title = new JLabel("Target telemetry");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16.0f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(12));

        targetSelector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        targetSelector.setAlignmentX(LEFT_ALIGNMENT);
        targetSelector.addActionListener(event -> {
            if (!synchronizing) {
                onSelectionChanged.accept((TargetTrajectory) targetSelector.getSelectedItem());
            }
        });
        add(targetSelector);
        add(Box.createVerticalStrut(8));
        JButton sensorButton = new JButton("Open sensor parameters…");
        sensorButton.setAlignmentX(LEFT_ALIGNMENT);
        sensorButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        sensorButton.addActionListener(event -> onOpenSensorWindow.run());
        add(sensorButton);
        add(Box.createVerticalStrut(5));
        JButton immButton = new JButton("Open IMM specifications…");
        immButton.setAlignmentX(LEFT_ALIGNMENT);
        immButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        immButton.addActionListener(event -> onOpenImmWindow.run());
        add(immButton);
        add(Box.createVerticalStrut(18));
        add(sectionSeparator());
        add(Box.createVerticalStrut(12));

        addRow("Target ID", idValue);
        addRow("Latitude", latitudeValue);
        addRow("Longitude", longitudeValue);
        addRow("Altitude", altitudeValue);
        addRow("Velocity", velocityValue);
        addRow("ECEF X", ecefXValue);
        addRow("ECEF Y", ecefYValue);
        addRow("ECEF Z", ecefZValue);
        addRow("Duration", durationValue);
        add(Box.createVerticalGlue());

        JLabel key = new JLabel("Planned path");
        key.setForeground(new Color(87, 99, 111));
        key.setAlignmentX(LEFT_ALIGNMENT);
        add(key);
        JLabel plannedNote = new JLabel("- - - thin outline");
        plannedNote.setForeground(new Color(87, 99, 111));
        plannedNote.setAlignmentX(LEFT_ALIGNMENT);
        add(plannedNote);
        add(Box.createVerticalStrut(6));
        JLabel historyNote = new JLabel("━━ bold recent history");
        historyNote.setForeground(new Color(87, 99, 111));
        historyNote.setAlignmentX(LEFT_ALIGNMENT);
        add(historyNote);
        add(Box.createVerticalStrut(6));
        JLabel measurementNote = new JLabel("× white measurement");
        measurementNote.setForeground(new Color(87, 99, 111));
        measurementNote.setAlignmentX(LEFT_ALIGNMENT);
        add(measurementNote);
        add(Box.createVerticalStrut(6));
        JLabel trackNote = new JLabel("■ IMM track mean / tail");
        trackNote.setForeground(new Color(87, 99, 111));
        trackNote.setAlignmentX(LEFT_ALIGNMENT);
        add(trackNote);
        JLabel covarianceNote = new JLabel("○ one-sigma covariance");
        covarianceNote.setForeground(new Color(87, 99, 111));
        covarianceNote.setAlignmentX(LEFT_ALIGNMENT);
        add(covarianceNote);
        JLabel deadTrackNote = new JLabel("■ grey = dead track");
        deadTrackNote.setForeground(new Color(145, 150, 156));
        deadTrackNote.setAlignmentX(LEFT_ALIGNMENT);
        add(deadTrackNote);

        synchronizing = true;
        model.targets().forEach(targetSelector::addItem);
        synchronizing = false;
    }

    void targetAdded(TargetTrajectory target) {
        targetSelector.addItem(target);
    }

    void setSelectedTarget(TargetTrajectory target) {
        synchronizing = true;
        targetSelector.setSelectedItem(target);
        synchronizing = false;
    }

    void refresh(TargetTrajectory target, ScenarioPlayback playback) {
        if (target == null) {
            idValue.setText("—");
            latitudeValue.setText("—");
            longitudeValue.setText("—");
            altitudeValue.setText("—");
            velocityValue.setText("—");
            ecefXValue.setText("—");
            ecefYValue.setText("—");
            ecefZValue.setText("—");
            durationValue.setText("—");
            return;
        }

        double time = playback.elapsedSeconds();
        EcefPoint position = playback.currentPosition(target);
        GeodeticPoint geodetic = position == null ? null : Wgs84.toGeodetic(position);
        idValue.setText(target.id());
        latitudeValue.setText(geodetic == null ? "—" : formatLatitude(geodetic.latitudeDegrees()));
        longitudeValue.setText(geodetic == null ? "—" : formatLongitude(geodetic.longitudeDegrees()));
        altitudeValue.setText(geodetic == null
                ? "%,.0f m".formatted(target.altitudeAt(time))
                : "%,.1f m".formatted(geodetic.altitudeMeters()));
        velocityValue.setText("%,.1f m/s".formatted(target.velocityAt(time)));
        ecefXValue.setText(position == null ? "—" : "%,.0f m".formatted(position.x()));
        ecefYValue.setText(position == null ? "—" : "%,.0f m".formatted(position.y()));
        ecefZValue.setText(position == null ? "—" : "%,.0f m".formatted(position.z()));
        durationValue.setText(target.isRunnable()
                ? "%.1f s".formatted(target.durationSeconds())
                : "Add a path");
    }

    private void addRow(String name, JLabel value) {
        JLabel label = new JLabel(name);
        label.setForeground(new Color(104, 116, 128));
        label.setAlignmentX(LEFT_ALIGNMENT);
        value.setAlignmentX(LEFT_ALIGNMENT);
        add(label);
        add(Box.createVerticalStrut(2));
        add(value);
        add(Box.createVerticalStrut(13));
    }

    private static JLabel valueLabel() {
        JLabel label = new JLabel("—");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 14.0f));
        return label;
    }

    private static JSeparator sectionSeparator() {
        JSeparator separator = new JSeparator();
        separator.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        separator.setAlignmentX(LEFT_ALIGNMENT);
        return separator;
    }

    private static String formatLatitude(double latitude) {
        return "%.5f° %s".formatted(Math.abs(latitude), latitude >= 0.0 ? "N" : "S");
    }

    private static String formatLongitude(double longitude) {
        return "%.5f° %s".formatted(Math.abs(longitude), longitude >= 0.0 ? "E" : "W");
    }
}
