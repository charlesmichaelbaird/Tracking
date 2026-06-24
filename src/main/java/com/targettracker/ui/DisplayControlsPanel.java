package com.targettracker.ui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

/** Common truth/measurement display controls used in both application modes. */
final class DisplayControlsPanel extends JPanel {
    DisplayControlsPanel(DisplayHistorySettings settings, Runnable onChanged) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 5));
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(232, 235, 239)),
                BorderFactory.createEmptyBorder(0, 8, 1, 8)));

        JLabel title = new JLabel("World-view layers");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        add(title);

        JToggleButton gridButton = new JToggleButton("Grid", true);
        gridButton.setToolTipText("Show or hide the latitude/longitude grid lines");
        gridButton.addActionListener(event -> {
            settings.setGridVisible(gridButton.isSelected());
            onChanged.run();
        });
        add(gridButton);

        JToggleButton blackoutsButton = new JToggleButton("Blackouts", true);
        blackoutsButton.setToolTipText("Show or hide sensor blackout regions");
        blackoutsButton.addActionListener(event -> {
            settings.setBlackoutRegionsVisible(blackoutsButton.isSelected());
            onChanged.run();
        });
        add(blackoutsButton);

        JToggleButton truthButton = new JToggleButton("Ground truth", true);
        truthButton.setToolTipText("Show or hide ground-truth paths and target markers");
        truthButton.addActionListener(event -> {
            settings.setGroundTruthVisible(truthButton.isSelected());
            onChanged.run();
        });
        add(truthButton);
        add(new JLabel("Truth history:"));
        add(historySlider(settings.groundTruthHistoryFraction(), fraction -> {
            settings.setGroundTruthHistoryFraction(fraction);
            onChanged.run();
        }, "Ground-truth history: left is none, right is all"));

        JToggleButton measurementsButton = new JToggleButton("Measurements", true);
        measurementsButton.setToolTipText("Show or hide sensor measurement markers");
        measurementsButton.addActionListener(event -> {
            settings.setMeasurementsVisible(measurementsButton.isSelected());
            onChanged.run();
        });
        add(measurementsButton);
        add(new JLabel("Measurement history:"));
        add(historySlider(settings.measurementHistoryFraction(), fraction -> {
            settings.setMeasurementHistoryFraction(fraction);
            onChanged.run();
        }, "Measurement history: left is none, right is all"));
    }

    private static JSlider historySlider(
            double initialFraction,
            java.util.function.DoubleConsumer onChanged,
            String tooltip) {
        JSlider slider = new JSlider(0, 100, (int) Math.round(initialFraction * 100.0));
        slider.setPreferredSize(new Dimension(165, 28));
        slider.setToolTipText(tooltip);
        slider.addChangeListener(event -> onChanged.accept(slider.getValue() / 100.0));
        return slider;
    }
}
