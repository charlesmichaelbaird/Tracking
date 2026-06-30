package com.targettracker.ui;

import com.targettracker.analysis.TrackStitchingAnalyzer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/** Standalone inspector for the exact bank-time values used by stitching analysis. */
final class TrackStitchingAnalysisDetailsWindow {
    private static final int STATE_SIZE = 9;
    private static final int POSITION_SIZE = 3;

    private TrackStitchingAnalysisDetailsWindow() {
    }

    static void show(
            Component owner,
            List<TrackStitchingAnalyzer.EventResult> events) {
        JFrame frame = new JFrame("Track Stitching Analysis Values");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setContentPane(createContent(events));
        frame.setSize(new Dimension(1180, 780));
        frame.setLocationRelativeTo(owner);
        frame.setVisible(true);
    }

    static JComponent createContent(List<TrackStitchingAnalyzer.EventResult> events) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        if (events == null || events.isEmpty()) {
            panel.add(message("No stitching analysis values are available."), BorderLayout.CENTER);
            return panel;
        }

        JTabbedPane eventTabs = new JTabbedPane(JTabbedPane.TOP);
        eventTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        for (TrackStitchingAnalyzer.EventResult event : events) {
            eventTabs.addTab(formatTime(event.timeSeconds()), createEventPanel(event));
        }
        panel.add(eventTabs, BorderLayout.CENTER);
        return panel;
    }

    private static JComponent createEventPanel(
            TrackStitchingAnalyzer.EventResult event) {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.addTab("Tracks", createTrackTabs(event));
        tabs.addTab("Pairs", createPairTabs(event));
        return tabs;
    }

    private static JComponent createTrackTabs(
            TrackStitchingAnalyzer.EventResult event) {
        Map<String, TrackDetail> tracks = collectTracks(event);
        if (tracks.isEmpty()) {
            return message("No track states are available for this candidate time.");
        }
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        for (TrackDetail track : tracks.values()) {
            tabs.addTab(track.trackId(), createTrackPanel(track));
        }
        return tabs;
    }

    private static JComponent createPairTabs(
            TrackStitchingAnalyzer.EventResult event) {
        if (event.diagnostics().isEmpty()) {
            return message("No pair diagnostics are available for this candidate time.");
        }
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        for (TrackStitchingAnalyzer.PairDiagnostics diagnostics : event.diagnostics()) {
            tabs.addTab(pairLabel(diagnostics), createPairPanel(diagnostics));
        }
        return tabs;
    }

    private static JComponent createTrackPanel(TrackDetail track) {
        if (track.samples().isEmpty()) {
            JTextArea area = detailsArea();
            area.setText(trackSummary(track)
                    + "\nNo bank-time state samples were generated for this track.");
            area.setCaretPosition(0);
            return scroll(area);
        }
        return createSampleBrowser(
                track.samples(),
                TrackStitchingAnalysisDetailsWindow::stateSampleLabel,
                sample -> trackSampleDetail(track, sample));
    }

    private static JComponent createPairPanel(
            TrackStitchingAnalyzer.PairDiagnostics diagnostics) {
        if (diagnostics.bankEvaluations().isEmpty()) {
            JTextArea area = detailsArea();
            area.setText("Pair: " + pairLabel(diagnostics)
                    + "\n\nNo bank-time pair evaluations were generated.");
            area.setCaretPosition(0);
            return scroll(area);
        }
        return createSampleBrowser(
                diagnostics.bankEvaluations(),
                TrackStitchingAnalysisDetailsWindow::bankEvaluationLabel,
                evaluation -> pairEvaluationDetail(diagnostics, evaluation));
    }

    private static <T> JComponent createSampleBrowser(
            List<T> samples,
            Function<T, String> labeler,
            Function<T, String> formatter) {
        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (T sample : samples) {
            listModel.addElement(labeler.apply(sample));
        }
        JList<String> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JTextArea detail = detailsArea();
        list.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }
            int index = list.getSelectedIndex();
            if (index >= 0 && index < samples.size()) {
                detail.setText(formatter.apply(samples.get(index)));
                detail.setCaretPosition(0);
            }
        });
        if (!samples.isEmpty()) {
            list.setSelectedIndex(0);
        }

        JScrollPane listScroll = new JScrollPane(
                list,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        listScroll.setBorder(BorderFactory.createTitledBorder("Bank times"));
        listScroll.setPreferredSize(new Dimension(260, 0));

        JScrollPane detailScroll = scroll(detail);
        detailScroll.setBorder(BorderFactory.createTitledBorder("Values"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailScroll);
        split.setResizeWeight(0.0);
        split.setDividerLocation(280);
        return split;
    }

    private static Map<String, TrackDetail> collectTracks(
            TrackStitchingAnalyzer.EventResult event) {
        Map<String, TrackDetail> tracks = new LinkedHashMap<>();
        for (TrackStitchingAnalyzer.Segment segment : event.allSegments()) {
            tracks.putIfAbsent(segment.trackId(), new TrackDetail(segment.trackId(), segment));
        }
        for (TrackStitchingAnalyzer.PairDiagnostics diagnostics : event.diagnostics()) {
            String pair = pairLabel(diagnostics);
            for (TrackStitchingAnalyzer.BankEvaluation evaluation
                    : diagnostics.bankEvaluations()) {
                addStateSample(
                        tracks,
                        evaluation.oldTrackId(),
                        "old/coasted",
                        pair,
                        evaluation.timeSeconds(),
                        evaluation.oldState(),
                        evaluation.oldCovariance());
                addStateSample(
                        tracks,
                        evaluation.newTrackId(),
                        "new/retrodicted",
                        pair,
                        evaluation.timeSeconds(),
                        evaluation.newState(),
                        evaluation.newCovariance());
            }
        }
        return tracks;
    }

    private static void addStateSample(
            Map<String, TrackDetail> tracks,
            String trackId,
            String role,
            String pair,
            double timeSeconds,
            double[] state,
            double[][] covariance) {
        TrackDetail track = tracks.computeIfAbsent(
                trackId,
                id -> new TrackDetail(id, null));
        track.samples().add(new StateSample(
                timeSeconds,
                role,
                pair,
                state,
                covariance));
    }

    private static String trackSampleDetail(
            TrackDetail track,
            StateSample sample) {
        StringBuilder text = new StringBuilder();
        text.append(trackSummary(track)).append('\n');
        text.append("Bank time: ").append(formatTime(sample.timeSeconds()))
                .append(" (").append(formatPrecise(sample.timeSeconds())).append(" s)\n");
        text.append("Role: ").append(sample.role()).append('\n');
        text.append("Source pair: ").append(sample.sourcePair()).append("\n\n");
        text.append("State (9x1)\n");
        appendVector(text, sample.state(), STATE_SIZE);
        text.append('\n');
        text.append("Covariance (9x9)\n");
        appendMatrix(text, sample.covariance(), STATE_SIZE);
        return text.toString();
    }

    private static String pairEvaluationDetail(
            TrackStitchingAnalyzer.PairDiagnostics diagnostics,
            TrackStitchingAnalyzer.BankEvaluation evaluation) {
        StringBuilder text = new StringBuilder();
        text.append("Pair: ").append(pairLabel(diagnostics)).append('\n');
        text.append("Bank time: ").append(formatTime(evaluation.timeSeconds()))
                .append(" (").append(formatPrecise(evaluation.timeSeconds())).append(" s)\n\n");

        text.append("Innovation values\n");
        appendScalar(text, "Squared Mahalanobis distance", evaluation.innovationQuadratic());
        appendScalar(text, "Mahalanobis distance", evaluation.mahalanobisDistance());
        appendScalar(text, "Log det innovation covariance", evaluation.logDeterminant());
        appendScalar(text, "Negative log likelihood", evaluation.negativeLogLikelihood());
        appendScalar(text, "Physics-aware NLL", evaluation.physicsAwareNegativeLogLikelihood());
        appendScalar(text, "Physics-aware opportunity cost",
                evaluation.physicsAwareOpportunityCost());
        appendScalar(text, "Physics-aware cost C_ijl", evaluation.physicsAwareCost());
        text.append('\n');

        text.append("Innovation (3x1)\n");
        appendVector(text, evaluation.innovation(), POSITION_SIZE);
        text.append('\n');
        text.append("Base innovation covariance (3x3)\n");
        appendMatrix(text, evaluation.innovationCovariance(), POSITION_SIZE);
        text.append('\n');
        text.append("Physics-aware innovation covariance with P_floor/scale (3x3)\n");
        appendMatrix(text, evaluation.physicsAwareInnovationCovariance(), POSITION_SIZE);
        text.append('\n');

        text.append("Old track state (9x1)\n");
        appendVector(text, evaluation.oldState(), STATE_SIZE);
        text.append('\n');
        text.append("Old track covariance (9x9)\n");
        appendMatrix(text, evaluation.oldCovariance(), STATE_SIZE);
        text.append('\n');

        text.append("New track state (9x1)\n");
        appendVector(text, evaluation.newState(), STATE_SIZE);
        text.append('\n');
        text.append("New track covariance (9x9)\n");
        appendMatrix(text, evaluation.newCovariance(), STATE_SIZE);
        text.append('\n');

        text.append("Other scalar diagnostics\n");
        appendScalar(text, "Physics-aware gap seconds", evaluation.physicsAwareGapSeconds());
        appendScalar(text, "Physics-aware bridge geometry log det",
                evaluation.physicsAwareBridgeGeometryLogDeterminant());
        appendScalar(text, "Physics-aware volume", evaluation.physicsAwareVolume());
        appendScalar(text, "Physics-aware log volume", evaluation.physicsAwareLogVolume());
        appendScalar(text, "Bridge endpoint RMS acceleration",
                evaluation.bridgeEndpointRmsAccelerationMetersPerSecondSquared());
        appendScalar(text, "Bridge endpoint peak acceleration",
                evaluation.bridgeEndpointPeakAccelerationMetersPerSecondSquared());
        appendScalar(text, "Bridge admissibility quadratic",
                evaluation.bridgeAdmissibilityQuadratic());
        appendScalar(text, "Bridge admissible volume m3",
                evaluation.bridgeAdmissibleVolumeCubicMeters());
        appendScalar(text, "Bridge admissible volume km3",
                evaluation.bridgeAdmissibleVolumeCubicKilometers());
        appendBoolean(text, "Bridge endpoint admissible", evaluation.bridgeEndpointAdmissible());
        appendBoolean(text, "Bridge bank admissible", evaluation.bridgeAdmissible());
        appendScalar(text, "Bridge different-target NLL",
                evaluation.bridgeDifferentTargetNegativeLogLikelihood());
        appendScalar(text, "Bridge NLLR", evaluation.bridgeNegativeLogLikelihoodRatio());
        appendScalar(text, "User NLLR volume km3", evaluation.userNllrVolumeCubicKilometers());
        appendScalar(text, "User-volume NLLR",
                evaluation.userVolumeNegativeLogLikelihoodRatio());
        appendScalar(text, "Innovation volume km3", evaluation.innovationVolumeCubicKilometers());
        appendScalar(text, "Static lambda ex", evaluation.staticLambdaEx());
        appendScalar(text, "Static NLLR", evaluation.staticNegativeLogLikelihoodRatio());
        appendScalar(text, "Learned birth density per km3",
                evaluation.learnedBirthDensityPerCubicKilometer());
        appendScalar(text, "Learned expected births", evaluation.learnedExpectedBirths());
        appendScalar(text, "Learned exposure scan km3",
                evaluation.learnedExposureScanCubicKilometers());
        appendScalar(text, "Learned reliability", evaluation.learnedReliability());
        appendScalar(text, "Learned query sigma meters", evaluation.learnedQuerySigmaMeters());
        appendScalar(text, "Learned NLLR",
                evaluation.learnedNegativeLogLikelihoodRatio());
        return text.toString();
    }

    private static String trackSummary(TrackDetail track) {
        StringBuilder text = new StringBuilder();
        text.append("Track: ").append(track.trackId()).append('\n');
        TrackStitchingAnalyzer.Segment segment = track.segment();
        if (segment != null) {
            text.append("Formation: ").append(formatTime(segment.formationTimeSeconds()))
                    .append(" (").append(formatPrecise(segment.formationTimeSeconds()))
                    .append(" s)\n");
            text.append("Last update: ").append(formatTime(segment.lastUpdateTimeSeconds()))
                    .append(" (").append(formatPrecise(segment.lastUpdateTimeSeconds()))
                    .append(" s)\n");
            text.append("Last observed: ").append(formatTime(segment.lastObservedTimeSeconds()))
                    .append(" (").append(formatPrecise(segment.lastObservedTimeSeconds()))
                    .append(" s)\n");
            text.append("Live at event: ").append(segment.liveAtEvent()).append('\n');
            text.append("Dead at event: ").append(segment.deadAtEvent()).append('\n');
        }
        return text.toString();
    }

    private static String stateSampleLabel(StateSample sample) {
        return "%s (%s)  %-15s  %s".formatted(
                formatTime(sample.timeSeconds()),
                formatSeconds(sample.timeSeconds()),
                sample.role(),
                sample.sourcePair());
    }

    private static String bankEvaluationLabel(
            TrackStitchingAnalyzer.BankEvaluation evaluation) {
        return "%s (%s)  NLL=%s  d2=%s".formatted(
                formatTime(evaluation.timeSeconds()),
                formatSeconds(evaluation.timeSeconds()),
                formatCompact(evaluation.negativeLogLikelihood()),
                formatCompact(evaluation.innovationQuadratic()));
    }

    private static String pairLabel(
            TrackStitchingAnalyzer.PairDiagnostics diagnostics) {
        return diagnostics.result().oldTrackId() + " -> "
                + diagnostics.result().newTrackId();
    }

    private static JScrollPane scroll(Component component) {
        return new JScrollPane(
                component,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }

    private static JTextArea detailsArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(new Color(250, 251, 252));
        area.setLineWrap(false);
        return area;
    }

    private static JComponent message(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(250, 251, 252));
        panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        JLabel label = new JLabel(text);
        label.setForeground(new Color(80, 92, 104));
        panel.add(label, BorderLayout.NORTH);
        return panel;
    }

    private static void appendScalar(
            StringBuilder text,
            String label,
            double value) {
        text.append("  ").append(label).append(": ")
                .append(formatPrecise(value)).append('\n');
    }

    private static void appendBoolean(
            StringBuilder text,
            String label,
            boolean value) {
        text.append("  ").append(label).append(": ").append(value).append('\n');
    }

    private static void appendVector(
            StringBuilder text,
            double[] vector,
            int size) {
        for (int row = 0; row < size; row++) {
            double value = row < vector.length ? vector[row] : Double.NaN;
            text.append("  [").append(formatPrecise(value)).append("]\n");
        }
    }

    private static void appendMatrix(
            StringBuilder text,
            double[][] matrix,
            int size) {
        for (int row = 0; row < size; row++) {
            text.append("  [");
            for (int column = 0; column < size; column++) {
                if (column > 0) {
                    text.append(' ');
                }
                double value = row < matrix.length
                        && matrix[row] != null
                        && column < matrix[row].length
                        ? matrix[row][column]
                        : Double.NaN;
                text.append(formatPrecise(value));
            }
            text.append("]\n");
        }
    }

    private static String formatTime(double seconds) {
        if (!Double.isFinite(seconds)) {
            return "-";
        }
        int totalSeconds = Math.max(0, (int) Math.round(seconds));
        return "%02d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
    }

    private static String formatCompact(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.3g", value);
    }

    private static String formatSeconds(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.3f s", value);
    }

    private static String formatPrecise(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.9e", value);
    }

    private record TrackDetail(
            String trackId,
            TrackStitchingAnalyzer.Segment segment,
            List<StateSample> samples) {
        TrackDetail(String trackId, TrackStitchingAnalyzer.Segment segment) {
            this(trackId, segment, new ArrayList<>());
        }
    }

    private record StateSample(
            double timeSeconds,
            String role,
            String sourcePair,
            double[] state,
            double[][] covariance) {
    }
}
