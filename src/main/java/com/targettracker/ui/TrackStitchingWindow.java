package com.targettracker.ui;

import com.targettracker.analysis.TrackStitchingAnalyzer;
import com.targettracker.recording.RecordedScenario;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.function.ToDoubleFunction;

/** Separate three-section UI for track-stitching candidate analysis. */
final class TrackStitchingWindow extends JDialog {
    private final RecordedScenario scenario;
    private final TrackStitchingAnalyzer analyzer = new TrackStitchingAnalyzer();
    private final JTabbedPane eventTabs = new JTabbedPane(JTabbedPane.TOP);
    private final JTextArea summaryArea = new JTextArea();
    private final JTextArea joinTimesArea = new JTextArea();
    private final JTextArea costsArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Ready");
    private final TimeRangeControl coastedWindow;
    private final TimeRangeControl newWindow;
    private final JCheckBox allowDeadTracks = new JCheckBox("Allow stitching to dead tracks");
    private final JTextField resolutionField = new JTextField("0.5", 7);
    private final JButton analyzeButton = new JButton("Run stitching analysis");
    private List<TrackStitchingAnalyzer.EventResult> events = List.of();

    TrackStitchingWindow(Frame owner, RecordedScenario scenario) {
        super(owner, "Track Stitching Analysis — " + scenario.scenarioName(), false);
        this.scenario = scenario;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1_100, 700));
        setSize(1_500, 900);

        int maximumWindowSeconds = Math.max(60, (int) Math.ceil(scenario.durationSeconds()));
        coastedWindow = new TimeRangeControl(
                "Allowed coasted-track time", maximumWindowSeconds,
                Math.min(5, maximumWindowSeconds), Math.min(120, maximumWindowSeconds));
        newWindow = new TimeRangeControl(
                "Allowed new-track age", maximumWindowSeconds,
                0, Math.min(60, maximumWindowSeconds));

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.setBackground(new Color(243, 246, 249));
        setContentPane(content);

        JPanel titlePanel = new JPanel(new BorderLayout());
        titlePanel.setOpaque(false);
        JLabel title = new JLabel("Track Stitching Analysis");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20.0f));
        titlePanel.add(title, BorderLayout.WEST);
        JLabel scenarioLabel = new JLabel(scenario.scenarioName());
        scenarioLabel.setForeground(new Color(80, 92, 104));
        titlePanel.add(scenarioLabel, BorderLayout.EAST);
        content.add(titlePanel, BorderLayout.NORTH);

        eventTabs.setBorder(BorderFactory.createTitledBorder("Candidate scenario timestamps"));
        eventTabs.addChangeListener(event -> updateOutput());

        JPanel right = new JPanel(new GridLayout(2, 1, 0, 10));
        right.setOpaque(false);
        right.setPreferredSize(new Dimension(380, 0));
        right.add(createConfigurationPanel());
        right.add(createOutputPanel());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, eventTabs, right);
        split.setResizeWeight(0.78);
        split.setContinuousLayout(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        content.add(split, BorderLayout.CENTER);

        analyzeButton.addActionListener(event -> runAnalysis());
        resolutionField.addActionListener(event -> runAnalysis());
        setLocationRelativeTo(owner);
        runAnalysis();
    }

    private JPanel createConfigurationPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Configuration"),
                BorderFactory.createEmptyBorder(6, 10, 10, 10)));
        panel.add(coastedWindow);
        panel.add(Box.createVerticalStrut(8));
        panel.add(newWindow);
        panel.add(Box.createVerticalStrut(8));
        allowDeadTracks.setOpaque(false);
        allowDeadTracks.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(allowDeadTracks);
        panel.add(Box.createVerticalStrut(10));
        JPanel resolutionRow = new JPanel(new BorderLayout(8, 0));
        resolutionRow.setOpaque(false);
        resolutionRow.setAlignmentX(LEFT_ALIGNMENT);
        resolutionRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        resolutionRow.add(new JLabel("Time-bank resolution (seconds)"), BorderLayout.CENTER);
        resolutionRow.add(resolutionField, BorderLayout.EAST);
        panel.add(resolutionRow);
        panel.add(Box.createVerticalStrut(10));
        analyzeButton.setAlignmentX(LEFT_ALIGNMENT);
        analyzeButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        panel.add(analyzeButton);
        panel.add(Box.createVerticalStrut(7));
        statusLabel.setForeground(new Color(80, 92, 104));
        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(statusLabel);
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createTitledBorder("Analysis output"));

        configureOutputArea(summaryArea);
        configureOutputArea(joinTimesArea);
        configureOutputArea(costsArea);

        JScrollPane summaryScroll = new JScrollPane(summaryArea);
        summaryScroll.setBorder(BorderFactory.createTitledBorder("Scenario summary"));
        summaryScroll.setPreferredSize(new Dimension(0, 138));
        panel.add(summaryScroll, BorderLayout.NORTH);

        JPanel pairedMatrices = new JPanel(new GridLayout(1, 2, 6, 0));
        pairedMatrices.setOpaque(false);
        JScrollPane joinScroll = new JScrollPane(joinTimesArea);
        joinScroll.setBorder(BorderFactory.createTitledBorder("Estimated join times"));
        JScrollPane costScroll = new JScrollPane(costsArea);
        costScroll.setBorder(BorderFactory.createTitledBorder("Track stitching costs (NLL)"));
        pairedMatrices.add(joinScroll);
        pairedMatrices.add(costScroll);
        panel.add(pairedMatrices, BorderLayout.CENTER);
        return panel;
    }

    private static void configureOutputArea(JTextArea area) {
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        area.setBackground(new Color(250, 251, 252));
        area.setLineWrap(false);
    }

    private void runAnalysis() {
        final double resolution;
        try {
            resolution = Double.parseDouble(resolutionField.getText().trim());
            if (!Double.isFinite(resolution) || resolution <= 0.0) {
                throw new NumberFormatException();
            }
            resolutionField.setBackground(Color.WHITE);
        } catch (NumberFormatException exception) {
            resolutionField.setBackground(new Color(255, 224, 224));
            statusLabel.setText("Enter a positive time-bank resolution");
            return;
        }
        TrackStitchingAnalyzer.Configuration configuration =
                new TrackStitchingAnalyzer.Configuration(
                        coastedWindow.minimumSeconds(),
                        coastedWindow.maximumSeconds(),
                        newWindow.minimumSeconds(),
                        newWindow.maximumSeconds(),
                        allowDeadTracks.isSelected(),
                        resolution);
        analyzeButton.setEnabled(false);
        statusLabel.setText("Analyzing measurement times…");
        eventTabs.removeAll();
        eventTabs.addTab("Analyzing…", centeredLabel("Computing candidate events…"));
        clearOutput();

        SwingWorker<List<TrackStitchingAnalyzer.EventResult>, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected List<TrackStitchingAnalyzer.EventResult> doInBackground() {
                        return analyzer.analyze(scenario, configuration);
                    }

                    @Override
                    protected void done() {
                        analyzeButton.setEnabled(true);
                        try {
                            events = get();
                            populateEventTabs();
                            statusLabel.setText(events.size() + " candidate timestamp(s)");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            statusLabel.setText("Analysis interrupted");
                        } catch (ExecutionException exception) {
                            statusLabel.setText("Analysis failed: "
                                    + exception.getCause().getMessage());
                            events = List.of();
                            populateEventTabs();
                        }
                    }
                };
        worker.execute();
    }

    private void populateEventTabs() {
        eventTabs.removeAll();
        if (events.isEmpty()) {
            eventTabs.addTab("No candidates", centeredLabel(
                    "No measurement time satisfies both configured segment windows."));
            summaryArea.setText("No stitching candidates for the current configuration.");
            joinTimesArea.setText("");
            costsArea.setText("");
            return;
        }
        for (TrackStitchingAnalyzer.EventResult event : events) {
            eventTabs.addTab(formatTime(event.timeSeconds()),
                    new StitchingMapPanel(scenario, event));
        }
        eventTabs.setSelectedIndex(0);
        updateOutput();
    }

    private void updateOutput() {
        int index = eventTabs.getSelectedIndex();
        if (index < 0 || index >= events.size()) {
            return;
        }
        TrackStitchingAnalyzer.EventResult event = events.get(index);
        summaryArea.setText(formatSummary(event));
        joinTimesArea.setText(formatJoinTimes(event));
        costsArea.setText(formatCosts(event));
        summaryArea.setCaretPosition(0);
        joinTimesArea.setCaretPosition(0);
        costsArea.setCaretPosition(0);
    }

    private void clearOutput() {
        summaryArea.setText("");
        joinTimesArea.setText("");
        costsArea.setText("");
    }

    private static String formatSummary(TrackStitchingAnalyzer.EventResult event) {
        StringBuilder text = new StringBuilder();
        text.append("Scenario time: ").append(formatTime(event.timeSeconds())).append("\n\n");
        text.append("Coasting/old tracks:\n");
        event.oldSegments().forEach(segment -> text.append("  ")
                .append(segment.trackId())
                .append(segment.deadAtEvent() ? " (dead)" : "")
                .append("  last update ").append(formatTime(segment.lastUpdateTimeSeconds()))
                .append('\n'));
        text.append("\nNew tracks:\n");
        event.newSegments().forEach(segment -> text.append("  ")
                .append(segment.trackId())
                .append("  formed ").append(formatTime(segment.formationTimeSeconds()))
                .append('\n'));

        appendStringMatrix(text, "Truth target association", event,
                result -> result.truthTargetId().isBlank() ? "—" : result.truthTargetId());
        return text.toString();
    }

    private static String formatJoinTimes(TrackStitchingAnalyzer.EventResult event) {
        StringBuilder text = new StringBuilder();
        appendTimeMatrix(text, "Simple midpoint join time", event,
                TrackStitchingAnalyzer.PairResult::simpleJoinTimeSeconds);
        appendTimeMatrix(text, "Kinematic midpoint join time", event,
                TrackStitchingAnalyzer.PairResult::kinematicJoinTimeSeconds);
        appendTimeMatrix(text, "Mahalanobis time-bank join time", event,
                TrackStitchingAnalyzer.PairResult::statisticalJoinTimeSeconds);
        appendTimeMatrix(text, "Ground-truth RMS join time", event,
                TrackStitchingAnalyzer.PairResult::actualJoinTimeSeconds);
        return text.toString();
    }

    private static String formatCosts(TrackStitchingAnalyzer.EventResult event) {
        StringBuilder text = new StringBuilder();
        appendNumberMatrix(text, "NLL at simple midpoint", event,
                TrackStitchingAnalyzer.PairResult::simpleNegativeLogLikelihood);
        appendNumberMatrix(text, "NLL at kinematic midpoint", event,
                TrackStitchingAnalyzer.PairResult::kinematicNegativeLogLikelihood);
        appendNumberMatrix(text, "NLL at Mahalanobis bank time", event,
                TrackStitchingAnalyzer.PairResult::statisticalNegativeLogLikelihood);
        appendNumberMatrix(text, "NLL at ground-truth RMS time", event,
                TrackStitchingAnalyzer.PairResult::actualNegativeLogLikelihood);
        return text.toString();
    }

    private static void appendTimeMatrix(
            StringBuilder text,
            String title,
            TrackStitchingAnalyzer.EventResult event,
            ToDoubleFunction<TrackStitchingAnalyzer.PairResult> value) {
        appendStringMatrix(text, title, event, result -> {
            double time = value.applyAsDouble(result);
            return Double.isFinite(time) ? formatTime(time) : "—";
        });
    }

    private static void appendNumberMatrix(
            StringBuilder text,
            String title,
            TrackStitchingAnalyzer.EventResult event,
            ToDoubleFunction<TrackStitchingAnalyzer.PairResult> value) {
        appendStringMatrix(text, title, event, result -> {
            double number = value.applyAsDouble(result);
            return Double.isFinite(number) ? String.format(Locale.ROOT, "%.3f", number) : "—";
        });
    }

    private static void appendStringMatrix(
            StringBuilder text,
            String title,
            TrackStitchingAnalyzer.EventResult event,
            java.util.function.Function<TrackStitchingAnalyzer.PairResult, String> value) {
        text.append("\n").append(title).append("\n");
        List<String> columns = event.newSegments().stream()
                .map(TrackStitchingAnalyzer.Segment::trackId).toList();
        text.append(String.format("%-12s", "old \\ new"));
        columns.forEach(column -> text.append(String.format("%14s", column)));
        text.append('\n');
        Map<String, TrackStitchingAnalyzer.PairResult> pairs = new HashMap<>();
        event.pairs().forEach(pair -> pairs.put(
                pair.oldTrackId() + "\u0000" + pair.newTrackId(), pair));
        for (TrackStitchingAnalyzer.Segment row : event.oldSegments()) {
            text.append(String.format("%-12s", row.trackId()));
            for (String column : columns) {
                TrackStitchingAnalyzer.PairResult pair = pairs.get(
                        row.trackId() + "\u0000" + column);
                String formatted = pair == null ? "—" : value.apply(pair);
                text.append(String.format("%14s", formatted));
            }
            text.append('\n');
        }
    }

    private static JPanel centeredLabel(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(246, 248, 251));
        JLabel label = new JLabel(text, JLabel.CENTER);
        label.setForeground(new Color(80, 92, 104));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private static String formatTime(double seconds) {
        if (!Double.isFinite(seconds)) {
            return "—";
        }
        int whole = Math.max(0, (int) Math.floor(seconds));
        int tenths = (int) Math.round((seconds - whole) * 10.0);
        if (tenths == 10) {
            whole++;
            tenths = 0;
        }
        return "%02d:%02d.%d".formatted(whole / 60, whole % 60, tenths);
    }
}
