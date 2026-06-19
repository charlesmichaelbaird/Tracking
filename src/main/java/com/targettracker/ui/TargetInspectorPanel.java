package com.targettracker.ui;

import com.targettracker.model.EnuPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;

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
import java.util.function.Consumer;

final class TargetInspectorPanel extends JPanel {
    private final JComboBox<TargetTrajectory> targetSelector = new JComboBox<>();
    private final JLabel idValue = valueLabel();
    private final JLabel eastValue = valueLabel();
    private final JLabel northValue = valueLabel();
    private final JLabel altitudeValue = valueLabel();
    private final JLabel velocityValue = valueLabel();
    private final JLabel durationValue = valueLabel();
    private boolean synchronizing;

    TargetInspectorPanel(ScenarioModel model, Consumer<TargetTrajectory> onSelectionChanged) {
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
        add(Box.createVerticalStrut(18));
        add(sectionSeparator());
        add(Box.createVerticalStrut(12));

        addRow("Target ID", idValue);
        addRow("East", eastValue);
        addRow("North", northValue);
        addRow("Altitude", altitudeValue);
        addRow("Velocity", velocityValue);
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
            eastValue.setText("—");
            northValue.setText("—");
            altitudeValue.setText("—");
            velocityValue.setText("—");
            durationValue.setText("—");
            return;
        }

        double time = playback.elapsedSeconds();
        EnuPoint position = playback.currentPosition(target);
        idValue.setText(target.id());
        eastValue.setText(position == null ? "—" : "%,.0f m".formatted(position.east()));
        northValue.setText(position == null ? "—" : "%,.0f m".formatted(position.north()));
        altitudeValue.setText("%,.0f m".formatted(target.altitudeAt(time)));
        velocityValue.setText("%,.1f m/s".formatted(target.velocityAt(time)));
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
}
