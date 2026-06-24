package com.targettracker.ui;

import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Combined target controls and editable velocity/altitude profiles. */
final class MotionTelemetryPanel extends JPanel {
    private final TargetInspectorPanel inspector;
    private final JButton newTargetButton = fullWidthButton("New target");
    private final JButton removeTargetButton = fullWidthButton("Remove target");
    private final JComboBox<String> drawingMode =
            new JComboBox<>(new String[]{"Free-hand", "Segmented line", "Circle", "Racetrack"});
    private final JButton finishPathButton = fullWidthButton("Finish path");
    private final JButton clearPathButton = fullWidthButton("Clear path");
    private final JButton smoothPathButton = fullWidthButton("Smooth");
    private final JButton undoSmoothButton = fullWidthButton("Undo smooth");
    private final JLabel lockLabel = new JLabel("Manual target editing enabled");
    private final JLabel profileTargetLabel = new JLabel();
    private final ProfileEditor velocityEditor;
    private final ProfileEditor altitudeEditor;
    private final BooleanSupplier editingLocked;

    MotionTelemetryPanel(
            ScenarioModel model,
            Supplier<TargetTrajectory> selectedTarget,
            ScenarioPlayback playback,
            BooleanSupplier editingLocked,
            Runnable onProfileChanged,
            Consumer<TargetTrajectory> onSelectionChanged,
            Runnable onNewTarget,
            Consumer<EarthMapCanvas.DrawingMode> onDrawingModeChanged,
            Runnable onFinishPath,
            Runnable onClearPath,
            Runnable onSmoothPath,
            Runnable onUndoSmoothPath,
            Runnable onRemoveTarget,
            Consumer<Double> onProfileCursorChanged) {
        this.editingLocked = editingLocked;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(246, 248, 251));

        inspector = new TargetInspectorPanel(model, onSelectionChanged);
        inspector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 116));
        add(inspector);
        add(Box.createVerticalStrut(8));
        add(createTargetControls(
                onNewTarget,
                onDrawingModeChanged,
                onFinishPath,
                onClearPath,
                onSmoothPath,
                onUndoSmoothPath,
                onRemoveTarget));
        add(Box.createVerticalStrut(8));

        JPanel profileHeader = new JPanel();
        profileHeader.setLayout(new BoxLayout(profileHeader, BoxLayout.Y_AXIS));
        profileHeader.setBackground(new Color(246, 248, 251));
        profileHeader.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        profileHeader.setAlignmentX(LEFT_ALIGNMENT);
        JLabel title = new JLabel("Motion profiles");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17.0f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        profileTargetLabel.setForeground(new Color(85, 97, 108));
        profileTargetLabel.setAlignmentX(LEFT_ALIGNMENT);
        profileHeader.add(title);
        profileHeader.add(Box.createVerticalStrut(3));
        profileHeader.add(profileTargetLabel);
        profileHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        add(profileHeader);

        velocityEditor = new ProfileEditor(
                "Velocity magnitude", "m/s", selectedTarget,
                TargetTrajectory::velocityProfile, playback, editingLocked,
                onProfileChanged, onProfileCursorChanged);
        altitudeEditor = new ProfileEditor(
                "WGS-84 ellipsoidal altitude", "m", selectedTarget,
                TargetTrajectory::altitudeProfile, playback, editingLocked,
                onProfileChanged, onProfileCursorChanged);
        add(wrapChart(velocityEditor));
        add(Box.createVerticalStrut(10));
        add(wrapChart(altitudeEditor));
    }

    private JPanel createTargetControls(
            Runnable onNewTarget,
            Consumer<EarthMapCanvas.DrawingMode> onDrawingModeChanged,
            Runnable onFinishPath,
            Runnable onClearPath,
            Runnable onSmoothPath,
            Runnable onUndoSmoothPath,
            Runnable onRemoveTarget) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 12, 0, 12),
                BorderFactory.createLineBorder(new Color(214, 220, 227))));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 246));

        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(inner);

        JPanel buttonGrid = new JPanel(new GridLayout(1, 2, 8, 0));
        buttonGrid.setOpaque(false);
        buttonGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        newTargetButton.addActionListener(event -> onNewTarget.run());
        removeTargetButton.addActionListener(event -> onRemoveTarget.run());
        buttonGrid.add(newTargetButton);
        buttonGrid.add(removeTargetButton);
        inner.add(buttonGrid);
        inner.add(Box.createVerticalStrut(10));

        JLabel drawingLabel = new JLabel("Drawing type");
        drawingLabel.setForeground(new Color(61, 73, 84));
        drawingLabel.setAlignmentX(LEFT_ALIGNMENT);
        inner.add(drawingLabel);
        inner.add(Box.createVerticalStrut(4));
        drawingMode.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        drawingMode.setAlignmentX(LEFT_ALIGNMENT);
        drawingMode.addActionListener(event -> onDrawingModeChanged.accept(switch (drawingMode.getSelectedIndex()) {
            case 1 -> EarthMapCanvas.DrawingMode.SEGMENTED;
            case 2 -> EarthMapCanvas.DrawingMode.CIRCLE;
            case 3 -> EarthMapCanvas.DrawingMode.RACETRACK;
            default -> EarthMapCanvas.DrawingMode.FREE_HAND;
        }));
        inner.add(drawingMode);
        inner.add(Box.createVerticalStrut(10));

        JPanel pathGrid = new JPanel(new GridLayout(1, 2, 8, 0));
        pathGrid.setOpaque(false);
        pathGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        finishPathButton.addActionListener(event -> onFinishPath.run());
        clearPathButton.addActionListener(event -> onClearPath.run());
        pathGrid.add(finishPathButton);
        pathGrid.add(clearPathButton);
        inner.add(pathGrid);
        inner.add(Box.createVerticalStrut(10));

        JPanel editGrid = new JPanel(new GridLayout(1, 2, 8, 0));
        editGrid.setOpaque(false);
        editGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        smoothPathButton.setToolTipText("Smooth the selected target path");
        undoSmoothButton.setToolTipText("Undo the last smoothing pass for the selected target");
        smoothPathButton.addActionListener(event -> onSmoothPath.run());
        undoSmoothButton.addActionListener(event -> onUndoSmoothPath.run());
        editGrid.add(smoothPathButton);
        editGrid.add(undoSmoothButton);
        inner.add(editGrid);
        inner.add(Box.createVerticalStrut(10));

        lockLabel.setForeground(new Color(44, 112, 62));
        lockLabel.setAlignmentX(LEFT_ALIGNMENT);
        inner.add(lockLabel);
        return panel;
    }

    void targetAdded(TargetTrajectory target) {
        inspector.targetAdded(target);
    }

    void replaceTargets(List<TargetTrajectory> targets, TargetTrajectory selected) {
        inspector.replaceTargets(targets, selected);
    }

    void setSelectedTarget(TargetTrajectory target) {
        inspector.setSelectedTarget(target);
    }

    TargetTrajectory selectedTarget() {
        return inspector.selectedTarget();
    }

    void setEditingState(boolean editingLocked, boolean presetScenarioActive) {
        boolean enabled = !editingLocked;
        newTargetButton.setEnabled(enabled && !presetScenarioActive);
        removeTargetButton.setEnabled(enabled && !presetScenarioActive);
        drawingMode.setEnabled(enabled);
        finishPathButton.setEnabled(enabled);
        clearPathButton.setEnabled(enabled && !presetScenarioActive);
        smoothPathButton.setEnabled(enabled && !presetScenarioActive);
        undoSmoothButton.setEnabled(enabled && !presetScenarioActive
                && selectedTarget() != null
                && selectedTarget().canUndoSmoothing());
        if (presetScenarioActive) {
            lockLabel.setText("Target structure locked by preset scenario");
            lockLabel.setForeground(new Color(132, 74, 17));
        } else if (editingLocked) {
            lockLabel.setText("Target editing locked during scenario activity");
            lockLabel.setForeground(new Color(132, 74, 17));
        } else {
            lockLabel.setText("Manual target editing enabled");
            lockLabel.setForeground(new Color(44, 112, 62));
        }
    }

    void refresh(TargetTrajectory target, ScenarioPlayback playback) {
        inspector.refresh(target, playback);
        profileTargetLabel.setText(target == null
                ? "No target selected"
                : editingLocked.getAsBoolean()
                ? "%s • profile locked by scenario mode".formatted(target.id())
                : "Editing %s • drag directly on either chart".formatted(target.id()));
        velocityEditor.repaint();
        altitudeEditor.repaint();
        undoSmoothButton.setEnabled(!editingLocked.getAsBoolean()
                && target != null
                && target.canUndoSmoothing());
    }

    private static JPanel wrapChart(ProfileEditor editor) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Color.WHITE);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 12, 0, 12),
                BorderFactory.createLineBorder(new Color(214, 220, 227))));
        wrapper.setAlignmentX(LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
        wrapper.setPreferredSize(new Dimension(400, 230));
        wrapper.add(editor, BorderLayout.CENTER);
        return wrapper;
    }

    private static JButton fullWidthButton(String text) {
        JButton button = new JButton(text);
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        return button;
    }
}
