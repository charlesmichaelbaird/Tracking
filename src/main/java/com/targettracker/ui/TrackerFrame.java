package com.targettracker.ui;

import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.PresetScenarioGenerator;
import com.targettracker.model.PresetScenarioParameters;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.ScenarioPreset;
import com.targettracker.model.SensorSettings;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.recording.TrackCsvRecorder;
import com.targettracker.recording.TrackCsvReader;
import com.targettracker.tracking.ImmSettings;
import com.targettracker.tracking.ImmTracker;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Path;

public final class TrackerFrame extends JFrame {
    private final ScenarioModel model = new ScenarioModel();
    private final SensorSettings sensorSettings = new SensorSettings();
    private final ImmSettings immSettings = new ImmSettings();
    private final DisplayHistorySettings displayHistorySettings = new DisplayHistorySettings();
    private final MeasurementEngine measurementEngine;
    private final ImmTracker immTracker;
    private final TrackCsvRecorder recorder = new TrackCsvRecorder();
    private final ScenarioPlayback playback;
    private final EarthMapCanvas earthMapCanvas;
    private final MotionTelemetryPanel motionTelemetryPanel;
    private final SensorParametersPanel sensorParametersPanel;
    private final ImmParametersPanel immParametersPanel;
    private final TargetsPanel targetsPanel;
    private final ControlSidebar controlSidebar;
    private final RecordingPanel recordingPanel;
    private final AnalysisLoadPanel analysisLoadPanel;
    private final DisplayControlsPanel displayControlsPanel;
    private final CardLayout dataModeLayout = new CardLayout();
    private final JPanel dataModePanel = new JPanel(dataModeLayout);
    private final PresetScenarioPanel presetScenarioPanel;
    private final ScenarioTimelinePanel timelinePanel;

    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel scenarioTimeLabel = new JLabel("t = 0.0 s");
    private final JButton pauseButton = new JButton("Pause");
    private final JButton precomputeButton = new JButton("Pre-compute scenario");
    private final JButton replayButton = new JButton("Replay scenario");
    private final JToggleButton generationModeButton =
            new JToggleButton("Scenario Generation", true);
    private final JToggleButton analysisModeButton = new JToggleButton("Analysis Mode");
    private TargetTrajectory selectedTarget;
    private boolean presetScenarioActive;
    private boolean analysisMode;
    private String currentScenarioName = "User generated";
    private RecordedScenario loadedAnalysisScenario;

    public TrackerFrame() {
        super("ECEF Target Tracker");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1_100, 680));
        setSize(1_450, 860);

        selectedTarget = model.addTarget();
        measurementEngine = new MeasurementEngine(model, sensorSettings);
        immTracker = new ImmTracker(immSettings);
        playback = new ScenarioPlayback(
                model, this::onPlaybackUpdated, measurementEngine, immTracker, recorder);
        timelinePanel = new ScenarioTimelinePanel(model, playback, recorder);
        recordingPanel = new RecordingPanel(this, recorder, this::onRecordingStateChanged);
        analysisLoadPanel = new AnalysisLoadPanel(
                this,
                recorder.outputParent(),
                this::loadRecordedScenarioFolder,
                this::openTrackStitchingAnalysis);
        dataModePanel.setOpaque(false);
        dataModePanel.add(recordingPanel, "generation");
        dataModePanel.add(analysisLoadPanel, "analysis");
        presetScenarioPanel = new PresetScenarioPanel(this, new PresetScenarioPanel.Listener() {
            @Override
            public void generatePreset(
                    ScenarioPreset preset,
                    PresetScenarioParameters parameters) {
                loadPresetScenario(preset, parameters);
            }

            @Override
            public void selectUserGeneratedMode() {
                activateUserGeneratedMode();
            }
        });
        earthMapCanvas = new EarthMapCanvas(
                model,
                playback,
                measurementEngine,
                displayHistorySettings,
                () -> selectedTarget,
                this::isScenarioEditingLocked,
                this::onPathChanged,
                this::onCursorChanged);
        displayControlsPanel = new DisplayControlsPanel(
                displayHistorySettings, earthMapCanvas::repaint);
        motionTelemetryPanel = new MotionTelemetryPanel(
                model,
                () -> selectedTarget,
                playback,
                this::isScenarioEditingLocked,
                this::onProfileChanged,
                this::selectTarget);
        sensorParametersPanel = new SensorParametersPanel(
                sensorSettings, this::onSensorParametersChanged);
        immParametersPanel = new ImmParametersPanel(
                immSettings, this::onImmParametersChanged);
        targetsPanel = new TargetsPanel(
                this::addTarget,
                earthMapCanvas::setDrawingMode,
                earthMapCanvas::finishPath,
                this::clearSelectedPath);
        controlSidebar = new ControlSidebar(
                immParametersPanel,
                sensorParametersPanel,
                motionTelemetryPanel,
                targetsPanel,
                presetScenarioPanel);

        setLayout(new BorderLayout());
        add(createHeader(), BorderLayout.NORTH);
        JPanel mapArea = new JPanel(new BorderLayout());
        mapArea.add(earthMapCanvas, BorderLayout.CENTER);
        mapArea.add(timelinePanel, BorderLayout.SOUTH);
        add(mapArea, BorderLayout.CENTER);
        add(controlSidebar, BorderLayout.EAST);
        add(createStatusBar(), BorderLayout.SOUTH);

        motionTelemetryPanel.setSelectedTarget(selectedTarget);
        updateStructuralEditingControls();
        refreshTelemetry();
        setLocationRelativeTo(null);
    }

    private JPanel createHeader() {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(Color.WHITE);
        container.setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, new Color(214, 220, 227)));

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.setBorder(BorderFactory.createEmptyBorder(10, 14, 4, 14));
        JLabel title = new JLabel("ECEF Target Tracker");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19.0f));
        titleRow.add(title, BorderLayout.WEST);
        JLabel frameLabel = new JLabel(
                "WGS-84 ECEF • Plate Carrée view • Ellipsoidal altitude");
        frameLabel.setForeground(new Color(91, 103, 115));
        titleRow.add(frameLabel, BorderLayout.EAST);
        container.add(titleRow);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 7));
        toolbar.setOpaque(false);

        JLabel modeLabel = new JLabel("Mode:");
        modeLabel.setFont(modeLabel.getFont().deriveFont(Font.BOLD));
        toolbar.add(modeLabel);
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(generationModeButton);
        modeGroup.add(analysisModeButton);
        generationModeButton.addActionListener(event -> setAnalysisMode(false));
        analysisModeButton.addActionListener(event -> setAnalysisMode(true));
        toolbar.add(generationModeButton);
        toolbar.add(analysisModeButton);
        toolbar.add(verticalSeparator());

        JLabel mapControlsLabel = new JLabel("Map:");
        mapControlsLabel.setFont(mapControlsLabel.getFont().deriveFont(Font.BOLD));
        toolbar.add(mapControlsLabel);

        JButton zoomInButton = new JButton("+");
        zoomInButton.setToolTipText("Zoom in");
        zoomInButton.addActionListener(event -> earthMapCanvas.zoomIn());
        toolbar.add(zoomInButton);

        JButton zoomOutButton = new JButton("−");
        zoomOutButton.setToolTipText("Zoom out");
        zoomOutButton.addActionListener(event -> earthMapCanvas.zoomOut());
        toolbar.add(zoomOutButton);

        JButton resetViewButton = new JButton("World view");
        resetViewButton.addActionListener(event -> earthMapCanvas.resetView());
        toolbar.add(resetViewButton);

        JToggleButton detailMapButton = new JToggleButton("Geography detail", true);
        detailMapButton.setToolTipText(
                "Show bundled Natural Earth coastlines, borders, and rivers while zoomed in");
        detailMapButton.addActionListener(event -> earthMapCanvas.setHighResolutionMapEnabled(
                detailMapButton.isSelected()));
        toolbar.add(detailMapButton);

        toolbar.add(verticalSeparator());
        JLabel scenarioControlsLabel = new JLabel("Scenario:");
        scenarioControlsLabel.setFont(
                scenarioControlsLabel.getFont().deriveFont(Font.BOLD));
        toolbar.add(scenarioControlsLabel);
        precomputeButton.setToolTipText(
                "Compute the complete sensor and tracker history without real-time waiting");
        precomputeButton.addActionListener(event -> precomputeScenario());
        toolbar.add(precomputeButton);

        replayButton.setEnabled(false);
        replayButton.setToolTipText("Play the most recently pre-computed scenario");
        replayButton.addActionListener(event -> replayScenario());
        toolbar.add(replayButton);

        pauseButton.setEnabled(false);
        pauseButton.addActionListener(event -> playback.togglePause());
        toolbar.add(pauseButton);

        JButton resetScenarioButton = new JButton("Reset");
        resetScenarioButton.setToolTipText("Rewind to t = 0 and pause");
        resetScenarioButton.addActionListener(event -> resetScenario());
        toolbar.add(resetScenarioButton);

        container.add(toolbar);
        container.add(dataModePanel);
        container.add(displayControlsPanel);
        return container;
    }

    private JPanel createStatusBar() {
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBackground(new Color(245, 247, 250));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(214, 220, 227)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        statusLabel.setForeground(new Color(76, 88, 100));
        scenarioTimeLabel.setForeground(new Color(76, 88, 100));
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(scenarioTimeLabel, BorderLayout.EAST);
        return statusBar;
    }

    private void addTarget() {
        if (presetScenarioActive || isScenarioEditingLocked()) {
            return;
        }
        playback.reset();
        TargetTrajectory target = model.addTarget();
        motionTelemetryPanel.targetAdded(target);
        selectTarget(target);
        statusLabel.setText("Created %s — draw its trajectory".formatted(target.id()));
    }

    private void selectTarget(TargetTrajectory target) {
        if (target == null) {
            return;
        }
        selectedTarget = target;
        motionTelemetryPanel.setSelectedTarget(target);
        refreshTelemetry();
        earthMapCanvas.repaint();
    }

    private void clearSelectedPath() {
        if (presetScenarioActive || isScenarioEditingLocked()) {
            return;
        }
        TargetTrajectory target = motionTelemetryPanel.selectedTarget();
        if (target == null) {
            return;
        }
        selectedTarget = target;
        clearPathForTarget(playback, target);
        earthMapCanvas.finishPath();
        motionTelemetryPanel.setSelectedTarget(target);
        statusLabel.setText("Path cleared for %s".formatted(target.id()));
        refreshTelemetry();
        timelinePanel.refresh();
        earthMapCanvas.repaint();
    }

    static void clearPathForTarget(ScenarioPlayback playback, TargetTrajectory target) {
        playback.reset();
        target.clearPath();
    }

    private void loadPresetScenario(
            ScenarioPreset preset,
            PresetScenarioParameters parameters) {
        if (analysisMode) {
            return;
        }
        playback.reset();
        PresetScenarioGenerator.generate(model, preset, parameters);
        presetScenarioActive = true;
        currentScenarioName = preset.toString();
        selectedTarget = model.targets().get(0);
        motionTelemetryPanel.replaceTargets(model.targets(), selectedTarget);
        updateStructuralEditingControls();
        earthMapCanvas.focusOnTargets();
        refreshTelemetry();
        timelinePanel.refresh();
        scenarioTimeLabel.setText("t = 0.0 / %.1f s".formatted(model.durationSeconds()));
        statusLabel.setText("Loaded %s — preset target structure is locked".formatted(preset));
    }

    private void activateUserGeneratedMode() {
        if (analysisMode) {
            return;
        }
        if (!presetScenarioActive) {
            return;
        }
        playback.reset();
        presetScenarioActive = false;
        currentScenarioName = "User generated";
        selectedTarget = model.replaceTargets(1).get(0);
        motionTelemetryPanel.replaceTargets(model.targets(), selectedTarget);
        updateStructuralEditingControls();
        earthMapCanvas.resetView();
        refreshTelemetry();
        timelinePanel.refresh();
        scenarioTimeLabel.setText("t = 0.0 s");
        statusLabel.setText("User-generated mode — draw a target trajectory");
    }

    private boolean isScenarioEditingLocked() {
        return analysisMode
                || playback.isRunning()
                || playback.isComputing()
                || presetScenarioActive;
    }

    private void updateStructuralEditingControls() {
        targetsPanel.setEditingState(isScenarioEditingLocked(), presetScenarioActive);
    }

    private void precomputeScenario() {
        if (analysisMode) {
            return;
        }
        if (!validateSensorParameters()
                || !validateImmParameters()
                || !recordingPanel.commitParentFolder()) {
            return;
        }
        earthMapCanvas.finishPath();
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        statusLabel.setText(recorder.isArmed()
                ? "Pre-computing tracker history and writing one-second samples…"
                : "Pre-computing sensor and tracker history…");
        statusLabel.paintImmediately(statusLabel.getBounds());
        try {
            if (!playback.precompute(currentScenarioName)) {
                String message = recorder.lastError() == null
                        ? "Draw or load at least one runnable target trajectory first."
                        : recorder.lastError();
                JOptionPane.showMessageDialog(
                        this,
                        message,
                        "Scenario could not be pre-computed",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (recorder.lastError() != null) {
                statusLabel.setText("Pre-compute complete with a recording error");
                JOptionPane.showMessageDialog(
                        this,
                        recorder.lastError(),
                        "Track recording stopped",
                        JOptionPane.WARNING_MESSAGE);
            } else {
                statusLabel.setText("Pre-compute complete — replay or drag the timeline");
            }
        } finally {
            setCursor(java.awt.Cursor.getDefaultCursor());
            recordingPanel.refresh();
            timelinePanel.refresh();
            updateStructuralEditingControls();
        }
    }

    private void replayScenario() {
        if (analysisMode) {
            return;
        }
        if (!playback.startReplay()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Pre-compute the scenario before starting replay.",
                    "Replay is not ready",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void resetScenario() {
        if (!playback.rewindReplayPaused()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Pre-compute the scenario before resetting its replay.",
                    "Replay is not ready",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        statusLabel.setText("Replay reset to t = 0.0 s — press Resume to continue");
    }

    private void onPathChanged() {
        if (selectedTarget == null) {
            return;
        }
        resetCompletedPlayback();
        refreshTelemetry();
        timelinePanel.refresh();
        statusLabel.setText(selectedTarget.path().size() < 2
                ? "%s: add another point".formatted(selectedTarget.id())
                : "%s: %d ECEF path points, %,.0f m geodesic length".formatted(
                        selectedTarget.id(),
                        selectedTarget.path().size(),
                        selectedTarget.surfaceLengthMeters()));
    }

    private void onProfileChanged() {
        resetCompletedPlayback();
        refreshTelemetry();
        timelinePanel.refresh();
        earthMapCanvas.repaint();
        statusLabel.setText("Updated motion profile for %s".formatted(selectedTarget.id()));
    }

    private void onCursorChanged(GeodeticPoint point) {
        if (!playback.isRunning()) {
            statusLabel.setText("Cursor  Lat %.5f°   Lon %.5f°   View %s".formatted(
                    point.latitudeDegrees(),
                    point.longitudeDegrees(),
                    earthMapCanvas.viewDescription()));
        }
    }

    private void onPlaybackUpdated() {
        earthMapCanvas.repaint();
        refreshTelemetry();
        scenarioTimeLabel.setText("t = %.1f / %.1f s".formatted(
                playback.elapsedSeconds(), playback.durationSeconds()));
        pauseButton.setEnabled(playback.isRunning());
        pauseButton.setText(playback.isPaused() ? "Resume" : "Pause");
        precomputeButton.setEnabled(!analysisMode
                && !playback.isComputing() && !playback.isRunning());
        replayButton.setEnabled(!analysisMode && playback.isReplayReady()
                && !playback.isComputing() && !playback.isRunning());
        updateStructuralEditingControls();
        recordingPanel.refresh();
        timelinePanel.refresh();
        if (playback.isComputing()) {
            statusLabel.setText("Pre-computing scenario…");
        } else if (playback.isPaused()) {
            statusLabel.setText("Replay paused");
        } else if (playback.isRunning()) {
            statusLabel.setText("Replaying pre-computed scenario");
        } else if (playback.isReplayDisplayActive()) {
            statusLabel.setText("Replay at %.1f s — drag the timeline to seek"
                    .formatted(playback.elapsedSeconds()));
        } else if (playback.elapsedSeconds() > 0.0
                && playback.elapsedSeconds() >= playback.durationSeconds()) {
            statusLabel.setText("Scenario complete");
        }
    }

    private void refreshTelemetry() {
        motionTelemetryPanel.refresh(selectedTarget, playback);
    }

    private void resetCompletedPlayback() {
        if (!playback.isRunning()
                && (playback.elapsedSeconds() > 0.0 || playback.isReplayReady())) {
            playback.reset();
        }
    }

    private boolean validateSensorParameters() {
        if (sensorParametersPanel.commitSensorParameters()) {
            return true;
        }
        controlSidebar.showCard(ControlSidebar.SENSOR);
        JOptionPane.showMessageDialog(
                this,
                "Correct the highlighted God sensor parameters before pre-computing the scenario.",
                "Invalid sensor parameters",
                JOptionPane.WARNING_MESSAGE);
        return false;
    }

    private void onSensorParametersChanged() {
        if (analysisMode) {
            return;
        }
        if (playback.isReplayReady()) {
            playback.reset();
            return;
        }
        measurementEngine.parametersChanged(playback.elapsedSeconds());
        earthMapCanvas.repaint();
    }

    private void onImmParametersChanged() {
        if (analysisMode) {
            return;
        }
        if (playback.isReplayReady()) {
            playback.reset();
            return;
        }
        immTracker.parametersChanged(playback.elapsedSeconds());
        earthMapCanvas.repaint();
    }

    private void onRecordingStateChanged() {
        timelinePanel.refresh();
    }

    private boolean validateImmParameters() {
        if (immParametersPanel.commitParameters()) {
            return true;
        }
        controlSidebar.showCard(ControlSidebar.IMM);
        JOptionPane.showMessageDialog(
                this,
                "Correct the highlighted IMM tracker parameters before pre-computing the scenario.",
                "Invalid IMM parameters",
                JOptionPane.WARNING_MESSAGE);
        return false;
    }

    private void setAnalysisMode(boolean enabled) {
        if (analysisMode == enabled) {
            return;
        }
        playback.reset();
        analysisMode = enabled;
        if (enabled) {
            recorder.setArmed(false);
            recordingPanel.refresh();
            presetScenarioActive = false;
            currentScenarioName = "User generated";
            selectedTarget = model.replaceTargets(1).get(0);
            motionTelemetryPanel.replaceTargets(model.targets(), selectedTarget);
            dataModeLayout.show(dataModePanel, "analysis");
            analysisLoadPanel.setParentFolder(recorder.outputParent());
            analysisLoadPanel.setStitchingEnabled(false);
            loadedAnalysisScenario = null;
            statusLabel.setText("Analysis mode — select a recorded scenario folder to load");
        } else {
            analysisLoadPanel.setStitchingEnabled(false);
            loadedAnalysisScenario = null;
            dataModeLayout.show(dataModePanel, "generation");
            statusLabel.setText("Scenario Generation mode");
        }
        updateModeControls();
        refreshTelemetry();
        timelinePanel.refresh();
        earthMapCanvas.resetView();
        earthMapCanvas.repaint();
    }

    private void updateModeControls() {
        generationModeButton.setSelected(!analysisMode);
        analysisModeButton.setSelected(analysisMode);
        precomputeButton.setEnabled(!analysisMode
                && !playback.isComputing() && !playback.isRunning());
        replayButton.setEnabled(!analysisMode && playback.isReplayReady()
                && !playback.isComputing() && !playback.isRunning());
        presetScenarioPanel.setGenerationEnabled(!analysisMode);
        updateStructuralEditingControls();
    }

    private void loadRecordedScenarioFolder(Path scenarioFolder) {
        if (!analysisMode) {
            return;
        }
        loadedAnalysisScenario = null;
        analysisLoadPanel.setStitchingEnabled(false);
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        try {
            RecordedScenario scenario = TrackCsvReader.read(scenarioFolder);
            loadedAnalysisScenario = scenario;
            playback.loadRecordedScenario(scenario);
            playback.rewindReplayPaused();
            earthMapCanvas.focusOnPoints(playback.scenarioExtentPoints());
            timelinePanel.refresh();
            scenarioTimeLabel.setText("t = 0.0 / %.1f s".formatted(
                    scenario.durationSeconds()));
            long trackCount = scenario.records().stream()
                    .map(record -> record.trackId())
                    .distinct()
                    .count();
            statusLabel.setText("Loaded %s — %d track(s), %.1f seconds"
                    .formatted(scenario.scenarioName(), trackCount, scenario.durationSeconds()));
            analysisLoadPanel.setStitchingEnabled(true);
        } catch (IOException | IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(
                    this,
                    "Could not load the recorded scenario.\n" + exception.getMessage(),
                    "Scenario load failed",
                    JOptionPane.WARNING_MESSAGE);
        } finally {
            setCursor(java.awt.Cursor.getDefaultCursor());
            updateModeControls();
        }
    }

    private void openTrackStitchingAnalysis() {
        if (!analysisMode || loadedAnalysisScenario == null) {
            return;
        }
        TrackStitchingWindow window = new TrackStitchingWindow(this, loadedAnalysisScenario);
        window.setVisible(true);
    }

    private static JSeparator verticalSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 24));
        return separator;
    }
}
