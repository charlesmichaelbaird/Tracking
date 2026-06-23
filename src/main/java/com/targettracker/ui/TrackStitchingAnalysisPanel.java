package com.targettracker.ui;

import com.targettracker.analysis.TrackStitchingAnalyzer;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingWorker;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/** Embedded main-display controls for track-stitching candidate analysis. */
final class TrackStitchingAnalysisPanel extends JPanel {
    private final RecordedScenario scenario;
    private final ScenarioPlayback playback;
    private final EarthMapCanvas mapCanvas;
    private final ScenarioTimelinePanel timelinePanel;
    private final Runnable closeAction;
    private final Consumer<String> statusConsumer;
    private final TrackStitchingAnalyzer analyzer = new TrackStitchingAnalyzer();
    private final JTabbedPane eventTabs = new JTabbedPane(JTabbedPane.TOP);
    private final JTextArea summaryArea = new JTextArea();
    private final JTextArea assignmentArea = new JTextArea();
    private final DefaultTableModel metricsModel = new DefaultTableModel(
            new Object[]{"Pair", "Estimated join time", "NLL", "Mahalanobis"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JTable metricsTable = new JTable(metricsModel);
    private List<MetricRow> metricRows = List.of();
    private final JLabel statusLabel = new JLabel("Ready");
    private final TimeRangeControl coastedWindow;
    private final TimeRangeControl newWindow;
    private final JCheckBox allowDeadTracks = new JCheckBox("Allow stitching to dead tracks");
    private final JTextField resolutionField = new JTextField("0.5", 7);
    private final JTextField falseAlarmRateField = new JTextField("1e-6", 8);
    private final JTextField birthRateField = new JTextField("1e-6", 8);
    private final JButton analyzeButton = new JButton("Run stitching analysis");
    private final JToggleButton feasibilityButton = new JToggleButton("Feasibility", true);
    private final JToggleButton alternativeButton = new JToggleButton("Alternative Hypothesis");
    private final JToggleButton showAllButton = new JToggleButton("All", true);
    private final JToggleButton showGreyedButton = new JToggleButton("Greyed");
    private final JToggleButton showOnlyButton = new JToggleButton("Only Stitched");
    private List<TrackStitchingAnalyzer.EventResult> events = List.of();
    private boolean active = true;

    private enum ScoreMode {
        FEASIBILITY,
        ALTERNATIVE
    }

    TrackStitchingAnalysisPanel(
            RecordedScenario scenario,
            ScenarioPlayback playback,
            EarthMapCanvas mapCanvas,
            ScenarioTimelinePanel timelinePanel,
            Runnable closeAction,
            Consumer<String> statusConsumer) {
        super(new BorderLayout(0, 10));
        this.scenario = scenario;
        this.playback = playback;
        this.mapCanvas = mapCanvas;
        this.timelinePanel = timelinePanel;
        this.closeAction = closeAction;
        this.statusConsumer = statusConsumer;
        setPreferredSize(new Dimension(620, 0));
        setMinimumSize(new Dimension(540, 0));
        setBackground(new Color(246, 248, 251));
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(214, 220, 227)));

        int maximumWindowSeconds = Math.max(60, (int) Math.ceil(scenario.durationSeconds()));
        coastedWindow = new TimeRangeControl(
                "Allowed coasted-track time", maximumWindowSeconds,
                Math.min(5, maximumWindowSeconds), Math.min(120, maximumWindowSeconds));
        newWindow = new TimeRangeControl(
                "Allowed new-track age", maximumWindowSeconds,
                0, Math.min(60, maximumWindowSeconds));

        eventTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        eventTabs.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(214, 220, 227)),
                BorderFactory.createEmptyBorder(2, 8, 1, 8)));
        eventTabs.setPreferredSize(new Dimension(600, 46));
        eventTabs.addChangeListener(event -> updateSelectedEvent());

        add(createTitlePanel(), BorderLayout.NORTH);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(0, 10, 10, 10));
        body.add(createConfigurationPanel(), BorderLayout.NORTH);
        body.add(createOutputPanel(), BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        analyzeButton.addActionListener(event -> runAnalysis());
        resolutionField.addActionListener(event -> runAnalysis());
        falseAlarmRateField.addActionListener(event -> runAnalysis());
        birthRateField.addActionListener(event -> runAnalysis());
        allowDeadTracks.addActionListener(event -> runAnalysis());
        feasibilityButton.addActionListener(event -> {
            syncScoreModeButtons();
            updateSelectedEvent();
        });
        alternativeButton.addActionListener(event -> {
            syncScoreModeButtons();
            updateSelectedEvent();
        });
        showAllButton.addActionListener(event -> updateSelectedEvent());
        showGreyedButton.addActionListener(event -> updateSelectedEvent());
        showOnlyButton.addActionListener(event -> updateSelectedEvent());
        runAnalysis();
    }

    JComponent tabStrip() {
        return eventTabs;
    }

    void deactivate() {
        active = false;
        timelinePanel.clearCandidateMarkers();
        mapCanvas.clearStitchingFocus();
    }

    private JPanel createTitlePanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(214, 220, 227)),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        JLabel title = new JLabel("Track Stitching Analysis");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16.0f));
        panel.add(title, BorderLayout.CENTER);
        JButton backButton = new JButton("Back to replay");
        backButton.addActionListener(event -> closeAction.run());
        panel.add(backButton, BorderLayout.EAST);
        return panel;
    }

    private JPanel createConfigurationPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Configuration"),
                BorderFactory.createEmptyBorder(6, 10, 10, 10)));
        panel.add(createTimingAndAlternativePanel());
        panel.add(Box.createVerticalStrut(10));
        JPanel resolutionRow = new JPanel(new BorderLayout(8, 0));
        resolutionRow.setOpaque(false);
        resolutionRow.setAlignmentX(LEFT_ALIGNMENT);
        resolutionRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        resolutionRow.add(new JLabel("Time-bank resolution (seconds)"), BorderLayout.CENTER);
        resolutionRow.add(resolutionField, BorderLayout.EAST);
        panel.add(resolutionRow);
        panel.add(Box.createVerticalStrut(10));
        panel.add(createVisibilityPanel());
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

    private JPanel createTimingAndAlternativePanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 112));

        JPanel timingPanel = new JPanel();
        timingPanel.setLayout(new BoxLayout(timingPanel, BoxLayout.Y_AXIS));
        timingPanel.setOpaque(false);
        timingPanel.add(coastedWindow);
        timingPanel.add(Box.createVerticalStrut(8));
        timingPanel.add(newWindow);
        timingPanel.add(Box.createVerticalStrut(5));
        allowDeadTracks.setOpaque(false);
        allowDeadTracks.setAlignmentX(LEFT_ALIGNMENT);
        timingPanel.add(allowDeadTracks);

        panel.add(timingPanel, BorderLayout.CENTER);
        panel.add(createAlternativeHypothesisPanel(), BorderLayout.EAST);
        return panel;
    }

    private JPanel createAlternativeHypothesisPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 6, 5));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setPreferredSize(new Dimension(260, 86));
        panel.setMaximumSize(new Dimension(280, 92));
        panel.setBorder(BorderFactory.createTitledBorder("Alternative Hypothesis"));
        falseAlarmRateField.setToolTipText("Expected false alarms per 1 km^3 innovation volume");
        birthRateField.setToolTipText("Expected target births per 1 km^3 innovation volume");
        panel.add(new JLabel("False alarms / km^3"));
        panel.add(falseAlarmRateField);
        panel.add(new JLabel("Target births / km^3"));
        panel.add(birthRateField);
        return panel;
    }

    private JPanel createVisibilityPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 3, 5, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
        panel.setBorder(BorderFactory.createTitledBorder("View"));
        ButtonGroup group = new ButtonGroup();
        group.add(showAllButton);
        group.add(showGreyedButton);
        group.add(showOnlyButton);
        panel.add(showAllButton);
        panel.add(showGreyedButton);
        panel.add(showOnlyButton);
        return panel;
    }

    private JPanel createOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createTitledBorder("Analysis output"));

        configureOutputArea(summaryArea);
        configureOutputArea(assignmentArea);
        configureMetricsTable();

        JPanel summaryPanel = new JPanel(new GridLayout(1, 2, 6, 0));
        summaryPanel.setOpaque(false);
        JScrollPane summaryScroll = new JScrollPane(summaryArea);
        summaryScroll.setBorder(BorderFactory.createTitledBorder("Scenario summary"));
        JScrollPane assignmentScroll = new JScrollPane(assignmentArea);
        assignmentScroll.setBorder(BorderFactory.createTitledBorder("Optimal assignments"));
        summaryPanel.add(summaryScroll);
        summaryPanel.add(assignmentScroll);
        summaryPanel.setPreferredSize(new Dimension(0, 158));
        panel.add(summaryPanel, BorderLayout.NORTH);

        JPanel metricsPanel = new JPanel(new BorderLayout(0, 4));
        metricsPanel.setOpaque(false);
        metricsPanel.add(createScoreModePanel(), BorderLayout.NORTH);
        JScrollPane metricsScroll = new JScrollPane(
                metricsTable,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        metricsScroll.setBorder(BorderFactory.createTitledBorder(
                "Join time, NLL, and Mahalanobis metrics"));
        metricsPanel.add(metricsScroll, BorderLayout.CENTER);
        panel.add(metricsPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createScoreModePanel() {
        JPanel panel = new JPanel(new GridLayout(1, 2, 6, 0));
        panel.setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        group.add(feasibilityButton);
        group.add(alternativeButton);
        feasibilityButton.setToolTipText("Show Gaussian NLL and Mahalanobis feasibility scores");
        alternativeButton.setToolTipText("Show static and learned spatial-density NLLR scores");
        panel.add(feasibilityButton);
        panel.add(alternativeButton);
        syncScoreModeButtons();
        return panel;
    }

    private void syncScoreModeButtons() {
        styleScoreModeButton(feasibilityButton, feasibilityButton.isSelected());
        styleScoreModeButton(alternativeButton, alternativeButton.isSelected());
    }

    private static void styleScoreModeButton(JToggleButton button, boolean selected) {
        button.setOpaque(true);
        if (selected) {
            button.setBackground(new Color(48, 84, 128));
            button.setForeground(Color.WHITE);
        } else {
            button.setBackground(new Color(222, 226, 232));
            button.setForeground(new Color(98, 106, 116));
        }
    }

    private static void configureOutputArea(JTextArea area) {
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        area.setBackground(new Color(250, 251, 252));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
    }

    private void configureMetricsTable() {
        metricsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        metricsTable.setFillsViewportHeight(true);
        metricsTable.setRowHeight(23);
        metricsTable.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        metricsTable.setDefaultRenderer(Object.class, new MetricCellRenderer());
        metricsTable.getTableHeader().setReorderingAllowed(false);
        metricsTable.getTableHeader().setFont(
                metricsTable.getTableHeader().getFont().deriveFont(Font.BOLD, 11.0f));
        configureMetricColumnWidths();
    }

    private void configureMetricColumnWidths() {
        if (metricsTable.getColumnModel().getColumnCount() < 4) {
            return;
        }
        metricsTable.getColumnModel().getColumn(0).setPreferredWidth(95);
        metricsTable.getColumnModel().getColumn(1).setPreferredWidth(210);
        metricsTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        metricsTable.getColumnModel().getColumn(3).setPreferredWidth(135);
    }

    private void runAnalysis() {
        final double resolution;
        final double falseAlarmRate;
        final double birthRate;
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
        try {
            falseAlarmRate = parseNonNegative(falseAlarmRateField);
            birthRate = parseNonNegative(birthRateField);
        } catch (NumberFormatException exception) {
            statusLabel.setText("Enter non-negative alternative-hypothesis densities");
            return;
        }
        TrackStitchingAnalyzer.Configuration configuration =
                new TrackStitchingAnalyzer.Configuration(
                        coastedWindow.minimumSeconds(),
                        coastedWindow.maximumSeconds(),
                        newWindow.minimumSeconds(),
                        newWindow.maximumSeconds(),
                        allowDeadTracks.isSelected(),
                        resolution,
                        falseAlarmRate,
                        birthRate);
        analyzeButton.setEnabled(false);
        statusLabel.setText("Analyzing measurement times...");
        eventTabs.removeAll();
        eventTabs.addTab("Analyzing...", emptyTabBody());
        timelinePanel.clearCandidateMarkers();
        mapCanvas.clearStitchingFocus();
        clearOutput();

        SwingWorker<List<TrackStitchingAnalyzer.EventResult>, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected List<TrackStitchingAnalyzer.EventResult> doInBackground() {
                        return analyzer.analyze(scenario, configuration);
                    }

                    @Override
                    protected void done() {
                        if (!active) {
                            return;
                        }
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

    private static double parseNonNegative(JTextField field) {
        try {
            double value = Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(value) || value < 0.0) {
                throw new NumberFormatException();
            }
            field.setBackground(Color.WHITE);
            return value;
        } catch (NumberFormatException exception) {
            field.setBackground(new Color(255, 224, 224));
            throw exception;
        }
    }

    private void populateEventTabs() {
        eventTabs.removeAll();
        if (events.isEmpty()) {
            eventTabs.addTab("No candidates", emptyTabBody());
            summaryArea.setText("No stitching candidates for the current configuration.");
            assignmentArea.setText("");
            metricsModel.setRowCount(0);
            metricRows = List.of();
            timelinePanel.clearCandidateMarkers();
            mapCanvas.clearStitchingFocus();
            return;
        }
        for (TrackStitchingAnalyzer.EventResult event : events) {
            eventTabs.addTab(formatTime(event.timeSeconds()), emptyTabBody());
        }
        eventTabs.setSelectedIndex(0);
        updateSelectedEvent();
    }

    private void updateSelectedEvent() {
        if (!active) {
            return;
        }
        int index = eventTabs.getSelectedIndex();
        if (index < 0 || index >= events.size()) {
            return;
        }
        TrackStitchingAnalyzer.EventResult event = events.get(index);
        summaryArea.setText(formatSummary(event));
        assignmentArea.setText(formatAssignments(event, scoreMode()));
        populateMetricsTable(event);
        summaryArea.setCaretPosition(0);
        assignmentArea.setCaretPosition(0);
        applyDisplayFocus(event);
    }

    private void applyDisplayFocus(TrackStitchingAnalyzer.EventResult event) {
        Set<String> trackIds = candidateTrackIds(event);
        Set<String> targetIds = candidateTargetIds(event, trackIds);
        mapCanvas.setStitchingFocus(
                trackIds,
                targetIds,
                selectedVisibilityMode(),
                candidateSegments(event));
        timelinePanel.setCandidateMarkers(
                events.stream().map(TrackStitchingAnalyzer.EventResult::timeSeconds).toList(),
                event.timeSeconds());
        if (playback.canSeek()) {
            playback.seekTo(event.timeSeconds());
        } else {
            timelinePanel.refresh();
            mapCanvas.repaint();
        }
        statusConsumer.accept("Track stitching candidate at %s".formatted(
                formatTime(event.timeSeconds())));
    }

    private EarthMapCanvas.StitchingVisibilityMode selectedVisibilityMode() {
        if (showOnlyButton.isSelected()) {
            return EarthMapCanvas.StitchingVisibilityMode.ONLY_STITCHED;
        }
        if (showGreyedButton.isSelected()) {
            return EarthMapCanvas.StitchingVisibilityMode.GREYED;
        }
        return EarthMapCanvas.StitchingVisibilityMode.ALL;
    }

    private ScoreMode scoreMode() {
        return alternativeButton.isSelected() ? ScoreMode.ALTERNATIVE : ScoreMode.FEASIBILITY;
    }

    private static Set<String> candidateTrackIds(TrackStitchingAnalyzer.EventResult event) {
        Set<String> ids = new LinkedHashSet<>();
        event.oldSegments().forEach(segment -> ids.add(segment.trackId()));
        event.newSegments().forEach(segment -> ids.add(segment.trackId()));
        return ids;
    }

    private static List<TrackStitchingAnalyzer.Segment> candidateSegments(
            TrackStitchingAnalyzer.EventResult event) {
        return event.allSegments();
    }

    private Set<String> candidateTargetIds(
            TrackStitchingAnalyzer.EventResult event,
            Set<String> trackIds) {
        Set<String> ids = new LinkedHashSet<>();
        for (TrackStitchingAnalyzer.PairResult pair : event.pairs()) {
            if (!pair.truthTargetId().isBlank()) {
                ids.add(pair.truthTargetId());
            }
        }
        for (RecordedMeasurement measurement : scenario.measurements()) {
            if (trackIds.contains(measurement.associatedTrackId())
                    && !measurement.targetId().isBlank()) {
                ids.add(measurement.targetId());
            }
        }
        return ids;
    }

    private void clearOutput() {
        summaryArea.setText("");
        assignmentArea.setText("");
        metricsModel.setRowCount(0);
        metricRows = List.of();
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

        text.append("\nTruth target association:\n");
        for (TrackStitchingAnalyzer.PairResult pair : event.pairs()) {
            text.append("  ")
                    .append(pair.oldTrackId())
                    .append(" -> ")
                    .append(pair.newTrackId())
                    .append(" : ")
                    .append(pair.truthTargetId().isBlank() ? "-" : pair.truthTargetId())
                    .append('\n');
        }
        return text.toString();
    }

    private static String formatAssignments(
            TrackStitchingAnalyzer.EventResult event,
            ScoreMode mode) {
        StringBuilder text = new StringBuilder();
        text.append("Learned null birth density:\n  ")
                .append(formatScientific(event.learnedBirthDensityPerCubicKilometerSecond()))
                .append(" births / km^3 / s\n\n");
        if (mode == ScoreMode.ALTERNATIVE) {
            appendAssignments(text, "Static/uniform NLLR optimum", event.staticNllrAssignments());
            appendAssignments(text, "Learned spatial NLLR optimum", event.learnedNllrAssignments());
        } else {
            appendAssignments(text, "NLL optimum", event.nllAssignments());
            appendAssignments(text, "Mahalanobis optimum", event.mahalanobisAssignments());
        }
        return text.toString();
    }

    private static void appendAssignments(
            StringBuilder text,
            String title,
            List<TrackStitchingAnalyzer.OptimalAssignment> assignments) {
        text.append(title).append('\n');
        if (assignments.isEmpty()) {
            text.append("  - no feasible assignment\n\n");
            return;
        }
        for (TrackStitchingAnalyzer.OptimalAssignment assignment : assignments) {
            text.append("  ")
                    .append(assignment.oldTrackId())
                    .append(" -> ")
                    .append(assignment.newTrackId())
                    .append("  ")
                    .append(assignment.variant())
                    .append(" @ ")
                    .append(formatTime(assignment.joinTimeSeconds()))
                    .append("  score ")
                    .append(formatNumber(assignment.score()))
                    .append('\n');
        }
        text.append('\n');
    }

    private void populateMetricsTable(TrackStitchingAnalyzer.EventResult event) {
        metricsModel.setRowCount(0);
        ScoreMode mode = scoreMode();
        if (mode == ScoreMode.ALTERNATIVE) {
            metricsModel.setColumnIdentifiers(new Object[]{
                    "Pair", "Estimated join time", "Static/uniform NLLR", "Learned spatial NLLR"});
        } else {
            metricsModel.setColumnIdentifiers(new Object[]{
                    "Pair", "Estimated join time", "NLL", "Mahalanobis"});
        }
        configureMetricColumnWidths();
        List<MetricRow> rows = new ArrayList<>();
        for (TrackStitchingAnalyzer.PairResult pair : event.pairs()) {
            String pairName = pair.oldTrackId() + " -> " + pair.newTrackId();
            rows.add(addMetricRow(
                    event,
                    pair,
                    pairName,
                    "Simple midpoint",
                    "Simple midpoint @ " + formatTime(pair.simpleJoinTimeSeconds()),
                    mode == ScoreMode.ALTERNATIVE
                            ? pair.simpleStaticNegativeLogLikelihoodRatio()
                            : pair.simpleNegativeLogLikelihood(),
                    mode == ScoreMode.ALTERNATIVE
                            ? pair.simpleLearnedNegativeLogLikelihoodRatio()
                            : pair.simpleMahalanobisDistance()));
            rows.add(addMetricRow(
                    event,
                    pair,
                    pairName,
                    "Kinematic midpoint",
                    "Kinematic midpoint @ " + formatTime(pair.kinematicJoinTimeSeconds()),
                    mode == ScoreMode.ALTERNATIVE
                            ? pair.kinematicStaticNegativeLogLikelihoodRatio()
                            : pair.kinematicNegativeLogLikelihood(),
                    mode == ScoreMode.ALTERNATIVE
                            ? pair.kinematicLearnedNegativeLogLikelihoodRatio()
                            : pair.kinematicMahalanobisDistance()));
            rows.add(addMetricRow(
                    event,
                    pair,
                    pairName,
                    "Mahalanobis bank",
                    "Mahalanobis bank @ " + formatTime(pair.statisticalJoinTimeSeconds()),
                    mode == ScoreMode.ALTERNATIVE
                            ? pair.statisticalStaticNegativeLogLikelihoodRatio()
                            : pair.statisticalNegativeLogLikelihood(),
                    mode == ScoreMode.ALTERNATIVE
                            ? pair.statisticalLearnedNegativeLogLikelihoodRatio()
                            : pair.statisticalMahalanobisDistance()));
            rows.add(addMetricRow(
                    event,
                    pair,
                    pairName,
                    "Truth RMS",
                    "Truth RMS @ " + formatTime(pair.actualJoinTimeSeconds()),
                    mode == ScoreMode.ALTERNATIVE
                            ? pair.actualStaticNegativeLogLikelihoodRatio()
                            : pair.actualNegativeLogLikelihood(),
                    mode == ScoreMode.ALTERNATIVE
                            ? pair.actualLearnedNegativeLogLikelihoodRatio()
                            : pair.actualMahalanobisDistance()));
        }
        metricRows = List.copyOf(rows);
    }

    private MetricRow addMetricRow(
            TrackStitchingAnalyzer.EventResult event,
            TrackStitchingAnalyzer.PairResult pair,
            String pairName,
            String variant,
            String estimate,
            double negativeLogLikelihood,
            double mahalanobisDistance) {
        metricsModel.addRow(new Object[]{
                pairName,
                estimate,
                formatNumber(negativeLogLikelihood),
                formatNumber(mahalanobisDistance)
        });
        ScoreMode mode = scoreMode();
        return new MetricRow(
                pairName,
                variant,
                isAssigned(mode == ScoreMode.ALTERNATIVE
                        ? event.staticNllrAssignments()
                        : event.nllAssignments(), pair, variant),
                isAssigned(mode == ScoreMode.ALTERNATIVE
                        ? event.learnedNllrAssignments()
                        : event.mahalanobisAssignments(), pair, variant));
    }

    private static boolean isAssigned(
            List<TrackStitchingAnalyzer.OptimalAssignment> assignments,
            TrackStitchingAnalyzer.PairResult pair,
            String variant) {
        return assignments.stream().anyMatch(assignment ->
                assignment.oldTrackId().equals(pair.oldTrackId())
                        && assignment.newTrackId().equals(pair.newTrackId())
                        && assignment.variant().equals(variant));
    }

    private static String formatNumber(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String formatScientific(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.3e", value);
    }

    private final class MetricCellRenderer extends DefaultTableCellRenderer {
        private final Border emptyBorder = BorderFactory.createEmptyBorder(1, 4, 1, 4);

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {
            Component component = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (row < 0 || row >= metricRows.size()) {
                return component;
            }
            MetricRow metricRow = metricRows.get(row);
            if (!isSelected) {
                component.setBackground(rowBackground(metricRow));
                component.setForeground(new Color(25, 31, 37));
            }
            component.setFont(component.getFont().deriveFont(
                    column == 0 ? Font.BOLD : Font.PLAIN));
            setBorder(groupBorder(row, column));
            return component;
        }

        private Color rowBackground(MetricRow row) {
            if (row.nllOptimal() && row.mahalanobisOptimal()) {
                return new Color(220, 246, 225);
            }
            if (row.nllOptimal()) {
                return new Color(255, 244, 204);
            }
            if (row.mahalanobisOptimal()) {
                return new Color(220, 239, 255);
            }
            return Color.WHITE;
        }

        private Border groupBorder(int row, int column) {
            boolean first = row == 0 || !metricRows.get(row).pairName()
                    .equals(metricRows.get(row - 1).pairName());
            boolean last = row == metricRows.size() - 1 || !metricRows.get(row).pairName()
                    .equals(metricRows.get(row + 1).pairName());
            int top = first ? 2 : 0;
            int bottom = last ? 2 : 0;
            int left = column == 0 ? 2 : 0;
            int right = column == metricsTable.getColumnCount() - 1 ? 2 : 0;
            if (top == 0 && bottom == 0 && left == 0 && right == 0) {
                return emptyBorder;
            }
            return BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(
                            top, left, bottom, right, new Color(48, 56, 64)),
                    emptyBorder);
        }
    }

    private record MetricRow(
            String pairName,
            String variant,
            boolean nllOptimal,
            boolean mahalanobisOptimal) {
    }

    private static JPanel emptyTabBody() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(1, 0));
        return panel;
    }

    private static String formatTime(double seconds) {
        if (!Double.isFinite(seconds)) {
            return "-";
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
