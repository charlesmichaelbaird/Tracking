package com.targettracker;

import com.targettracker.ui.TrackerFrame;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // The cross-platform Swing look and feel remains a safe fallback.
            }

            TrackerFrame frame = new TrackerFrame();
            frame.setVisible(true);
        });
    }
}
