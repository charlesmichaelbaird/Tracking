package com.targettracker.ui;

import com.targettracker.model.TargetTrajectory;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

final class ProfileWindow extends JDialog {
    private final JLabel targetLabel = new JLabel();
    private final ProfileEditor velocityEditor;
    private final ProfileEditor altitudeEditor;

    ProfileWindow(
            Frame owner,
            Supplier<TargetTrajectory> selectedTarget,
            ScenarioPlayback playback,
            BooleanSupplier editingLocked,
            Runnable onProfileChanged) {
        super(owner, "Target velocity and altitude profiles", false);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        setMinimumSize(new Dimension(560, 600));
        setSize(610, 660);

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        content.setBackground(new Color(243, 246, 249));
        setContentPane(content);

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Motion profiles");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18.0f));
        targetLabel.setForeground(new Color(85, 97, 108));
        header.add(title);
        header.add(targetLabel);
        content.add(header, BorderLayout.NORTH);

        velocityEditor = new ProfileEditor(
                "Velocity magnitude", "m/s", selectedTarget,
                TargetTrajectory::velocityProfile, playback, editingLocked, onProfileChanged);
        altitudeEditor = new ProfileEditor(
                "Altitude (Up)", "m", selectedTarget,
                TargetTrajectory::altitudeProfile, playback, editingLocked, onProfileChanged);

        JPanel charts = new JPanel(new GridLayout(2, 1, 0, 12));
        charts.setOpaque(false);
        charts.add(wrapChart(velocityEditor));
        charts.add(wrapChart(altitudeEditor));
        content.add(charts, BorderLayout.CENTER);
    }

    void refresh(TargetTrajectory target) {
        targetLabel.setText(target == null
                ? "No target selected"
                : "Editing %s • drag directly on either chart".formatted(target.id()));
        velocityEditor.repaint();
        altitudeEditor.repaint();
    }

    private static JPanel wrapChart(ProfileEditor editor) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(Color.WHITE);
        wrapper.setBorder(BorderFactory.createLineBorder(new Color(214, 220, 227)));
        wrapper.add(editor, BorderLayout.CENTER);
        return wrapper;
    }
}
