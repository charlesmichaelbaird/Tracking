package com.targettracker.ui;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.model.Wgs84;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;

/** Compact target selector and live target telemetry for the motion card. */
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

    TargetInspectorPanel(ScenarioModel model, Consumer<TargetTrajectory> onSelectionChanged) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        setBackground(Color.WHITE);
        setAlignmentX(LEFT_ALIGNMENT);

        JLabel title = new JLabel("Target telemetry");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17.0f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(10));

        targetSelector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        targetSelector.setAlignmentX(LEFT_ALIGNMENT);
        targetSelector.addActionListener(event -> {
            if (!synchronizing) {
                onSelectionChanged.accept((TargetTrajectory) targetSelector.getSelectedItem());
            }
        });
        add(targetSelector);
        add(Box.createVerticalStrut(14));
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
        add(Box.createVerticalStrut(4));

        JLabel legend = new JLabel("<html><span style='color:#57636f'>"
                + "Thin line: planned path<br>Bold line: target/track history<br>"
                + "×: measurement &nbsp; ■: IMM mean &nbsp; ○: covariance<br>"
                + "Grey track: dead track</span></html>");
        legend.setAlignmentX(LEFT_ALIGNMENT);
        add(legend);

        synchronizing = true;
        model.targets().forEach(targetSelector::addItem);
        synchronizing = false;
    }

    void targetAdded(TargetTrajectory target) {
        targetSelector.addItem(target);
    }

    void replaceTargets(List<TargetTrajectory> targets, TargetTrajectory selected) {
        synchronizing = true;
        targetSelector.removeAllItems();
        targets.forEach(targetSelector::addItem);
        targetSelector.setSelectedItem(selected);
        synchronizing = false;
    }

    void setSelectedTarget(TargetTrajectory target) {
        synchronizing = true;
        targetSelector.setSelectedItem(target);
        synchronizing = false;
    }

    TargetTrajectory selectedTarget() {
        return (TargetTrajectory) targetSelector.getSelectedItem();
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
        add(Box.createVerticalStrut(1));
        add(value);
        add(Box.createVerticalStrut(9));
    }

    private static JLabel valueLabel() {
        JLabel label = new JLabel("—");
        label.setFont(label.getFont().deriveFont(Font.BOLD, 13.0f));
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
