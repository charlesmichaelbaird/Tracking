package com.targettracker.ui;

import com.targettracker.recording.TrackCsvRecorder;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Compact recording controls embedded in the main window header. */
final class RecordingPanel extends JPanel {
    private final Component dialogParent;
    private final TrackCsvRecorder recorder;
    private final Runnable onRecordingStateChanged;
    private final JTextField folderField;
    private final JButton browseButton = new JButton("Browse…");
    private final JToggleButton recordButton = new JToggleButton("Record");
    private final JLabel statusLabel = new JLabel("Off");

    RecordingPanel(
            Component dialogParent,
            TrackCsvRecorder recorder,
            Runnable onRecordingStateChanged) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 5));
        this.dialogParent = dialogParent;
        this.recorder = recorder;
        this.onRecordingStateChanged = onRecordingStateChanged;
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(232, 235, 239)),
                BorderFactory.createEmptyBorder(0, 8, 1, 8)));

        JLabel title = new JLabel("Track recording");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        add(title);

        recordButton.setIcon(new RecordDotIcon());
        recordButton.setToolTipText("Arm one-second track recording for the next pre-compute");
        recordButton.addActionListener(event -> toggleRecording());
        add(recordButton);

        add(new JLabel("Parent folder:"));
        folderField = new JTextField(recorder.outputParent().toString(), 38);
        folderField.setToolTipText(
                "Each pre-compute creates a unique <scenario-name>_yyyy-MM-dd_HH-mm-ss_SSS subfolder here");
        folderField.addActionListener(event -> commitParentFolder());
        add(folderField);

        browseButton.addActionListener(event -> browseForFolder());
        add(browseButton);

        statusLabel.setForeground(new Color(91, 103, 115));
        add(statusLabel);
        refresh();
    }

    boolean commitParentFolder() {
        if (recorder.isActive()) {
            return true;
        }
        try {
            String text = folderField.getText().trim();
            if (text.isEmpty()) {
                throw new InvalidPathException(text, "Choose a parent folder");
            }
            recorder.setOutputParent(Path.of(text));
            folderField.setText(recorder.outputParent().toString());
            refresh();
            return true;
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(
                    dialogParent,
                    "Choose a valid parent folder.\n" + exception.getMessage(),
                    "Invalid recording folder",
                    JOptionPane.WARNING_MESSAGE);
            folderField.requestFocusInWindow();
            return false;
        }
    }

    void refresh() {
        boolean active = recorder.isActive();
        recordButton.setSelected(recorder.isArmed());
        folderField.setEnabled(!active);
        browseButton.setEnabled(!active);
        recordButton.repaint();

        if (recorder.lastError() != null) {
            statusLabel.setForeground(new Color(176, 40, 40));
            statusLabel.setText(recorder.lastError());
        } else if (active && recorder.runDirectory() != null) {
            statusLabel.setForeground(new Color(196, 28, 28));
            statusLabel.setText("Recording to " + recorder.runDirectory().getFileName());
        } else if (recorder.isArmed()) {
            statusLabel.setForeground(new Color(145, 52, 52));
            statusLabel.setText("Armed for next pre-compute");
        } else {
            statusLabel.setForeground(new Color(91, 103, 115));
            statusLabel.setText("Off");
        }
    }

    private void toggleRecording() {
        if (recordButton.isSelected()) {
            if (!commitParentFolder()) {
                recordButton.setSelected(false);
                recorder.setArmed(false);
                refresh();
                return;
            }
            recorder.setArmed(true);
        } else {
            recorder.setArmed(false);
        }
        refresh();
        onRecordingStateChanged.run();
    }

    private void browseForFolder() {
        JFileChooser chooser = new JFileChooser(recorder.outputParent().toFile());
        chooser.setDialogTitle("Choose recording parent folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(dialogParent) == JFileChooser.APPROVE_OPTION) {
            folderField.setText(chooser.getSelectedFile().toPath().toString());
            commitParentFolder();
        }
    }

    private final class RecordDotIcon implements Icon {
        private static final int SIZE = 14;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = recorder.isActive()
                    ? new Color(238, 35, 35)
                    : recorder.isArmed() ? new Color(151, 54, 54) : new Color(155, 160, 166);
            if (recorder.isActive()) {
                g2.setColor(new Color(238, 35, 35, 70));
                g2.fillOval(x, y, SIZE, SIZE);
            }
            g2.setColor(fill);
            g2.fillOval(x + 2, y + 2, SIZE - 4, SIZE - 4);
            g2.setStroke(new BasicStroke(1.0f));
            g2.setColor(new Color(110, 20, 20, 120));
            g2.drawOval(x + 2, y + 2, SIZE - 4, SIZE - 4);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }
}
