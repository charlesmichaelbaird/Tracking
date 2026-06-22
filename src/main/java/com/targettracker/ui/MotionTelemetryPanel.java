package com.targettracker.ui;

import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Combined target telemetry and editable velocity/altitude profiles. */
final class MotionTelemetryPanel extends JPanel {
    private final TargetInspectorPanel inspector;
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
            Consumer<TargetTrajectory> onSelectionChanged) {
        this.editingLocked = editingLocked;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(246, 248, 251));

        inspector = new TargetInspectorPanel(model, onSelectionChanged);
        inspector.setMaximumSize(new Dimension(Integer.MAX_VALUE, 585));
        add(inspector);
        add(Box.createVerticalStrut(10));

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
        profileHeader.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
        add(profileHeader);

        velocityEditor = new ProfileEditor(
                "Velocity magnitude", "m/s", selectedTarget,
                TargetTrajectory::velocityProfile, playback, editingLocked, onProfileChanged);
        altitudeEditor = new ProfileEditor(
                "WGS-84 ellipsoidal altitude", "m", selectedTarget,
                TargetTrajectory::altitudeProfile, playback, editingLocked, onProfileChanged);
        add(wrapChart(velocityEditor));
        add(Box.createVerticalStrut(10));
        add(wrapChart(altitudeEditor));
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

    void refresh(TargetTrajectory target, ScenarioPlayback playback) {
        inspector.refresh(target, playback);
        profileTargetLabel.setText(target == null
                ? "No target selected"
                : editingLocked.getAsBoolean()
                ? "%s • profile locked by scenario mode".formatted(target.id())
                : "Editing %s • drag directly on either chart".formatted(target.id()));
        velocityEditor.repaint();
        altitudeEditor.repaint();
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
}
