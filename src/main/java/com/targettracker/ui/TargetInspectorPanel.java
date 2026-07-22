package com.targettracker.ui;

import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Consumer;

/** Compact target selector for the target/motion card. */
final class TargetInspectorPanel extends JPanel {
    private final JComboBox<TargetTrajectory> targetSelector = new JComboBox<>();
    private final JButton extrapolateAllButton = new JButton("Extrapolate All");
    private final ButtonGroup platformGroup = new ButtonGroup();
    private final JToggleButton airButton = new JToggleButton("Air");
    private final JToggleButton groundButton = new JToggleButton("Ground");
    private boolean synchronizing;

    TargetInspectorPanel(
            ScenarioModel model,
            Consumer<TargetTrajectory> onSelectionChanged,
            Consumer<TargetTrajectory.PlatformType> onPlatformChanged,
            Runnable onExtrapolateAllTargets) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        AppTheme.setRole(this, AppTheme.ROLE_CARD);
        setAlignmentX(LEFT_ALIGNMENT);

        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        titleRow.setAlignmentX(LEFT_ALIGNMENT);
        titleRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        JLabel title = new JLabel("Target selection");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17.0f));
        titleRow.add(title, BorderLayout.CENTER);
        extrapolateAllButton.setToolTipText(
                "Extend every shorter target to the scenario duration");
        extrapolateAllButton.setEnabled(false);
        extrapolateAllButton.addActionListener(event -> onExtrapolateAllTargets.run());
        titleRow.add(extrapolateAllButton, BorderLayout.EAST);
        add(titleRow);
        add(Box.createVerticalStrut(4));
        JLabel note = new JLabel("Select the target whose path/profile you want to edit.");
        note.setForeground(AppTheme.current().mutedText());
        note.setAlignmentX(LEFT_ALIGNMENT);
        add(note);
        add(Box.createVerticalStrut(10));

        targetSelector.addActionListener(event -> {
            if (!synchronizing) {
                onSelectionChanged.accept((TargetTrajectory) targetSelector.getSelectedItem());
            }
        });

        JPanel selectorRow = new JPanel(new GridLayout(1, 2, 8, 0));
        selectorRow.setOpaque(false);
        selectorRow.setAlignmentX(LEFT_ALIGNMENT);
        selectorRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        selectorRow.add(targetSelector);

        JPanel platformRow = new JPanel(new GridLayout(1, 2, 6, 0));
        platformRow.setOpaque(false);
        platformGroup.add(airButton);
        platformGroup.add(groundButton);
        airButton.setToolTipText("Preset altitude to 3000 m and velocity to 150 m/s");
        groundButton.setToolTipText("Preset altitude to 0 m and velocity to 20 m/s");
        airButton.addActionListener(event -> {
            if (!synchronizing) {
                onPlatformChanged.accept(TargetTrajectory.PlatformType.AIR);
            }
        });
        groundButton.addActionListener(event -> {
            if (!synchronizing) {
                onPlatformChanged.accept(TargetTrajectory.PlatformType.GROUND);
            }
        });
        platformRow.add(airButton);
        platformRow.add(groundButton);
        selectorRow.add(platformRow);
        add(selectorRow);

        synchronizing = true;
        model.targets().forEach(targetSelector::addItem);
        refreshPlatformButtons(selectedTarget());
        synchronizing = false;
    }

    void targetAdded(TargetTrajectory target) {
        targetSelector.addItem(target);
        refreshPlatformButtons(selectedTarget());
    }

    void replaceTargets(List<TargetTrajectory> targets, TargetTrajectory selected) {
        synchronizing = true;
        targetSelector.removeAllItems();
        targets.forEach(targetSelector::addItem);
        targetSelector.setSelectedItem(selected);
        refreshPlatformButtons(selected);
        synchronizing = false;
    }

    void setSelectedTarget(TargetTrajectory target) {
        synchronizing = true;
        targetSelector.setSelectedItem(target);
        refreshPlatformButtons(target);
        synchronizing = false;
    }

    TargetTrajectory selectedTarget() {
        return (TargetTrajectory) targetSelector.getSelectedItem();
    }

    void setExtrapolateAllEnabled(boolean enabled) {
        extrapolateAllButton.setEnabled(enabled);
    }

    void setPlatformControlsEnabled(boolean enabled) {
        boolean hasTarget = selectedTarget() != null;
        airButton.setEnabled(enabled && hasTarget);
        groundButton.setEnabled(enabled && hasTarget);
        stylePlatformButton(airButton);
        stylePlatformButton(groundButton);
    }

    void refresh(TargetTrajectory target, ScenarioPlayback playback) {
        refreshPlatformButtons(target);
    }

    private void refreshPlatformButtons(TargetTrajectory target) {
        if (target == null) {
            platformGroup.clearSelection();
            stylePlatformButton(airButton);
            stylePlatformButton(groundButton);
            return;
        }
        if (target.platformType() == TargetTrajectory.PlatformType.GROUND) {
            groundButton.setSelected(true);
        } else {
            airButton.setSelected(true);
        }
        stylePlatformButton(airButton);
        stylePlatformButton(groundButton);
    }

    private static void stylePlatformButton(JToggleButton button) {
        AppTheme.Palette palette = AppTheme.current();
        button.setBackground(button.isSelected()
                ? palette.selectionBackground()
                : palette.buttonBackground());
        button.setForeground(button.isEnabled() ? palette.buttonText() : palette.mutedText());
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(button.isSelected()
                        ? palette.selectionBackground()
                        : palette.border()),
                BorderFactory.createEmptyBorder(4, 9, 4, 9)));
    }
}
