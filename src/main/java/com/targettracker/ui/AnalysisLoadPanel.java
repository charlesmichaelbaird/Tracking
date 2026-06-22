package com.targettracker.ui;

import com.targettracker.recording.TrackCsvRecorder;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Header controls for selecting and loading a previously recorded run. */
final class AnalysisLoadPanel extends JPanel {
    interface Listener {
        void loadScenario(Path scenarioFolder);
    }

    private final Component dialogParent;
    private final Listener listener;
    private final JTextField parentFolderField;
    private final JComboBox<RunFolder> scenarioFolderSelector = new JComboBox<>();
    private final JLabel statusLabel = new JLabel();

    AnalysisLoadPanel(Component dialogParent, Path initialParentFolder, Listener listener) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 5));
        this.dialogParent = dialogParent;
        this.listener = listener;
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(232, 235, 239)),
                BorderFactory.createEmptyBorder(0, 8, 1, 8)));

        JLabel title = new JLabel("Load scenario folder");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        add(title);

        add(new JLabel("Top-level directory:"));
        parentFolderField = new JTextField(initialParentFolder.toString(), 30);
        parentFolderField.setToolTipText("Folder containing recorded scenario subfolders");
        parentFolderField.addActionListener(event -> refreshFolders());
        add(parentFolderField);

        JButton browseButton = new JButton("Browse…");
        browseButton.addActionListener(event -> browseForParentFolder());
        add(browseButton);

        add(new JLabel("Scenario:"));
        scenarioFolderSelector.setPreferredSize(new Dimension(285, 28));
        scenarioFolderSelector.setToolTipText("Recorded scenario subfolder to replay");
        add(scenarioFolderSelector);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(event -> refreshFolders());
        add(refreshButton);

        JButton loadButton = new JButton("Load scenario");
        loadButton.addActionListener(event -> loadSelectedScenario());
        add(loadButton);

        statusLabel.setForeground(new Color(91, 103, 115));
        add(statusLabel);
        refreshFolders();
    }

    void refreshFolders() {
        RunFolder previous = (RunFolder) scenarioFolderSelector.getSelectedItem();
        scenarioFolderSelector.removeAllItems();
        try {
            Path parent = parentFolder();
            if (!Files.isDirectory(parent)) {
                statusLabel.setForeground(new Color(176, 40, 40));
                statusLabel.setText("Directory not found");
                return;
            }
            List<Path> folders;
            try (Stream<Path> paths = Files.list(parent)) {
                folders = paths
                        .filter(Files::isDirectory)
                        .filter(AnalysisLoadPanel::containsReplayData)
                        .sorted(Comparator.comparing(
                                (Path path) -> path.getFileName().toString()).reversed())
                        .toList();
            }
            for (Path folder : folders) {
                scenarioFolderSelector.addItem(new RunFolder(folder));
            }
            if (previous != null) {
                selectFolder(previous.path());
            }
            statusLabel.setForeground(new Color(91, 103, 115));
            statusLabel.setText(folders.isEmpty()
                    ? "No recorded runs found"
                    : folders.size() + " run(s) found");
        } catch (InvalidPathException exception) {
            statusLabel.setForeground(new Color(176, 40, 40));
            statusLabel.setText("Invalid top-level directory");
        } catch (IOException exception) {
            statusLabel.setForeground(new Color(176, 40, 40));
            statusLabel.setText("Could not read directory");
        }
    }

    void setParentFolder(Path parentFolder) {
        parentFolderField.setText(parentFolder.toAbsolutePath().normalize().toString());
        refreshFolders();
    }

    private Path parentFolder() {
        String text = parentFolderField.getText().trim();
        if (text.isEmpty()) {
            throw new InvalidPathException(text, "A top-level directory is required");
        }
        Path parent = Path.of(text).toAbsolutePath().normalize();
        parentFolderField.setText(parent.toString());
        return parent;
    }

    private void browseForParentFolder() {
        Path initial;
        try {
            initial = parentFolder();
        } catch (InvalidPathException exception) {
            initial = Path.of(".").toAbsolutePath().normalize();
        }
        JFileChooser chooser = new JFileChooser(initial.toFile());
        chooser.setDialogTitle("Choose recorded-scenario parent folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(dialogParent) == JFileChooser.APPROVE_OPTION) {
            parentFolderField.setText(chooser.getSelectedFile().toPath().toString());
            refreshFolders();
        }
    }

    private void loadSelectedScenario() {
        RunFolder selected = (RunFolder) scenarioFolderSelector.getSelectedItem();
        if (selected == null) {
            statusLabel.setForeground(new Color(176, 40, 40));
            statusLabel.setText("Select a recorded run first");
            return;
        }
        listener.loadScenario(selected.path());
    }

    private void selectFolder(Path path) {
        for (int index = 0; index < scenarioFolderSelector.getItemCount(); index++) {
            RunFolder item = scenarioFolderSelector.getItemAt(index);
            if (item.path().equals(path)) {
                scenarioFolderSelector.setSelectedIndex(index);
                return;
            }
        }
    }

    private static boolean containsReplayData(Path folder) {
        if (containsCsv(folder)) {
            return true;
        }
        return containsCsv(folder.resolve(TrackCsvRecorder.TRACK_DIRECTORY))
                || containsCsv(folder.resolve(TrackCsvRecorder.GROUND_TRUTH_DIRECTORY))
                || containsCsv(folder.resolve(TrackCsvRecorder.MEASUREMENT_DIRECTORY));
    }

    private static boolean containsCsv(Path folder) {
        if (!Files.isDirectory(folder)) {
            return false;
        }
        try (Stream<Path> paths = Files.list(folder)) {
            return paths.anyMatch(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().toLowerCase().endsWith(".csv"));
        } catch (IOException exception) {
            return false;
        }
    }

    private record RunFolder(Path path) {
        @Override
        public String toString() {
            return path.getFileName().toString();
        }
    }
}
