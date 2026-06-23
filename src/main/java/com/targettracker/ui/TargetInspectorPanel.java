package com.targettracker.ui;

import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.function.Consumer;

/** Compact target selector for the target/motion card. */
final class TargetInspectorPanel extends JPanel {
    private final JComboBox<TargetTrajectory> targetSelector = new JComboBox<>();
    private boolean synchronizing;

    TargetInspectorPanel(ScenarioModel model, Consumer<TargetTrajectory> onSelectionChanged) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        setBackground(Color.WHITE);
        setAlignmentX(LEFT_ALIGNMENT);

        JLabel title = new JLabel("Target selection");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17.0f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(4));
        JLabel note = new JLabel("Select the target whose path/profile you want to edit.");
        note.setForeground(new Color(80, 92, 104));
        note.setAlignmentX(LEFT_ALIGNMENT);
        add(note);
        add(Box.createVerticalStrut(10));

        targetSelector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        targetSelector.setAlignmentX(LEFT_ALIGNMENT);
        targetSelector.addActionListener(event -> {
            if (!synchronizing) {
                onSelectionChanged.accept((TargetTrajectory) targetSelector.getSelectedItem());
            }
        });
        add(targetSelector);

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
        // Selector-only component; retained for the surrounding refresh API.
    }
}
