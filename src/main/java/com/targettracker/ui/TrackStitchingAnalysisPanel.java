package com.targettracker.ui;

import com.targettracker.analysis.TrackStitchingAnalysisExporter;
import com.targettracker.analysis.TrackStitchingAnalyzer;
import com.targettracker.model.EcefPoint;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.tracking.TrackRecord;

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
import javax.swing.event.TableModelEvent;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final TrackStitchingAnalysisExporter exporter = new TrackStitchingAnalysisExporter();
    private final JTabbedPane eventTabs = new JTabbedPane(JTabbedPane.TOP);
    private final JTabbedPane outputTabs = new JTabbedPane(JTabbedPane.TOP);
    private final JTabbedPane rocTabs = new JTabbedPane(JTabbedPane.TOP);
    private final CardLayout sectionLayout = new CardLayout();
    private final JPanel sectionCards = new JPanel(sectionLayout);
    private final JTextArea summaryArea = new JTextArea();
    private final JTextArea assignmentArea = new JTextArea();
    private final DefaultTableModel metricsModel = new DefaultTableModel(
            new Object[]{"Pair", "Estimated join time", "Bhattacharyya Distance",
                    "Bhattacharyya Coefficient", "Hellinger Distance",
                    "State", "Poly"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return isOverlayColumn(column);
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return isOverlayColumn(columnIndex) ? Boolean.class : String.class;
        }
    };
    private final JTable metricsTable = new JTable(metricsModel);
    private final Border metricEmptyBorder = BorderFactory.createEmptyBorder(1, 4, 1, 4);
    private List<MetricRow> metricRows = List.of();
    private final JLabel statusLabel = new JLabel("Ready");
    private final TimeRangeControl coastedWindow;
    private final TimeRangeControl newWindow;
    private final JCheckBox allowDeadTracks = new JCheckBox("Allow stitching to dead tracks");
    private final JTextField resolutionField = new JTextField("0.5", 7);
    private final JTextField falseAlarmRateField = new JTextField("1e-6", 8);
    private final JTextField birthRateField = new JTextField("1e-6", 8);
    private final JTextField userNllrVolumeField = new JTextField("1.0", 8);
    private final JTextField outputDirectoryField = new JTextField("", 18);
    private final JButton analyzeButton = new JButton("Run stitching analysis");
    private final JButton rocButton = new JButton("ROC Curve");
    private final JButton exportButton = new JButton("Output data to folder");
    private final JToggleButton configurationSectionButton = new JToggleButton("Configuration");
    private final JToggleButton analysisOutputSectionButton =
            new JToggleButton("Analysis output", true);
    private final JToggleButton rocSectionButton = new JToggleButton("ROC curve");
    private final JToggleButton showAllButton = new JToggleButton("All", true);
    private final JToggleButton showGreyedButton = new JToggleButton("Greyed");
    private final JToggleButton showOnlyButton = new JToggleButton("Only Stitched");
    private List<TrackStitchingAnalyzer.EventResult> events = List.of();
    private TrackStitchingAnalyzer.AnalysisResult analysisResult =
            new TrackStitchingAnalyzer.AnalysisResult(List.of(), List.of());
    private TrackStitchingAnalyzer.Configuration latestConfiguration;
    private boolean populatingMetricsTable;
    private boolean active = true;
    private static final String CONFIGURATION_SECTION = "Configuration";
    private static final String ANALYSIS_OUTPUT_SECTION = "Analysis output";
    private static final String ROC_CURVE_SECTION = "ROC curve";

    private enum ScoreMode {
        MINIMUM_NLL,
        OVERLAP_3D,
        OVERLAP_6D,
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
        Path defaultOutputParent = scenario.folder().getParent() == null
                ? scenario.folder()
                : scenario.folder().getParent();
        outputDirectoryField.setText(defaultOutputParent.toString());

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
        body.add(createSectionSelector(), BorderLayout.NORTH);
        sectionCards.setOpaque(false);
        sectionCards.add(createConfigurationPanel(), CONFIGURATION_SECTION);
        sectionCards.add(createOutputPanel(), ANALYSIS_OUTPUT_SECTION);
        sectionCards.add(createRocCurvePanel(), ROC_CURVE_SECTION);
        body.add(sectionCards, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);
        showSection(ANALYSIS_OUTPUT_SECTION);

        analyzeButton.addActionListener(event -> runAnalysis());
        rocButton.addActionListener(event -> runRocCurve());
        resolutionField.addActionListener(event -> runAnalysis());
        falseAlarmRateField.addActionListener(event -> runAnalysis());
        birthRateField.addActionListener(event -> runAnalysis());
        userNllrVolumeField.addActionListener(event -> runAnalysis());
        allowDeadTracks.addActionListener(event -> runAnalysis());
        outputTabs.addChangeListener(event -> updateSelectedEvent());
        showAllButton.addActionListener(event -> updateSelectedEvent());
        showGreyedButton.addActionListener(event -> updateSelectedEvent());
        showOnlyButton.addActionListener(event -> updateSelectedEvent());
        exportButton.addActionListener(event -> exportAnalysis());
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
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(214, 220, 227)),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));

        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        JLabel title = new JLabel("Track Stitching Analysis");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16.0f));
        titleRow.add(title, BorderLayout.CENTER);
        JButton backButton = new JButton("Back to replay");
        backButton.addActionListener(event -> closeAction.run());
        titleRow.add(backButton, BorderLayout.EAST);
        panel.add(titleRow, BorderLayout.NORTH);

        JPanel actionRow = new JPanel(new BorderLayout(8, 0));
        actionRow.setOpaque(false);
        JPanel buttonRow = new JPanel(new GridLayout(1, 2, 7, 0));
        buttonRow.setOpaque(false);
        analyzeButton.setToolTipText("Compute stitching candidates for the current configuration");
        rocButton.setToolTipText("Compute 3D Hellinger-distance ROC curves");
        buttonRow.add(analyzeButton);
        buttonRow.add(rocButton);
        actionRow.add(buttonRow, BorderLayout.WEST);
        statusLabel.setForeground(new Color(80, 92, 104));
        actionRow.add(statusLabel, BorderLayout.CENTER);
        panel.add(actionRow, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createSectionSelector() {
        JPanel panel = new JPanel(new GridLayout(1, 3, 7, 0));
        panel.setOpaque(false);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        ButtonGroup group = new ButtonGroup();
        group.add(configurationSectionButton);
        group.add(analysisOutputSectionButton);
        group.add(rocSectionButton);
        configurationSectionButton.addActionListener(
                event -> showSection(CONFIGURATION_SECTION));
        analysisOutputSectionButton.addActionListener(
                event -> showSection(ANALYSIS_OUTPUT_SECTION));
        rocSectionButton.addActionListener(event -> showSection(ROC_CURVE_SECTION));
        panel.add(configurationSectionButton);
        panel.add(analysisOutputSectionButton);
        panel.add(rocSectionButton);
        return panel;
    }

    private void showSection(String name) {
        sectionLayout.show(sectionCards, name);
        boolean configuration = CONFIGURATION_SECTION.equals(name);
        boolean analysisOutput = ANALYSIS_OUTPUT_SECTION.equals(name);
        boolean rocCurve = ROC_CURVE_SECTION.equals(name);
        configurationSectionButton.setSelected(configuration);
        analysisOutputSectionButton.setSelected(analysisOutput);
        rocSectionButton.setSelected(rocCurve);
        styleSectionButton(configurationSectionButton, configuration);
        styleSectionButton(analysisOutputSectionButton, analysisOutput);
        styleSectionButton(rocSectionButton, rocCurve);
    }

    private static void styleSectionButton(JToggleButton button, boolean selected) {
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN));
        if (selected) {
            button.setBackground(new Color(199, 231, 255));
            button.setForeground(new Color(25, 31, 37));
            button.setBorder(BorderFactory.createLineBorder(new Color(61, 126, 174)));
        } else {
            button.setBackground(new Color(241, 243, 245));
            button.setForeground(new Color(67, 74, 82));
            button.setBorder(BorderFactory.createLineBorder(new Color(178, 184, 190)));
        }
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
        panel.add(createExportPanel());
        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createExportPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 5));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 82));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Data export"),
                BorderFactory.createEmptyBorder(3, 4, 5, 4)));
        JLabel label = new JLabel("Output parent folder");
        label.setForeground(new Color(69, 79, 90));
        panel.add(label, BorderLayout.NORTH);

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        outputDirectoryField.setToolTipText(
                "Parent directory. Export creates a scenario/date-time subfolder here.");
        row.add(outputDirectoryField, BorderLayout.CENTER);
        exportButton.setToolTipText("Write full Track Stitching Analysis CSV dump");
        row.add(exportButton, BorderLayout.EAST);
        panel.add(row, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createTimingAndAlternativePanel() {
        JPanel panel = new JPanel(new BorderLayout(12, 0));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 154));

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

        JPanel alternativeColumn = new JPanel();
        alternativeColumn.setLayout(new BoxLayout(alternativeColumn, BoxLayout.Y_AXIS));
        alternativeColumn.setOpaque(false);
        alternativeColumn.add(createAlternativeHypothesisPanel());
        alternativeColumn.add(Box.createVerticalStrut(6));
        alternativeColumn.add(createBridgeNllrPanel());

        panel.add(timingPanel, BorderLayout.CENTER);
        panel.add(alternativeColumn, BorderLayout.EAST);
        return panel;
    }

    private JPanel createAlternativeHypothesisPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 5, 2));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setPreferredSize(new Dimension(244, 66));
        panel.setMinimumSize(new Dimension(244, 66));
        panel.setMaximumSize(new Dimension(252, 70));
        javax.swing.border.TitledBorder titleBorder =
                BorderFactory.createTitledBorder("Alternative Hypothesis");
        titleBorder.setTitleFont(titleBorder.getTitleFont().deriveFont(Font.PLAIN, 11f));
        panel.setBorder(BorderFactory.createCompoundBorder(
                titleBorder,
                BorderFactory.createEmptyBorder(0, 4, 3, 4)));
        falseAlarmRateField.setToolTipText("Expected false alarms per 1 km^3 innovation volume");
        birthRateField.setToolTipText("Expected target births per 1 km^3 innovation volume");
        falseAlarmRateField.setColumns(7);
        birthRateField.setColumns(7);
        panel.add(compactLabel("False alarms / km^3"));
        panel.add(falseAlarmRateField);
        panel.add(compactLabel("Target births / km^3"));
        panel.add(birthRateField);
        return panel;
    }

    private JPanel createBridgeNllrPanel() {
        JPanel panel = new JPanel(new GridLayout(0, 2, 5, 2));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setPreferredSize(new Dimension(244, 44));
        panel.setMinimumSize(new Dimension(244, 44));
        panel.setMaximumSize(new Dimension(252, 48));
        javax.swing.border.TitledBorder titleBorder =
                BorderFactory.createTitledBorder("Bridge NLLR");
        titleBorder.setTitleFont(titleBorder.getTitleFont().deriveFont(Font.PLAIN, 11f));
        panel.setBorder(BorderFactory.createCompoundBorder(
                titleBorder,
                BorderFactory.createEmptyBorder(0, 4, 3, 4)));
        userNllrVolumeField.setToolTipText(
                "Uniform residual volume in km^3 for the user-volume NLLR column");
        userNllrVolumeField.setColumns(7);
        panel.add(compactLabel("User volume km^3"));
        panel.add(userNllrVolumeField);
        return panel;
    }

    private static JLabel compactLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.PLAIN, 11f));
        return label;
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
        outputTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        outputTabs.addTab("Min log-likelihood", emptyTabBody());
        outputTabs.addTab("Gaussian overlap 3D", emptyTabBody());
        outputTabs.addTab("Gaussian overlap 6D", emptyTabBody());
        outputTabs.addTab("Feasibility", emptyTabBody());
        outputTabs.addTab("NLLR", emptyTabBody());
        outputTabs.setSelectedIndex(0);
        outputTabs.setPreferredSize(new Dimension(0, 32));
        metricsPanel.add(outputTabs, BorderLayout.NORTH);
        JScrollPane metricsScroll = new JScrollPane(
                metricsTable,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        metricsScroll.setBorder(BorderFactory.createTitledBorder(
                "Join time and Gaussian-overlap metrics"));
        metricsPanel.add(metricsScroll, BorderLayout.CENTER);
        panel.add(metricsPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createRocCurvePanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createTitledBorder("ROC curve"));
        rocTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        rocTabs.addTab("ROC", messagePanel("Click ROC Curve to compute 3D Hellinger ROC curves."));
        panel.add(rocTabs, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel messagePanel(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(250, 251, 252));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        JLabel label = new JLabel(text);
        label.setForeground(new Color(80, 92, 104));
        panel.add(label, BorderLayout.NORTH);
        return panel;
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
        metricsTable.setDefaultRenderer(Boolean.class, new MetricToggleRenderer());
        metricsTable.getTableHeader().setReorderingAllowed(false);
        metricsTable.getTableHeader().setFont(
                metricsTable.getTableHeader().getFont().deriveFont(Font.BOLD, 11.0f));
        configureMetricColumnWidths();
        metricsModel.addTableModelListener(event -> {
            if (populatingMetricsTable
                    || event.getType() != TableModelEvent.UPDATE
                    || !isOverlayColumn(event.getColumn())) {
                return;
            }
            updateStitchingOverlays();
        });
    }

    private void configureMetricColumnWidths() {
        int columnCount = metricsTable.getColumnModel().getColumnCount();
        if (columnCount < 6) {
            return;
        }
        metricsTable.getColumnModel().getColumn(0).setPreferredWidth(82);
        metricsTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        for (int column = 2; column < columnCount - 2; column++) {
            metricsTable.getColumnModel().getColumn(column).setPreferredWidth(104);
        }
        metricsTable.getColumnModel().getColumn(columnCount - 2).setPreferredWidth(46);
        metricsTable.getColumnModel().getColumn(columnCount - 1).setPreferredWidth(46);
    }

    private boolean isOverlayColumn(int column) {
        return column >= 0
                && column < metricsModel.getColumnCount()
                && ("State".equals(metricsModel.getColumnName(column))
                || "Poly".equals(metricsModel.getColumnName(column)));
    }

    private int stateColumnIndex() {
        return Math.max(0, metricsModel.getColumnCount() - 2);
    }

    private int polyColumnIndex() {
        return Math.max(0, metricsModel.getColumnCount() - 1);
    }

    private TrackStitchingAnalyzer.Configuration readConfiguration() {
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
            throw exception;
        }
        final double falseAlarmRate;
        final double birthRate;
        final double userNllrVolume;
        try {
            falseAlarmRate = parseNonNegative(falseAlarmRateField);
            birthRate = parseNonNegative(birthRateField);
        } catch (NumberFormatException exception) {
            statusLabel.setText("Enter non-negative alternative-hypothesis densities");
            throw exception;
        }
        try {
            userNllrVolume = parsePositive(userNllrVolumeField);
        } catch (NumberFormatException exception) {
            statusLabel.setText("Enter a positive user NLLR volume");
            throw exception;
        }
        return new TrackStitchingAnalyzer.Configuration(
                coastedWindow.minimumSeconds(),
                coastedWindow.maximumSeconds(),
                newWindow.minimumSeconds(),
                newWindow.maximumSeconds(),
                allowDeadTracks.isSelected(),
                resolution,
                falseAlarmRate,
                birthRate,
                50.0,
                1.0,
                userNllrVolume);
    }

    private void runAnalysis() {
        final TrackStitchingAnalyzer.Configuration configuration;
        try {
            configuration = readConfiguration();
        } catch (NumberFormatException exception) {
            return;
        }
        latestConfiguration = configuration;
        analyzeButton.setEnabled(false);
        rocButton.setEnabled(false);
        exportButton.setEnabled(false);
        statusLabel.setText("Analyzing measurement times...");
        eventTabs.removeAll();
        eventTabs.addTab("Analyzing...", emptyTabBody());
        timelinePanel.clearCandidateMarkers();
        mapCanvas.clearStitchingFocus();
        clearOutput();

        SwingWorker<TrackStitchingAnalyzer.AnalysisResult, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected TrackStitchingAnalyzer.AnalysisResult doInBackground() {
                        return analyzer.analyzeDetailed(scenario, configuration);
                    }

                    @Override
                    protected void done() {
                        if (!active) {
                            return;
                        }
                        analyzeButton.setEnabled(true);
                        rocButton.setEnabled(true);
                        try {
                            analysisResult = get();
                            events = analysisResult.events();
                            populateEventTabs();
                            statusLabel.setText(events.size() + " candidate timestamp(s)");
                            exportButton.setEnabled(true);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            statusLabel.setText("Analysis interrupted");
                        } catch (ExecutionException exception) {
                            statusLabel.setText("Analysis failed: "
                                    + exception.getCause().getMessage());
                            events = List.of();
                            analysisResult = new TrackStitchingAnalyzer.AnalysisResult(
                                    List.of(), List.of());
                            populateEventTabs();
                        }
                    }
        };
        worker.execute();
    }

    private void runRocCurve() {
        final TrackStitchingAnalyzer.Configuration configuration;
        try {
            configuration = readConfiguration();
        } catch (NumberFormatException exception) {
            return;
        }
        showSection(ROC_CURVE_SECTION);
        final boolean exportWasEnabled = exportButton.isEnabled();
        analyzeButton.setEnabled(false);
        rocButton.setEnabled(false);
        exportButton.setEnabled(false);
        statusLabel.setText("Computing ROC curve...");
        rocTabs.removeAll();
        rocTabs.addTab("Computing...", messagePanel("Computing 3D Hellinger ROC curves..."));

        SwingWorker<RocCurveResult, Void> worker = new SwingWorker<>() {
            @Override
            protected RocCurveResult doInBackground() {
                TrackStitchingAnalyzer.AnalysisResult result =
                        analyzer.analyzeDetailed(scenario, configuration);
                return buildRocCurve(result);
            }

            @Override
            protected void done() {
                if (!active) {
                    return;
                }
                analyzeButton.setEnabled(true);
                rocButton.setEnabled(true);
                exportButton.setEnabled(exportWasEnabled);
                try {
                    RocCurveResult result = get();
                    populateRocTabs(result);
                    statusLabel.setText("ROC curve ready: "
                            + result.candidateCount()
                            + " candidate stitch attempt(s)");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("ROC computation interrupted");
                } catch (ExecutionException exception) {
                    rocTabs.removeAll();
                    rocTabs.addTab("ROC", messagePanel("ROC computation failed."));
                    statusLabel.setText("ROC computation failed: "
                            + exception.getCause().getMessage());
                }
            }
        };
        worker.execute();
    }

    private RocCurveResult buildRocCurve(
            TrackStitchingAnalyzer.AnalysisResult result) {
        Map<String, String> targetByTrack = trackTargetIds();
        List<RocExample> examples = new ArrayList<>();
        for (TrackStitchingAnalyzer.EventResult event : result.events()) {
            for (TrackStitchingAnalyzer.PairResult pair : event.pairs()) {
                String oldTargetId = targetByTrack.get(pair.oldTrackId());
                String newTargetId = targetByTrack.get(pair.newTrackId());
                if (!validTargetId(oldTargetId) || !validTargetId(newTargetId)) {
                    continue;
                }
                double hellingerDistance = rocHellingerDistance(pair);
                if (Double.isFinite(hellingerDistance)) {
                    examples.add(new RocExample(
                            pair.oldTrackId(),
                            pair.newTrackId(),
                            oldTargetId,
                            newTargetId,
                            Math.max(0.0, Math.min(1.0, hellingerDistance))));
                }
            }
        }
        return rocCurve("All targets", examples);
    }

    private Map<String, String> trackTargetIds() {
        Map<String, String> targetByTrack = new LinkedHashMap<>();
        for (RecordedMeasurement measurement : scenario.measurements()) {
            String trackId = measurement.associatedTrackId();
            String targetId = measurement.targetId();
            if (trackId.isBlank() || targetId.isBlank()) {
                continue;
            }
            String existingTargetId = targetByTrack.get(trackId);
            if (existingTargetId == null) {
                targetByTrack.put(trackId, targetId);
            } else if (!existingTargetId.equals(targetId)) {
                targetByTrack.put(trackId, "");
            }
        }
        return targetByTrack;
    }

    private static boolean validTargetId(String targetId) {
        return targetId != null && !targetId.isBlank();
    }

    private static double rocHellingerDistance(TrackStitchingAnalyzer.PairResult pair) {
        return pair.statisticalHellingerDistance();
    }

    private static RocCurveResult rocCurve(String label, List<RocExample> examples) {
        int positives = 0;
        for (RocExample example : examples) {
            if (example.sameTarget()) {
                positives++;
            }
        }
        int negatives = examples.size() - positives;
        List<RocPoint> points = new ArrayList<>();
        for (int index = 0; index <= 100; index++) {
            double threshold = index / 100.0;
            int truePositives = 0;
            int falsePositives = 0;
            for (RocExample example : examples) {
                if (example.hellingerDistance() <= threshold) {
                    if (example.sameTarget()) {
                        truePositives++;
                    } else {
                        falsePositives++;
                    }
                }
            }
            double truePositiveRate = positives == 0
                    ? Double.NaN
                    : truePositives / (double) positives;
            double falsePositiveRate = negatives == 0
                    ? Double.NaN
                    : falsePositives / (double) negatives;
            points.add(new RocPoint(threshold, truePositiveRate, falsePositiveRate));
        }
        return new RocCurveResult(
                label,
                List.copyOf(points),
                positives,
                negatives,
                examples.size(),
                positives == 0 || negatives == 0 ? Double.NaN : rocAuc(points));
    }

    private static double rocAuc(List<RocPoint> points) {
        double area = 0.0;
        RocPoint previous = null;
        for (RocPoint point : points) {
            if (!Double.isFinite(point.falsePositiveRate())
                    || !Double.isFinite(point.truePositiveRate())) {
                continue;
            }
            if (previous != null) {
                double width = point.falsePositiveRate() - previous.falsePositiveRate();
                double height = (point.truePositiveRate() + previous.truePositiveRate()) * 0.5;
                area += width * height;
            }
            previous = point;
        }
        return Math.max(0.0, Math.min(1.0, area));
    }

    private void populateRocTabs(RocCurveResult result) {
        rocTabs.removeAll();
        rocTabs.addTab("ROC", new RocCurveChart(result));
        rocTabs.setSelectedIndex(0);
    }

    private void exportAnalysis() {
        if (latestConfiguration == null) {
            statusLabel.setText("Run stitching analysis before exporting");
            return;
        }
        final Path parentDirectory;
        try {
            parentDirectory = Paths.get(outputDirectoryField.getText().trim())
                    .toAbsolutePath()
                    .normalize();
            outputDirectoryField.setBackground(Color.WHITE);
        } catch (RuntimeException exception) {
            outputDirectoryField.setBackground(new Color(255, 224, 224));
            statusLabel.setText("Enter a valid output folder");
            return;
        }

        exportButton.setEnabled(false);
        analyzeButton.setEnabled(false);
        rocButton.setEnabled(false);
        statusLabel.setText("Exporting stitching analysis data...");
        SwingWorker<Path, Void> worker = new SwingWorker<>() {
            @Override
            protected Path doInBackground() throws IOException {
                return exporter.export(
                        scenario,
                        analysisResult,
                        latestConfiguration,
                        parentDirectory);
            }

            @Override
            protected void done() {
                if (!active) {
                    return;
                }
                analyzeButton.setEnabled(true);
                rocButton.setEnabled(true);
                exportButton.setEnabled(true);
                try {
                    Path output = get();
                    statusLabel.setText("Exported to " + output);
                    statusConsumer.accept("Track stitching data exported to " + output);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    statusLabel.setText("Export interrupted");
                } catch (ExecutionException exception) {
                    statusLabel.setText("Export failed: "
                            + exception.getCause().getMessage());
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

    private static double parsePositive(JTextField field) {
        try {
            double value = Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(value) || value <= 0.0) {
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
        return switch (outputTabs.getSelectedIndex()) {
            case 1 -> ScoreMode.OVERLAP_3D;
            case 2 -> ScoreMode.OVERLAP_6D;
            case 3 -> ScoreMode.FEASIBILITY;
            case 4 -> ScoreMode.ALTERNATIVE;
            default -> ScoreMode.MINIMUM_NLL;
        };
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
        mapCanvas.setStitchingOverlays(List.of());
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
                .append(formatScientific(event.learnedBirthDensityPerCubicKilometer()))
                .append(" births / km^3\n\n");
        switch (mode) {
            case MINIMUM_NLL -> appendAssignments(text, "Minimum NLL optimum",
                    event.minimumNllAssignments());
            case OVERLAP_3D -> {
                appendAssignments(text, "3D Bhattacharyya Distance optimum",
                        event.bhattacharyyaDistanceAssignments());
                appendAssignments(text, "3D Bhattacharyya Coefficient optimum",
                        event.bhattacharyyaCoefficientAssignments());
                appendAssignments(text, "3D Hellinger Distance optimum",
                        event.hellingerDistanceAssignments());
            }
            case OVERLAP_6D -> {
                appendAssignments(text, "6D Bhattacharyya Distance optimum",
                        event.sixDimensionalBhattacharyyaDistanceAssignments());
                appendAssignments(text, "6D Bhattacharyya Coefficient optimum",
                        event.sixDimensionalBhattacharyyaCoefficientAssignments());
                appendAssignments(text, "6D Hellinger Distance optimum",
                        event.sixDimensionalHellingerDistanceAssignments());
            }
            case FEASIBILITY -> {
                appendAssignments(text, "NLL optimum", event.nllAssignments());
                appendAssignments(text, "Mahalanobis optimum", event.mahalanobisAssignments());
            }
            case ALTERNATIVE -> {
                appendAssignments(text, "Bridge-volume NLLR optimum",
                        event.bridgeNllrAssignments());
                appendAssignments(text, "User-volume NLLR optimum",
                        event.userVolumeNllrAssignments());
            }
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
        populatingMetricsTable = true;
        metricsModel.setRowCount(0);
        ScoreMode mode = scoreMode();
        switch (mode) {
            case MINIMUM_NLL -> metricsModel.setColumnIdentifiers(new Object[]{
                    "Pair", "Time of min log-likelihood", "NLL",
                    "NLLR", "NLLR-static", "State", "Poly"});
            case OVERLAP_3D, OVERLAP_6D -> metricsModel.setColumnIdentifiers(new Object[]{
                    "Pair", "Estimated join time", "Bhattacharyya Distance",
                    "Bhattacharyya Coefficient", "Hellinger Distance", "State", "Poly"});
            case FEASIBILITY -> metricsModel.setColumnIdentifiers(new Object[]{
                    "Pair", "Estimated join time", "NLL", "Mahalanobis", "State", "Poly"});
            case ALTERNATIVE -> metricsModel.setColumnIdentifiers(new Object[]{
                    "Pair", "Time of min NLLR", "Bridge-volume NLLR",
                    "User-volume NLLR", "State", "Poly"});
        }
        configureMetricColumnWidths();
        List<MetricRow> rows = new ArrayList<>();
        for (TrackStitchingAnalyzer.PairResult pair : event.pairs()) {
            String pairName = pair.oldTrackId() + " -> " + pair.newTrackId();
            if (mode == ScoreMode.MINIMUM_NLL) {
                rows.add(addMetricRow(
                        event,
                        pair,
                        pairName,
                        "Minimum NLL bank",
                        formatTime(pair.minimumNllTimeSeconds()),
                        pair.minimumNegativeLogLikelihood(),
                        pair.minimumNllBridgeNegativeLogLikelihoodRatio(),
                        pair.minimumNllUserVolumeNegativeLogLikelihoodRatio()));
                continue;
            }
            if (mode == ScoreMode.ALTERNATIVE) {
                rows.add(addMetricRow(
                        event,
                        pair,
                        pairName,
                        "Bridge NLLR bank",
                        formatTime(pair.bridgeNllrTimeSeconds()),
                        pair.bridgeNegativeLogLikelihoodRatio(),
                        pair.userVolumeNegativeLogLikelihoodRatio(),
                        Double.NaN));
                continue;
            }
            rows.add(addMetricRow(
                    event,
                    pair,
                    pairName,
                    "Simple midpoint",
                    "Simple midpoint @ " + formatTime(pair.simpleJoinTimeSeconds()),
                    firstMetric(pair, "Simple midpoint", mode),
                    secondMetric(pair, "Simple midpoint", mode),
                    thirdMetric(pair, "Simple midpoint", mode)));
            rows.add(addMetricRow(
                    event,
                    pair,
                    pairName,
                    "Kinematic midpoint",
                    "Kinematic midpoint @ " + formatTime(pair.kinematicJoinTimeSeconds()),
                    firstMetric(pair, "Kinematic midpoint", mode),
                    secondMetric(pair, "Kinematic midpoint", mode),
                    thirdMetric(pair, "Kinematic midpoint", mode)));
            rows.add(addMetricRow(
                    event,
                    pair,
                    pairName,
                    "Mahalanobis bank",
                    "Mahalanobis bank @ " + formatTime(pair.statisticalJoinTimeSeconds()),
                    firstMetric(pair, "Mahalanobis bank", mode),
                    secondMetric(pair, "Mahalanobis bank", mode),
                    thirdMetric(pair, "Mahalanobis bank", mode)));
            rows.add(addMetricRow(
                    event,
                    pair,
                    pairName,
                    "Truth RMS",
                    "Truth RMS @ " + formatTime(pair.actualJoinTimeSeconds()),
                    firstMetric(pair, "Truth RMS", mode),
                    secondMetric(pair, "Truth RMS", mode),
                    thirdMetric(pair, "Truth RMS", mode)));
        }
        metricRows = List.copyOf(rows);
        populatingMetricsTable = false;
        updateStitchingOverlays();
    }

    private MetricRow addMetricRow(
            TrackStitchingAnalyzer.EventResult event,
            TrackStitchingAnalyzer.PairResult pair,
            String pairName,
            String variant,
            String estimate,
            double firstMetric,
            double secondMetric,
            double thirdMetric) {
        ScoreMode mode = scoreMode();
        if (isOverlapMode(mode)) {
            metricsModel.addRow(new Object[]{
                    pairName,
                    estimate,
                    formatNumber(firstMetric),
                    formatNumber(secondMetric),
                    formatNumber(thirdMetric),
                    false,
                    false
            });
        } else if (mode == ScoreMode.MINIMUM_NLL) {
            metricsModel.addRow(new Object[]{
                    pairName,
                    estimate,
                    formatNumber(firstMetric),
                    formatNumber(secondMetric),
                    formatNumber(thirdMetric),
                    false,
                    false
            });
        } else {
            metricsModel.addRow(new Object[]{
                    pairName,
                    estimate,
                    formatNumber(firstMetric),
                    formatNumber(secondMetric),
                    false,
                    false
            });
        }
        return new MetricRow(
                pairName,
                variant,
                pair,
                diagnosticsFor(event, pair),
                segmentFor(event.oldSegments(), pair.oldTrackId()),
                segmentFor(event.newSegments(), pair.newTrackId()),
                isAssigned(firstAssignments(event, mode), pair, variant),
                isAssigned(secondAssignments(event, mode), pair, variant),
                isAssigned(thirdAssignments(event, mode), pair, variant));
    }

    private static double firstMetric(
            TrackStitchingAnalyzer.PairResult pair,
            String variant,
            ScoreMode mode) {
        return switch (mode) {
            case MINIMUM_NLL -> pair.minimumNegativeLogLikelihood();
            case OVERLAP_3D -> switch (variant) {
                case "Simple midpoint" -> pair.simpleBhattacharyyaDistance();
                case "Kinematic midpoint" -> pair.kinematicBhattacharyyaDistance();
                case "Mahalanobis bank" -> pair.statisticalBhattacharyyaDistance();
                default -> pair.actualBhattacharyyaDistance();
            };
            case OVERLAP_6D -> switch (variant) {
                case "Simple midpoint" -> pair.simpleBhattacharyyaDistance6d();
                case "Kinematic midpoint" -> pair.kinematicBhattacharyyaDistance6d();
                case "Mahalanobis bank" -> pair.statisticalBhattacharyyaDistance6d();
                default -> pair.actualBhattacharyyaDistance6d();
            };
            case FEASIBILITY -> switch (variant) {
                case "Simple midpoint" -> pair.simpleNegativeLogLikelihood();
                case "Kinematic midpoint" -> pair.kinematicNegativeLogLikelihood();
                case "Mahalanobis bank" -> pair.statisticalNegativeLogLikelihood();
                default -> pair.actualNegativeLogLikelihood();
            };
            case ALTERNATIVE -> pair.bridgeNegativeLogLikelihoodRatio();
        };
    }

    private static double secondMetric(
            TrackStitchingAnalyzer.PairResult pair,
            String variant,
            ScoreMode mode) {
        return switch (mode) {
            case MINIMUM_NLL -> pair.minimumNllBridgeNegativeLogLikelihoodRatio();
            case OVERLAP_3D -> switch (variant) {
                case "Simple midpoint" -> pair.simpleBhattacharyyaCoefficient();
                case "Kinematic midpoint" -> pair.kinematicBhattacharyyaCoefficient();
                case "Mahalanobis bank" -> pair.statisticalBhattacharyyaCoefficient();
                default -> pair.actualBhattacharyyaCoefficient();
            };
            case OVERLAP_6D -> switch (variant) {
                case "Simple midpoint" -> pair.simpleBhattacharyyaCoefficient6d();
                case "Kinematic midpoint" -> pair.kinematicBhattacharyyaCoefficient6d();
                case "Mahalanobis bank" -> pair.statisticalBhattacharyyaCoefficient6d();
                default -> pair.actualBhattacharyyaCoefficient6d();
            };
            case FEASIBILITY -> switch (variant) {
                case "Simple midpoint" -> pair.simpleMahalanobisDistance();
                case "Kinematic midpoint" -> pair.kinematicMahalanobisDistance();
                case "Mahalanobis bank" -> pair.statisticalMahalanobisDistance();
                default -> pair.actualMahalanobisDistance();
            };
            case ALTERNATIVE -> pair.userVolumeNegativeLogLikelihoodRatio();
        };
    }

    private static double thirdMetric(
            TrackStitchingAnalyzer.PairResult pair,
            String variant,
            ScoreMode mode) {
        if (mode == ScoreMode.MINIMUM_NLL) {
            return pair.minimumNllUserVolumeNegativeLogLikelihoodRatio();
        }
        if (!isOverlapMode(mode)) {
            return Double.NaN;
        }
        return switch (mode) {
            case OVERLAP_3D -> switch (variant) {
                case "Simple midpoint" -> pair.simpleHellingerDistance();
                case "Kinematic midpoint" -> pair.kinematicHellingerDistance();
                case "Mahalanobis bank" -> pair.statisticalHellingerDistance();
                default -> pair.actualHellingerDistance();
            };
            case OVERLAP_6D -> switch (variant) {
                case "Simple midpoint" -> pair.simpleHellingerDistance6d();
                case "Kinematic midpoint" -> pair.kinematicHellingerDistance6d();
                case "Mahalanobis bank" -> pair.statisticalHellingerDistance6d();
                default -> pair.actualHellingerDistance6d();
            };
            default -> Double.NaN;
        };
    }

    private static boolean isOverlapMode(ScoreMode mode) {
        return mode == ScoreMode.OVERLAP_3D || mode == ScoreMode.OVERLAP_6D;
    }

    private static List<TrackStitchingAnalyzer.OptimalAssignment> firstAssignments(
            TrackStitchingAnalyzer.EventResult event,
            ScoreMode mode) {
        return switch (mode) {
            case MINIMUM_NLL -> event.minimumNllAssignments();
            case OVERLAP_3D -> event.bhattacharyyaDistanceAssignments();
            case OVERLAP_6D -> event.sixDimensionalBhattacharyyaDistanceAssignments();
            case FEASIBILITY -> event.nllAssignments();
            case ALTERNATIVE -> event.bridgeNllrAssignments();
        };
    }

    private static List<TrackStitchingAnalyzer.OptimalAssignment> secondAssignments(
            TrackStitchingAnalyzer.EventResult event,
            ScoreMode mode) {
        return switch (mode) {
            case MINIMUM_NLL -> List.of();
            case OVERLAP_3D -> event.bhattacharyyaCoefficientAssignments();
            case OVERLAP_6D -> event.sixDimensionalBhattacharyyaCoefficientAssignments();
            case FEASIBILITY -> event.mahalanobisAssignments();
            case ALTERNATIVE -> event.userVolumeNllrAssignments();
        };
    }

    private static List<TrackStitchingAnalyzer.OptimalAssignment> thirdAssignments(
            TrackStitchingAnalyzer.EventResult event,
            ScoreMode mode) {
        return switch (mode) {
            case OVERLAP_3D -> event.hellingerDistanceAssignments();
            case OVERLAP_6D -> event.sixDimensionalHellingerDistanceAssignments();
            default -> List.of();
        };
    }

    private void updateStitchingOverlays() {
        if (populatingMetricsTable || metricRows.isEmpty()) {
            mapCanvas.setStitchingOverlays(List.of());
            return;
        }
        List<EarthMapCanvas.StitchingOverlay> overlays = new ArrayList<>();
        int rowCount = Math.min(metricRows.size(), metricsModel.getRowCount());
        int stateColumn = stateColumnIndex();
        int polyColumn = polyColumnIndex();
        for (int row = 0; row < rowCount; row++) {
            boolean showStates = Boolean.TRUE.equals(metricsModel.getValueAt(row, stateColumn));
            boolean showPolynomial = Boolean.TRUE.equals(metricsModel.getValueAt(row, polyColumn));
            if (!showStates && !showPolynomial) {
                continue;
            }
            MetricRow metricRow = metricRows.get(row);
            List<EarthMapCanvas.StitchingStateOverlay> stateOverlays =
                    showStates ? stateOverlaysFor(metricRow) : List.of();
            List<EcefPoint> polynomial =
                    showPolynomial ? polynomialOverlayFor(metricRow) : List.of();
            if (!stateOverlays.isEmpty() || polynomial.size() >= 2) {
                overlays.add(new EarthMapCanvas.StitchingOverlay(stateOverlays, polynomial));
            }
        }
        mapCanvas.setStitchingOverlays(overlays);
    }

    private static List<EarthMapCanvas.StitchingStateOverlay> stateOverlaysFor(
            MetricRow row) {
        if (row.diagnostics() == null) {
            return List.of();
        }
        List<EarthMapCanvas.StitchingStateOverlay> overlays = new ArrayList<>();
        if ("Mahalanobis bank".equals(row.variant())) {
            for (TrackStitchingAnalyzer.BankEvaluation evaluation
                    : row.diagnostics().bankEvaluations()) {
                overlays.add(stateOverlay(
                        evaluation.oldState(),
                        evaluation.oldCovariance(),
                        evaluation.newState(),
                        evaluation.newCovariance()));
            }
            return List.copyOf(overlays);
        }
        TrackStitchingAnalyzer.JoinEvaluation evaluation = joinEvaluationFor(row);
        if (evaluation == null || !Double.isFinite(evaluation.timeSeconds())) {
            return List.of();
        }
        overlays.add(stateOverlay(
                evaluation.oldState(),
                evaluation.oldCovariance(),
                evaluation.newState(),
                evaluation.newCovariance()));
        return List.copyOf(overlays);
    }

    private static EarthMapCanvas.StitchingStateOverlay stateOverlay(
            double[] oldState,
            double[][] oldCovariance,
            double[] newState,
            double[][] newCovariance) {
        return new EarthMapCanvas.StitchingStateOverlay(
                pointFromState(oldState),
                positionCovariance(oldCovariance),
                pointFromState(newState),
                positionCovariance(newCovariance));
    }

    private static List<EcefPoint> polynomialOverlayFor(MetricRow row) {
        if (row.oldSegment() == null || row.newSegment() == null) {
            return List.of();
        }
        TrackStitchingAnalyzer.JoinEvaluation evaluation = joinEvaluationFor(row);
        if (evaluation == null || !Double.isFinite(evaluation.timeSeconds())) {
            return List.of();
        }
        TrackRecord oldHead = row.oldSegment().lastUpdateRecord();
        TrackRecord newTail = row.newSegment().formationRecord();
        double oldTime = oldHead.timeSeconds();
        double joinTime = evaluation.timeSeconds();
        double newTime = newTail.timeSeconds();
        if (!(oldTime < joinTime && joinTime < newTime)) {
            return List.of();
        }
        double[] joinPosition = averagePosition(evaluation.oldState(), evaluation.newState());
        return quadraticInterpolation(
                oldTime,
                oldHead.state(),
                joinTime,
                joinPosition,
                newTime,
                newTail.state());
    }

    private static List<EcefPoint> quadraticInterpolation(
            double time0,
            double[] point0,
            double time1,
            double[] point1,
            double time2,
            double[] point2) {
        List<EcefPoint> points = new ArrayList<>();
        int samples = 72;
        for (int sample = 0; sample <= samples; sample++) {
            double time = time0 + (time2 - time0) * sample / samples;
            double l0 = (time - time1) * (time - time2)
                    / ((time0 - time1) * (time0 - time2));
            double l1 = (time - time0) * (time - time2)
                    / ((time1 - time0) * (time1 - time2));
            double l2 = (time - time0) * (time - time1)
                    / ((time2 - time0) * (time2 - time1));
            points.add(new EcefPoint(
                    l0 * point0[0] + l1 * point1[0] + l2 * point2[0],
                    l0 * point0[1] + l1 * point1[1] + l2 * point2[1],
                    l0 * point0[2] + l1 * point1[2] + l2 * point2[2]));
        }
        return List.copyOf(points);
    }

    private static double[] averagePosition(double[] oldState, double[] newState) {
        return new double[]{
                (oldState[0] + newState[0]) / 2.0,
                (oldState[1] + newState[1]) / 2.0,
                (oldState[2] + newState[2]) / 2.0};
    }

    private static EcefPoint pointFromState(double[] state) {
        return new EcefPoint(state[0], state[1], state[2]);
    }

    private static double[][] positionCovariance(double[][] covariance) {
        double[][] position = new double[3][3];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(covariance[row], 0, position[row], 0, 3);
        }
        return position;
    }

    private static TrackStitchingAnalyzer.JoinEvaluation joinEvaluationFor(MetricRow row) {
        if (row.diagnostics() == null) {
            return null;
        }
        return row.diagnostics().joinEvaluations().stream()
                .filter(evaluation -> evaluation.variant().equals(row.variant()))
                .findFirst()
                .orElse(null);
    }

    private static TrackStitchingAnalyzer.PairDiagnostics diagnosticsFor(
            TrackStitchingAnalyzer.EventResult event,
            TrackStitchingAnalyzer.PairResult pair) {
        return event.diagnostics().stream()
                .filter(diagnostics ->
                        diagnostics.result().oldTrackId().equals(pair.oldTrackId())
                                && diagnostics.result().newTrackId().equals(pair.newTrackId()))
                .findFirst()
                .orElse(null);
    }

    private static TrackStitchingAnalyzer.Segment segmentFor(
            List<TrackStitchingAnalyzer.Segment> segments,
            String trackId) {
        return segments.stream()
                .filter(segment -> segment.trackId().equals(trackId))
                .findFirst()
                .orElse(null);
    }

    private static boolean isAssigned(
            List<TrackStitchingAnalyzer.OptimalAssignment> assignments,
            TrackStitchingAnalyzer.PairResult pair,
            String variant) {
        return assignments.stream().anyMatch(assignment ->
                assignment.oldTrackId().equals(pair.oldTrackId())
                        && assignment.newTrackId().equals(pair.newTrackId())
                        && (assignment.variant().equals(variant)
                        || ("Bridge NLLR bank".equals(variant)
                        && "User volume NLLR bank".equals(assignment.variant()))));
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
                component.setBackground(metricRowBackground(metricRow));
                component.setForeground(new Color(25, 31, 37));
            }
            component.setFont(component.getFont().deriveFont(
                    column == 0 ? Font.BOLD : Font.PLAIN));
            setBorder(metricGroupBorder(row, column));
            return component;
        }
    }

    private final class MetricToggleRenderer extends JCheckBox implements TableCellRenderer {
        MetricToggleRenderer() {
            setHorizontalAlignment(CENTER);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(
                JTable table,
                Object value,
                boolean isSelected,
                boolean hasFocus,
                int row,
                int column) {
            setSelected(Boolean.TRUE.equals(value));
            if (row >= 0 && row < metricRows.size()) {
                setBackground(isSelected
                        ? table.getSelectionBackground()
                        : metricRowBackground(metricRows.get(row)));
                setBorder(metricGroupBorder(row, column));
            }
            return this;
        }
    }

    private Color metricRowBackground(MetricRow row) {
        int optimumCount = 0;
        if (row.firstOptimal()) {
            optimumCount++;
        }
        if (row.secondOptimal()) {
            optimumCount++;
        }
        if (row.thirdOptimal()) {
            optimumCount++;
        }
        if (optimumCount >= 2) {
            return new Color(220, 246, 225);
        }
        if (row.firstOptimal()) {
            return new Color(255, 244, 204);
        }
        if (row.secondOptimal()) {
            return new Color(220, 239, 255);
        }
        if (row.thirdOptimal()) {
            return new Color(235, 226, 255);
        }
        return Color.WHITE;
    }

    private Border metricGroupBorder(int row, int column) {
        boolean first = row == 0 || !metricRows.get(row).pairName()
                .equals(metricRows.get(row - 1).pairName());
        boolean last = row == metricRows.size() - 1 || !metricRows.get(row).pairName()
                .equals(metricRows.get(row + 1).pairName());
        int top = first ? 2 : 0;
        int bottom = last ? 2 : 0;
        int left = column == 0 ? 2 : 0;
        int right = column == metricsTable.getColumnCount() - 1 ? 2 : 0;
        if (top == 0 && bottom == 0 && left == 0 && right == 0) {
            return metricEmptyBorder;
        }
        return BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(
                        top, left, bottom, right, new Color(48, 56, 64)),
                metricEmptyBorder);
    }

    private static final class RocCurveChart extends JPanel {
        private final RocCurveResult result;

        private RocCurveChart(RocCurveResult result) {
            this.result = result;
            setBackground(Color.WHITE);
            setPreferredSize(new Dimension(420, 360));
            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                paintChart(g2);
            } finally {
                g2.dispose();
            }
        }

        private void paintChart(Graphics2D g2) {
            int width = getWidth();
            int height = getHeight();
            int left = 58;
            int top = 58;
            int right = 24;
            int bottom = 54;
            int plotWidth = Math.max(1, width - left - right);
            int plotHeight = Math.max(1, height - top - bottom);
            int x0 = left;
            int y0 = top + plotHeight;

            g2.setColor(new Color(25, 31, 37));
            g2.setFont(getFont().deriveFont(Font.BOLD, 14.0f));
            g2.drawString(
                    result.label() + "  3D Hellinger ROC (Mahalanobis bank)",
                    left,
                    24);
            g2.setFont(getFont().deriveFont(Font.PLAIN, 11.0f));
            String auc = Double.isFinite(result.auc()) ? formatNumber(result.auc()) : "-";
            g2.drawString(
                    "Candidates: %d   same target: %d   different target: %d   AUC: %s"
                            .formatted(
                                    result.candidateCount(),
                                    result.positiveCount(),
                                    result.negativeCount(),
                                    auc),
                    left,
                    42);

            g2.setColor(new Color(236, 240, 244));
            g2.fillRect(x0, top, plotWidth, plotHeight);
            g2.setColor(new Color(142, 151, 160));
            for (int tick = 0; tick <= 5; tick++) {
                int x = x0 + Math.round(plotWidth * tick / 5.0f);
                int y = y0 - Math.round(plotHeight * tick / 5.0f);
                g2.drawLine(x, y0, x, y0 + 4);
                g2.drawLine(x0 - 4, y, x0, y);
                String label = formatNumber(tick / 5.0);
                g2.drawString(label, x - 10, y0 + 19);
                g2.drawString(label, x0 - 40, y + 4);
            }
            g2.setColor(new Color(76, 86, 96));
            g2.drawLine(x0, y0, x0 + plotWidth, y0);
            g2.drawLine(x0, top, x0, y0);
            g2.drawString("False positive rate", x0 + plotWidth / 2 - 48, height - 15);
            g2.drawString("True positive rate", 12, top - 10);

            if (result.positiveCount() == 0 || result.negativeCount() == 0) {
                g2.setColor(new Color(94, 104, 114));
                g2.drawString(
                        "Need at least one same-target and one different-target attempt.",
                        x0 + 18,
                        top + plotHeight / 2);
                return;
            }

            g2.setStroke(new BasicStroke(1.0f));
            g2.setColor(new Color(155, 164, 173));
            g2.drawLine(x0, y0, x0 + plotWidth, top);

            g2.setStroke(new BasicStroke(2.4f));
            g2.setColor(new Color(28, 105, 174));
            int previousX = -1;
            int previousY = -1;
            for (RocPoint point : result.points()) {
                if (!Double.isFinite(point.falsePositiveRate())
                        || !Double.isFinite(point.truePositiveRate())) {
                    continue;
                }
                int x = x0 + (int) Math.round(point.falsePositiveRate() * plotWidth);
                int y = y0 - (int) Math.round(point.truePositiveRate() * plotHeight);
                if (previousX >= 0) {
                    g2.drawLine(previousX, previousY, x, y);
                }
                previousX = x;
                previousY = y;
            }
            if (previousX >= 0) {
                g2.fillOval(previousX - 3, previousY - 3, 6, 6);
            }
        }
    }

    private record RocCurveResult(
            String label,
            List<RocPoint> points,
            int positiveCount,
            int negativeCount,
            int candidateCount,
            double auc) {
    }

    private record RocPoint(
            double threshold,
            double truePositiveRate,
            double falsePositiveRate) {
    }

    private record RocExample(
            String oldTrackId,
            String newTrackId,
            String oldTargetId,
            String newTargetId,
            double hellingerDistance) {
        private boolean sameTarget() {
            return oldTargetId.equals(newTargetId);
        }
    }

    private record MetricRow(
            String pairName,
            String variant,
            TrackStitchingAnalyzer.PairResult pair,
            TrackStitchingAnalyzer.PairDiagnostics diagnostics,
            TrackStitchingAnalyzer.Segment oldSegment,
            TrackStitchingAnalyzer.Segment newSegment,
            boolean firstOptimal,
            boolean secondOptimal,
            boolean thirdOptimal) {
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
