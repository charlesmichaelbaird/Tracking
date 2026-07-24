package com.targettracker.ui;

import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;

import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Combined target controls and editable velocity/altitude profiles. */
final class MotionTelemetryPanel extends JPanel {
    private static final int CONTROL_BUTTON_HEIGHT = 32;

    private final ScenarioModel model;
    private final TargetInspectorPanel inspector;
    private final JButton newTargetButton = fullWidthButton("New target");
    private final JButton copyTargetButton = fullWidthButton("Copy target");
    private final JButton removeTargetButton = fullWidthButton("Remove target");
    private final JComboBox<String> drawingMode =
            new JComboBox<>(new String[]{"Free-hand", "Segmented line", "Circle", "Racetrack"});
    private final JButton finishPathButton = fullWidthButton("Finish path");
    private final JToggleButton editPathButton = fullWidthToggleButton("Edit path", true);
    private final JButton removeLastSegmentButton = fullWidthButton("Remove last");
    private final JButton clearPathButton = fullWidthButton("Clear path");
    private final JButton smoothPathButton = fullWidthButton("Smooth");
    private final JButton undoSmoothButton = fullWidthButton("Undo smooth");
    private final JToggleButton extrapolatePathButton = fullWidthToggleButton("Extrapolate");
    private final JLabel lockLabel = new JLabel("Manual target editing enabled");
    private final JLabel profileTargetLabel = new JLabel();
    private final ProfileEditor velocityEditor;
    private final ProfileEditor altitudeEditor;
    private final BooleanSupplier editingLocked;
    private boolean editingEnabled = true;
    private boolean presetScenarioActive;
    private boolean canRemoveLastSegment;

    MotionTelemetryPanel(
            ScenarioModel model,
            Supplier<TargetTrajectory> selectedTarget,
            ScenarioPlayback playback,
            BooleanSupplier editingLocked,
            Runnable onProfileChanged,
            Consumer<TargetTrajectory> onSelectionChanged,
            Consumer<TargetTrajectory.PlatformType> onPlatformChanged,
            Runnable onExtrapolateAllTargets,
            Runnable onNewTarget,
            Runnable onCopyTarget,
            Consumer<EarthMapCanvas.DrawingMode> onDrawingModeChanged,
            Runnable onFinishPath,
            Consumer<Boolean> onPathEditChanged,
            Runnable onRemoveLastSegment,
            Runnable onClearPath,
            Runnable onSmoothPath,
            Runnable onUndoSmoothPath,
            Runnable onToggleExtrapolatePath,
            Runnable onRemoveTarget,
            Consumer<Double> onProfileCursorChanged) {
        this.model = model;
        this.editingLocked = editingLocked;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        AppTheme.setRole(this, AppTheme.ROLE_APP);

        inspector = new TargetInspectorPanel(
                model, onSelectionChanged, onPlatformChanged, onExtrapolateAllTargets);
        inspector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 124));
        add(inspector);
        add(Box.createVerticalStrut(8));
        add(createTargetControls(
                onNewTarget,
                onCopyTarget,
                onDrawingModeChanged,
                onFinishPath,
                onPathEditChanged,
                onRemoveLastSegment,
                onClearPath,
                onSmoothPath,
                onUndoSmoothPath,
                onToggleExtrapolatePath,
                onRemoveTarget));
        add(Box.createVerticalStrut(8));

        JPanel profileHeader = new JPanel();
        profileHeader.setLayout(new BoxLayout(profileHeader, BoxLayout.Y_AXIS));
        AppTheme.setRole(profileHeader, AppTheme.ROLE_APP);
        profileHeader.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        profileHeader.setAlignmentX(LEFT_ALIGNMENT);
        JLabel title = new JLabel("Motion profiles");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 17.0f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        profileTargetLabel.setForeground(AppTheme.current().mutedText());
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
            Runnable onCopyTarget,
            Consumer<EarthMapCanvas.DrawingMode> onDrawingModeChanged,
            Runnable onFinishPath,
            Consumer<Boolean> onPathEditChanged,
            Runnable onRemoveLastSegment,
            Runnable onClearPath,
            Runnable onSmoothPath,
            Runnable onUndoSmoothPath,
            Runnable onToggleExtrapolatePath,
            Runnable onRemoveTarget) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        AppTheme.setRole(panel, AppTheme.ROLE_CARD);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 12, 0, 12),
                AppTheme.lineBorder()));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 246));

        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        panel.add(inner);

        JPanel buttonGrid = new JPanel(new GridLayout(1, 3, 8, 0));
        buttonGrid.setOpaque(false);
        buttonGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, CONTROL_BUTTON_HEIGHT));
        newTargetButton.addActionListener(event -> onNewTarget.run());
        copyTargetButton.setToolTipText(
                "Duplicate the selected target (or use Ctrl+C, then Ctrl+V)");
        copyTargetButton.addActionListener(event -> onCopyTarget.run());
        removeTargetButton.addActionListener(event -> onRemoveTarget.run());
        buttonGrid.add(newTargetButton);
        buttonGrid.add(copyTargetButton);
        buttonGrid.add(removeTargetButton);
        inner.add(buttonGrid);
        inner.add(Box.createVerticalStrut(10));

        JLabel drawingLabel = new JLabel("Drawing type");
        drawingLabel.setForeground(AppTheme.current().text());
        drawingLabel.setAlignmentX(LEFT_ALIGNMENT);
        inner.add(drawingLabel);
        inner.add(Box.createVerticalStrut(4));
        drawingMode.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        drawingMode.setAlignmentX(LEFT_ALIGNMENT);
        drawingMode.addActionListener(event -> {
            onDrawingModeChanged.accept(switch (drawingMode.getSelectedIndex()) {
                case 1 -> EarthMapCanvas.DrawingMode.SEGMENTED;
                case 2 -> EarthMapCanvas.DrawingMode.CIRCLE;
                case 3 -> EarthMapCanvas.DrawingMode.RACETRACK;
                default -> EarthMapCanvas.DrawingMode.FREE_HAND;
            });
        });

        JPanel drawingRow = new JPanel(new GridLayout(1, 2, 8, 0));
        drawingRow.setOpaque(false);
        drawingRow.setAlignmentX(LEFT_ALIGNMENT);
        drawingRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, CONTROL_BUTTON_HEIGHT));
        finishPathButton.addActionListener(event -> onFinishPath.run());
        drawingRow.add(drawingMode);
        drawingRow.add(finishPathButton);
        inner.add(drawingRow);
        inner.add(Box.createVerticalStrut(10));

        JPanel pathGrid = new JPanel(new GridLayout(1, 3, 8, 0));
        pathGrid.setOpaque(false);
        pathGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, CONTROL_BUTTON_HEIGHT));
        editPathButton.setToolTipText(
                "Continue the selected free-hand or segmented path when drawing again");
        editPathButton.addActionListener(event ->
                setPathEditSelected(editPathButton.isSelected(), onPathEditChanged));
        removeLastSegmentButton.setToolTipText(
                "Remove the most recent free-hand stroke or segmented line segment");
        removeLastSegmentButton.addActionListener(event -> onRemoveLastSegment.run());
        clearPathButton.addActionListener(event -> onClearPath.run());
        pathGrid.add(editPathButton);
        pathGrid.add(removeLastSegmentButton);
        pathGrid.add(clearPathButton);
        inner.add(pathGrid);
        inner.add(Box.createVerticalStrut(10));

        JPanel editGrid = new JPanel(new GridLayout(1, 2, 8, 0));
        editGrid.setOpaque(false);
        editGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, CONTROL_BUTTON_HEIGHT));
        smoothPathButton.setToolTipText("Smooth the selected target path");
        undoSmoothButton.setToolTipText("Undo the last smoothing pass for the selected target");
        smoothPathButton.addActionListener(event -> onSmoothPath.run());
        undoSmoothButton.addActionListener(event -> onUndoSmoothPath.run());
        editGrid.add(smoothPathButton);
        editGrid.add(undoSmoothButton);
        inner.add(editGrid);
        inner.add(Box.createVerticalStrut(10));

        extrapolatePathButton.setToolTipText(
                "Extend or restore the selected path to match the scenario length");
        extrapolatePathButton.addActionListener(event -> onToggleExtrapolatePath.run());
        JPanel extrapolateGrid = new JPanel(new GridLayout(1, 2, 8, 0));
        extrapolateGrid.setOpaque(false);
        extrapolateGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, CONTROL_BUTTON_HEIGHT));
        extrapolateGrid.add(extrapolatePathButton);
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        extrapolateGrid.add(spacer);
        inner.add(extrapolateGrid);
        inner.add(Box.createVerticalStrut(10));

        lockLabel.setForeground(AppTheme.statusColor(AppTheme.Status.SUCCESS));
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
        editingEnabled = !editingLocked;
        this.presetScenarioActive = presetScenarioActive;
        newTargetButton.setEnabled(editingEnabled && !presetScenarioActive);
        copyTargetButton.setEnabled(editingEnabled && !presetScenarioActive && canCopySelectedTarget());
        removeTargetButton.setEnabled(editingEnabled && !presetScenarioActive);
        drawingMode.setEnabled(editingEnabled);
        finishPathButton.setEnabled(editingEnabled);
        clearPathButton.setEnabled(editingEnabled && !presetScenarioActive);
        smoothPathButton.setEnabled(editingEnabled && !presetScenarioActive);
        undoSmoothButton.setEnabled(editingEnabled && !presetScenarioActive
                && selectedTarget() != null
                && selectedTarget().canUndoSmoothing());
        inspector.setPlatformControlsEnabled(editingEnabled && !presetScenarioActive);
        refreshPathActionControls();
        refreshExtrapolateControls(editingEnabled && !presetScenarioActive);
        if (presetScenarioActive) {
            lockLabel.setText("Target structure locked by preset scenario");
            lockLabel.setForeground(AppTheme.statusColor(AppTheme.Status.WARNING));
        } else if (editingLocked) {
            lockLabel.setText("Target editing locked during scenario activity");
            lockLabel.setForeground(AppTheme.statusColor(AppTheme.Status.WARNING));
        } else {
            lockLabel.setText("Manual target editing enabled");
            lockLabel.setForeground(AppTheme.statusColor(AppTheme.Status.SUCCESS));
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
        copyTargetButton.setEnabled(!editingLocked.getAsBoolean()
                && canCopySelectedTarget());
        inspector.setPlatformControlsEnabled(!editingLocked.getAsBoolean()
                && target != null);
        refreshPathActionControls();
        refreshExtrapolateControls(!editingLocked.getAsBoolean());
    }

    void setPathEditSelected(boolean selected) {
        setPathEditSelected(selected, null);
    }

    void setRemoveLastSegmentAvailable(boolean available) {
        canRemoveLastSegment = available;
        refreshPathActionControls();
    }

    private boolean canCopySelectedTarget() {
        TargetTrajectory target = selectedTarget();
        return target != null && target.path().size() >= 2;
    }

    private void refreshExtrapolateControls(boolean editingEnabled) {
        TargetTrajectory target = selectedTarget();
        boolean extrapolated = target != null && target.extrapolatedToScenarioLength();
        boolean canExtrapolate = target != null
                && target.canExtrapolateTo(model.durationSeconds());
        extrapolatePathButton.setSelected(extrapolated);
        extrapolatePathButton.setText(extrapolated ? "Remove extrapolation" : "Extrapolate");
        extrapolatePathButton.setEnabled(editingEnabled && target != null
                && (extrapolated || canExtrapolate));
        inspector.setExtrapolateAllEnabled(editingEnabled
                && model.canExtrapolateTargetsToScenarioDuration());
    }

    private static JPanel wrapChart(ProfileEditor editor) {
        JPanel wrapper = new JPanel(new BorderLayout());
        AppTheme.setRole(wrapper, AppTheme.ROLE_CARD);
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 12, 0, 12),
                AppTheme.lineBorder()));
        wrapper.setAlignmentX(LEFT_ALIGNMENT);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 230));
        wrapper.setPreferredSize(new Dimension(400, 230));
        wrapper.add(editor, BorderLayout.CENTER);
        return wrapper;
    }

    private static JButton fullWidthButton(String text) {
        JButton button = new JButton(text);
        sizeControlButton(button);
        return button;
    }

    private void setPathEditSelected(
            boolean selected,
            Consumer<Boolean> onPathEditChanged) {
        if (editPathButton.isSelected() != selected) {
            editPathButton.setSelected(selected);
        }
        styleEditPathButton();
        if (onPathEditChanged != null) {
            onPathEditChanged.accept(selected);
        }
    }

    private void refreshPathActionControls() {
        boolean pathEditingAvailable = editingEnabled && !presetScenarioActive;
        editPathButton.setEnabled(pathEditingAvailable);
        removeLastSegmentButton.setEnabled(pathEditingAvailable && canRemoveLastSegment);
        styleEditPathButton();
    }

    private void styleEditPathButton() {
        AppTheme.Palette palette = AppTheme.current();
        boolean selected = editPathButton.isSelected();
        editPathButton.setBackground(selected
                ? palette.selectionBackground()
                : palette.buttonBackground());
        editPathButton.setForeground(AppTheme.buttonTextColor(editPathButton.isEnabled()));
        editPathButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected
                        ? palette.selectionBackground()
                        : palette.border()),
                BorderFactory.createEmptyBorder(4, 9, 4, 9)));
        editPathButton.repaint();
    }

    private static JToggleButton fullWidthToggleButton(String text) {
        return fullWidthToggleButton(text, false);
    }

    private static JToggleButton fullWidthToggleButton(String text, boolean selected) {
        JToggleButton button = new JToggleButton(text, selected);
        sizeControlButton(button);
        return button;
    }

    private static void sizeControlButton(AbstractButton button) {
        Dimension preferred = button.getPreferredSize();
        button.setPreferredSize(new Dimension(preferred.width, CONTROL_BUTTON_HEIGHT));
        button.setMinimumSize(new Dimension(0, CONTROL_BUTTON_HEIGHT));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, CONTROL_BUTTON_HEIGHT));
        button.setAlignmentX(LEFT_ALIGNMENT);
    }

}
