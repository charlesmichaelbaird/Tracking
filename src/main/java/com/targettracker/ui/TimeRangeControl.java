package com.targettracker.ui;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import java.awt.Color;
import java.awt.Dimension;

/** Two-handle-like time window composed from linked minimum and maximum sliders. */
final class TimeRangeControl extends JPanel {
    private final JSlider minimumSlider;
    private final JSlider maximumSlider;
    private final JLabel summary = new JLabel();
    private boolean synchronizing;

    TimeRangeControl(String title, int maximumSeconds, int minimumValue, int maximumValue) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setAlignmentX(LEFT_ALIGNMENT);
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));

        JLabel label = new JLabel(title);
        label.setAlignmentX(LEFT_ALIGNMENT);
        add(label);
        add(Box.createVerticalStrut(2));
        int rangeMaximum = Math.max(1, maximumSeconds);
        minimumSlider = new JSlider(0, rangeMaximum, Math.min(minimumValue, rangeMaximum));
        maximumSlider = new JSlider(
                0, rangeMaximum, Math.min(Math.max(maximumValue, minimumValue), rangeMaximum));
        configure(minimumSlider, "Minimum allowed time");
        configure(maximumSlider, "Maximum allowed time");
        add(minimumSlider);
        add(maximumSlider);
        summary.setForeground(new Color(75, 87, 99));
        summary.setAlignmentX(LEFT_ALIGNMENT);
        add(summary);

        minimumSlider.addChangeListener(event -> {
            if (!synchronizing && minimumSlider.getValue() > maximumSlider.getValue()) {
                synchronizing = true;
                maximumSlider.setValue(minimumSlider.getValue());
                synchronizing = false;
            }
            updateSummary();
        });
        maximumSlider.addChangeListener(event -> {
            if (!synchronizing && maximumSlider.getValue() < minimumSlider.getValue()) {
                synchronizing = true;
                minimumSlider.setValue(maximumSlider.getValue());
                synchronizing = false;
            }
            updateSummary();
        });
        updateSummary();
    }

    double minimumSeconds() {
        return minimumSlider.getValue();
    }

    double maximumSeconds() {
        return maximumSlider.getValue();
    }

    private void updateSummary() {
        summary.setText("Window  " + format(minimumSlider.getValue())
                + "  to  " + format(maximumSlider.getValue()));
    }

    private static void configure(JSlider slider, String tooltip) {
        slider.setAlignmentX(LEFT_ALIGNMENT);
        slider.setPreferredSize(new Dimension(360, 28));
        slider.setMaximumSize(new Dimension(380, 34));
        slider.setToolTipText(tooltip);
    }

    private static String format(int seconds) {
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
