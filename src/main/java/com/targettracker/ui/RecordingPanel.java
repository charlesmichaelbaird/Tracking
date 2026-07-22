package com.targettracker.ui;

import com.targettracker.recording.TrackCsvRecorder;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

/** Compact CSV output controls embedded in the main window header. */
final class RecordingPanel extends JPanel {
    private final Component dialogParent;
    private final TrackCsvRecorder recorder;
    private final JTextField parentFolderField;
    private final JTextField folderNameField = new JTextField("scenario-output", 36);
    private final JButton browseButton = new JButton("Browse...");
    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel outputPathLabel = new JLabel();
    private String previewScenarioName = "scenario";

    RecordingPanel(Component dialogParent, TrackCsvRecorder recorder) {
        this.dialogParent = dialogParent;
        this.recorder = recorder;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, AppTheme.current().border()),
                BorderFactory.createEmptyBorder(0, 8, 1, 8)));

        JPanel parentRow = row();
        JLabel title = new JLabel("CSV output");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        parentRow.add(title);

        parentRow.add(new JLabel("Output path:"));
        parentFolderField = new JTextField(recorder.outputParent().toString(), 48);
        parentFolderField.setToolTipText("Parent folder where Save CSVs creates the export folder");
        parentFolderField.addActionListener(event -> commitParentFolder());
        parentRow.add(parentFolderField);

        browseButton.addActionListener(event -> browseForFolder());
        parentRow.add(browseButton);

        statusLabel.setForeground(AppTheme.statusColor(AppTheme.Status.MUTED));
        parentRow.add(statusLabel);
        add(parentRow);

        JPanel folderRow = row();
        folderRow.add(new JLabel("Folder name:"));
        folderNameField.setToolTipText(
                "Name for the export subfolder; existing names receive a numeric suffix");
        folderNameField.addActionListener(event -> updateOutputPathPreview(previewScenarioName));
        folderNameField.getDocument().addDocumentListener(new SimpleDocumentListener(
                () -> updateOutputPathPreview(previewScenarioName)));
        folderRow.add(folderNameField);
        folderRow.add(new JLabel("Output folder:"));
        outputPathLabel.setForeground(AppTheme.current().mutedText());
        outputPathLabel.setPreferredSize(new Dimension(260, 18));
        folderRow.add(outputPathLabel);
        add(folderRow);

        refresh();
    }

    boolean commitOutputSettings(String scenarioName) {
        if (!commitParentFolder()) {
            return false;
        }
        String folderName = folderName(scenarioName);
        if (folderName.isBlank()) {
            JOptionPane.showMessageDialog(
                    dialogParent,
                    "Enter a folder name before saving CSV data.",
                    "Missing export folder name",
                    JOptionPane.WARNING_MESSAGE);
            folderNameField.requestFocusInWindow();
            return false;
        }
        updateOutputPathPreview(scenarioName);
        return true;
    }

    boolean commitParentFolder() {
        if (recorder.isActive()) {
            return true;
        }
        try {
            String text = parentFolderField.getText().trim();
            if (text.isEmpty()) {
                throw new InvalidPathException(text, "Choose a parent folder");
            }
            recorder.setOutputParent(Path.of(text));
            parentFolderField.setText(recorder.outputParent().toString());
            refresh();
            return true;
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(
                    dialogParent,
                    "Choose a valid parent folder.\n" + exception.getMessage(),
                    "Invalid recording folder",
                    JOptionPane.WARNING_MESSAGE);
            parentFolderField.requestFocusInWindow();
            return false;
        }
    }

    String folderName(String scenarioName) {
        String text = folderNameField.getText().trim();
        if (!text.isBlank()) {
            return text;
        }
        return scenarioName == null || scenarioName.isBlank() ? "scenario" : scenarioName;
    }

    Path outputFolder(String scenarioName) {
        return recorder.outputParent().resolve(safeFolderName(folderName(scenarioName)));
    }

    void rememberOutputFolder(Path folder) {
        if (folder == null || folder.getFileName() == null) {
            return;
        }
        Path parent = folder.toAbsolutePath().normalize().getParent();
        if (parent != null && parent.equals(recorder.outputParent())) {
            folderNameField.setText(folder.getFileName().toString());
            updateOutputPathPreview(previewScenarioName);
        }
    }

    void refresh() {
        boolean active = recorder.isActive();
        parentFolderField.setEnabled(!active);
        folderNameField.setEnabled(!active);
        browseButton.setEnabled(!active);
        updateOutputPathPreview(previewScenarioName);

        if (recorder.lastError() != null) {
            statusLabel.setForeground(AppTheme.statusColor(AppTheme.Status.DANGER));
            statusLabel.setText(recorder.lastError());
        } else if (active && recorder.runDirectory() != null) {
            statusLabel.setForeground(AppTheme.statusColor(AppTheme.Status.DANGER));
            statusLabel.setText("Saving to " + recorder.runDirectory().getFileName());
        } else if (recorder.runDirectory() != null) {
            statusLabel.setForeground(AppTheme.statusColor(AppTheme.Status.SUCCESS));
            statusLabel.setText("Last saved to " + recorder.runDirectory().getFileName());
        } else {
            statusLabel.setForeground(AppTheme.statusColor(AppTheme.Status.MUTED));
            statusLabel.setText("Ready");
        }
    }

    private void browseForFolder() {
        JFileChooser chooser = new JFileChooser(recorder.outputParent().toFile());
        chooser.setDialogTitle("Choose recording parent folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(dialogParent) == JFileChooser.APPROVE_OPTION) {
            parentFolderField.setText(chooser.getSelectedFile().toPath().toString());
            commitParentFolder();
        }
    }

    private void updateOutputPathPreview(String scenarioName) {
        String effectiveName = scenarioName == null || scenarioName.isBlank()
                ? "scenario"
                : scenarioName;
        previewScenarioName = effectiveName;
        Path outputFolder = outputFolder(effectiveName);
        outputPathLabel.setText(outputFolder.toString());
    }

    private static JPanel row() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    private static String safeFolderName(String text) {
        String safe = text == null ? "" : text
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return safe.isBlank() ? "scenario" : safe;
    }

    private record SimpleDocumentListener(Runnable onChange) implements DocumentListener {
        @Override
        public void insertUpdate(DocumentEvent event) {
            onChange.run();
        }

        @Override
        public void removeUpdate(DocumentEvent event) {
            onChange.run();
        }

        @Override
        public void changedUpdate(DocumentEvent event) {
            onChange.run();
        }
    }
}
