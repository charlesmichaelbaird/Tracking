package com.targettracker.ui;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.util.function.Consumer;

/** Embedded controls for creating and drawing user-generated targets. */
final class TargetsPanel extends JPanel {
    private final JButton newTargetButton = fullWidthButton("New target");
    private final JComboBox<String> drawingMode =
            new JComboBox<>(new String[]{"Free-hand", "Segmented line"});
    private final JButton finishPathButton = fullWidthButton("Finish path");
    private final JButton clearPathButton = fullWidthButton("Clear path");
    private final JLabel lockLabel = new JLabel("Manual target editing enabled");

    TargetsPanel(
            Runnable onNewTarget,
            Consumer<EarthMapCanvas.DrawingMode> onDrawingModeChanged,
            Runnable onFinishPath,
            Runnable onClearPath) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        setBackground(new Color(246, 248, 251));

        JLabel title = new JLabel("Targets");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18.0f));
        title.setAlignmentX(LEFT_ALIGNMENT);
        add(title);
        add(Box.createVerticalStrut(4));
        JLabel note = new JLabel("<html>Create a target, choose how its ground path is drawn, "
                + "then finish or clear the selected path.</html>");
        note.setForeground(new Color(80, 92, 104));
        note.setAlignmentX(LEFT_ALIGNMENT);
        add(note);
        add(Box.createVerticalStrut(18));

        newTargetButton.addActionListener(event -> onNewTarget.run());
        add(newTargetButton);
        add(Box.createVerticalStrut(14));

        JLabel drawingLabel = new JLabel("Drawing type");
        drawingLabel.setForeground(new Color(61, 73, 84));
        drawingLabel.setAlignmentX(LEFT_ALIGNMENT);
        add(drawingLabel);
        add(Box.createVerticalStrut(4));
        drawingMode.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        drawingMode.setAlignmentX(LEFT_ALIGNMENT);
        drawingMode.addActionListener(event -> onDrawingModeChanged.accept(
                drawingMode.getSelectedIndex() == 0
                        ? EarthMapCanvas.DrawingMode.FREE_HAND
                        : EarthMapCanvas.DrawingMode.SEGMENTED));
        add(drawingMode);
        add(Box.createVerticalStrut(14));

        finishPathButton.addActionListener(event -> onFinishPath.run());
        clearPathButton.addActionListener(event -> onClearPath.run());
        add(finishPathButton);
        add(Box.createVerticalStrut(8));
        add(clearPathButton);
        add(Box.createVerticalStrut(18));

        lockLabel.setForeground(new Color(44, 112, 62));
        lockLabel.setAlignmentX(LEFT_ALIGNMENT);
        add(lockLabel);
        add(Box.createVerticalGlue());
    }

    void setEditingState(boolean editingLocked, boolean presetScenarioActive) {
        boolean enabled = !editingLocked;
        newTargetButton.setEnabled(enabled && !presetScenarioActive);
        drawingMode.setEnabled(enabled);
        finishPathButton.setEnabled(enabled);
        clearPathButton.setEnabled(enabled && !presetScenarioActive);
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

    private static JButton fullWidthButton(String text) {
        JButton button = new JButton(text);
        button.setAlignmentX(LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        return button;
    }
}
