package com.targettracker.ui;

import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.BlackoutRegion;
import com.targettracker.model.PresetScenarioGenerator;
import com.targettracker.model.PresetScenarioParameters;
import com.targettracker.model.SavedScenarioDefinition;
import com.targettracker.model.SavedScenarioRepository;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.ScenarioPreset;
import com.targettracker.model.SensorSettings;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.recording.TrackCsvRecorder;
import com.targettracker.recording.TrackCsvReader;
import com.targettracker.tracking.ImmSettings;
import com.targettracker.tracking.ImmTracker;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
    private final SavedScenarioRepository savedScenarioRepository =
            new SavedScenarioRepository(Path.of("saved_scenarios"));
    private final ScenarioPlayback playback;
    private final EarthMapCanvas earthMapCanvas;
    private final MotionTelemetryPanel motionTelemetryPanel;
    private final SensorParametersPanel sensorParametersPanel;
    private final ImmParametersPanel immParametersPanel;
    private final ControlSidebar controlSidebar;
    private final RecordingPanel recordingPanel;
    private final AnalysisLoadPanel analysisLoadPanel;
    private final DisplayControlsPanel displayControlsPanel;
    private final CardLayout dataModeLayout = new CardLayout();
    private final JPanel dataModePanel = new JPanel(dataModeLayout);
    private final PresetScenarioPanel presetScenarioPanel;
    private final ScenarioTimelinePanel timelinePanel;
    private final JPanel mapArea = new JPanel(new BorderLayout());

    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel scenarioTimeLabel = new JLabel(formatScenarioClock(0.0));
    private final JButton pauseButton = new JButton("Pause");
    private final JButton precomputeButton = new JButton("Pre-compute scenario");
    private final JButton replayButton = new JButton("Replay scenario");
    private final JToggleButton moveToolButton = new JToggleButton("Move: Off");
    private final JToggleButton modifyToolButton = new JToggleButton("Modify: Off");
    private final JToggleButton trajectoryArrowButton = new JToggleButton("Arrow: On", true);
    private final Icon moveToolIcon = new MoveToolIcon();
    private final Icon modifyToolIcon = new ModifyToolIcon();
    private final Icon trajectoryArrowIcon = new TrajectoryArrowIcon();
    private final JToggleButton generationModeButton =
            new JToggleButton("Scenario Generation", true);
    private final JToggleButton analysisModeButton = new JToggleButton("Analysis Mode");
    private TargetTrajectory selectedTarget;
    private TargetTrajectory copiedTarget;
    private BlackoutRegion selectedBlackoutRegion;
    private EarthMapCanvas.DrawingMode drawingMode = EarthMapCanvas.DrawingMode.FREE_HAND;
    private boolean targetPanelActive = true;
    private boolean presetScenarioActive;
    private boolean analysisMode;
    private String currentScenarioName = "User generated";
    private RecordedScenario loadedAnalysisScenario;
    private TrackStitchingAnalysisPanel stitchingAnalysisPanel;

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
            public void loadSavedScenario(SavedScenarioDefinition scenario) {
                loadSavedUserScenario(scenario);
            }

            @Override
            public void selectUserGeneratedMode() {
                activateUserGeneratedMode();
            }

            @Override
            public void saveUserScenario(String scenarioName) {
                saveUserGeneratedScenario(scenarioName);
            }

            @Override
            public void setUserScenarioLength(Double durationSeconds) {
                updateUserScenarioLength(durationSeconds);
            }
        });
        refreshSavedScenarioChoices();
        earthMapCanvas = new EarthMapCanvas(
                model,
                playback,
                measurementEngine,
                displayHistorySettings,
                () -> selectedTarget,
                () -> selectedBlackoutRegion,
                this::isScenarioEditingLocked,
                this::moveBlackoutRegion,
                this::onPathChanged,
                this::onBlackoutRegionCreated,
                this::onCursorChanged);
        displayControlsPanel = new DisplayControlsPanel(
                displayHistorySettings, earthMapCanvas::repaint);
        motionTelemetryPanel = new MotionTelemetryPanel(
                model,
                () -> selectedTarget,
                playback,
                this::isScenarioEditingLocked,
                this::onProfileChanged,
                this::selectTarget,
                this::extrapolateAllTargets,
                this::addTarget,
                this::copySelectedTarget,
                this::setDrawingMode,
                earthMapCanvas::finishPath,
                this::clearSelectedPath,
                this::smoothSelectedPath,
                this::undoSmoothSelectedPath,
                this::toggleSelectedExtrapolation,
                this::removeSelectedTarget,
                earthMapCanvas::setProfileHighlightNormalizedTime);
        sensorParametersPanel = new SensorParametersPanel(
                sensorSettings,
                model,
                this::onSensorParametersChanged,
                this::beginBlackoutRegionDrawing,
                this::selectBlackoutRegion,
                this::removeBlackoutRegion);
        immParametersPanel = new ImmParametersPanel(
                immSettings, this::onImmParametersChanged);
        controlSidebar = new ControlSidebar(
                immParametersPanel,
                sensorParametersPanel,
                motionTelemetryPanel,
                presetScenarioPanel,
                this::onSidebarCardChanged);

        setLayout(new BorderLayout());
        add(createHeader(), BorderLayout.NORTH);
        mapArea.add(earthMapCanvas, BorderLayout.CENTER);
        mapArea.add(timelinePanel, BorderLayout.SOUTH);
        add(mapArea, BorderLayout.CENTER);
        add(controlSidebar, BorderLayout.EAST);
        add(createStatusBar(), BorderLayout.SOUTH);

        motionTelemetryPanel.setSelectedTarget(selectedTarget);
        updateStructuralEditingControls();
        refreshTelemetry();
        installTargetCopyPasteShortcuts();
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
        JPanel topRightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        topRightControls.setOpaque(false);
        moveToolButton.setToolTipText(
                "Toggle click-and-drag movement for target paths and blackout regions");
        moveToolButton.setIcon(moveToolIcon);
        moveToolButton.setOpaque(true);
        moveToolButton.setContentAreaFilled(true);
        moveToolButton.setFocusPainted(false);
        moveToolButton.addActionListener(event ->
                setMoveToolActive(moveToolButton.isSelected()));
        modifyToolButton.setToolTipText(
                "Pick and place vertices on segmented target trajectories");
        modifyToolButton.setIcon(modifyToolIcon);
        modifyToolButton.setOpaque(true);
        modifyToolButton.setContentAreaFilled(true);
        modifyToolButton.setFocusPainted(false);
        modifyToolButton.addActionListener(event ->
                setModifyToolActive(modifyToolButton.isSelected()));
        trajectoryArrowButton.setToolTipText(
                "Show or hide direction arrows on target trajectories");
        trajectoryArrowButton.setIcon(trajectoryArrowIcon);
        trajectoryArrowButton.setOpaque(true);
        trajectoryArrowButton.setContentAreaFilled(true);
        trajectoryArrowButton.setFocusPainted(false);
        trajectoryArrowButton.addActionListener(event ->
                setTrajectoryArrowsVisible(trajectoryArrowButton.isSelected()));
        refreshMoveToolButton();
        refreshModifyToolButton();
        refreshTrajectoryArrowButton();
        topRightControls.add(frameLabel);
        topRightControls.add(moveToolButton);
        topRightControls.add(modifyToolButton);
        topRightControls.add(trajectoryArrowButton);
        titleRow.add(topRightControls, BorderLayout.EAST);
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
        if (modifyToolButton.isSelected()) {
            setModifyToolActive(false);
        }
        playback.reset();
        TargetTrajectory target = model.addTarget();
        motionTelemetryPanel.targetAdded(target);
        selectTarget(target);
        statusLabel.setText("Created %s — draw its trajectory".formatted(target.id()));
    }

    private void copySelectedTarget() {
        if (presetScenarioActive || isScenarioEditingLocked()) {
            return;
        }
        if (copySelectedTargetToClipboard()) {
            pasteCopiedTarget();
        }
    }

    private boolean copySelectedTargetToClipboard() {
        earthMapCanvas.cancelNodeModification();
        TargetTrajectory target = motionTelemetryPanel.selectedTarget();
        if (target == null || target.path().size() < 2) {
            statusLabel.setText("Finish a target trajectory before copying it");
            return false;
        }
        copiedTarget = new TargetTrajectory("COPIED", target.color());
        copiedTarget.copyStateFrom(target);
        statusLabel.setText("Copied %s - press Ctrl+V to paste".formatted(target.id()));
        return true;
    }

    private void pasteCopiedTarget() {
        if (presetScenarioActive || isScenarioEditingLocked()) {
            statusLabel.setText("Targets can be pasted in an idle user-generated scenario");
            return;
        }
        if (copiedTarget == null) {
            statusLabel.setText("Copy a finished target trajectory before pasting");
            return;
        }
        earthMapCanvas.cancelNodeModification();
        playback.reset();
        TargetTrajectory target = model.copyTarget(copiedTarget);
        motionTelemetryPanel.targetAdded(target);
        selectTarget(target);
        earthMapCanvas.finishPath();
        reconcileActiveExtrapolations();
        updateStructuralEditingControls();
        refreshTelemetry();
        timelinePanel.refresh();
        earthMapCanvas.repaint();
        statusLabel.setText("Created %s from the copied trajectory".formatted(target.id()));
    }

    private void installTargetCopyPasteShortcuts() {
        var inputMap = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var actionMap = getRootPane().getActionMap();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
                "copy-selected-target");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK),
                "paste-copied-target");
        actionMap.put("copy-selected-target", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                copySelectedTargetToClipboard();
            }
        });
        actionMap.put("paste-copied-target", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                pasteCopiedTarget();
            }
        });
    }

    private void selectTarget(TargetTrajectory target) {
        if (target == null) {
            return;
        }
        earthMapCanvas.cancelNodeModification();
        selectedTarget = target;
        motionTelemetryPanel.setSelectedTarget(target);
        refreshTelemetry();
        earthMapCanvas.repaint();
    }

    private void setDrawingMode(EarthMapCanvas.DrawingMode drawingMode) {
        this.drawingMode = drawingMode;
        earthMapCanvas.setDrawingMode(drawingMode);
        if (drawingMode != EarthMapCanvas.DrawingMode.SEGMENTED
                && modifyToolButton.isSelected()) {
            setModifyToolActive(false);
        }
        updateModifyToolAvailability();
    }

    private void onSidebarCardChanged(String cardName) {
        boolean targetCard = ControlSidebar.TARGETS.equals(cardName);
        boolean sensorCard = ControlSidebar.SENSOR.equals(cardName);
        targetPanelActive = targetCard;
        earthMapCanvas.setTargetDrawingEnabled(targetCard);
        earthMapCanvas.setBlackoutEditingEnabled(sensorCard);
        if (!targetCard && modifyToolButton.isSelected()) {
            setModifyToolActive(false);
        }
        updateModifyToolAvailability();
    }

    private void setMoveToolActive(boolean active) {
        boolean enabled = active && !isScenarioEditingLocked();
        if (enabled && modifyToolButton.isSelected()) {
            modifyToolButton.setSelected(false);
            earthMapCanvas.setModifyToolEnabled(false);
            refreshModifyToolButton();
        }
        if (moveToolButton.isSelected() != enabled) {
            moveToolButton.setSelected(enabled);
        }
        earthMapCanvas.setMoveToolEnabled(enabled);
        refreshMoveToolButton();
        statusLabel.setText(enabled
                ? "Move tool enabled — drag target paths or blackout regions"
                : "Move tool disabled");
    }

    private void refreshMoveToolButton() {
        boolean selected = moveToolButton.isSelected();
        moveToolButton.setText(selected ? "Move: On" : "Move: Off");
        moveToolButton.setBackground(selected
                ? new Color(255, 218, 92)
                : new Color(235, 238, 242));
        moveToolButton.setForeground(new Color(28, 36, 44));
        moveToolButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected
                        ? new Color(166, 111, 0)
                        : new Color(168, 176, 184)),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        moveToolButton.repaint();
    }

    private void setModifyToolActive(boolean active) {
        boolean enabled = active
                && !isScenarioEditingLocked()
                && targetPanelActive
                && drawingMode == EarthMapCanvas.DrawingMode.SEGMENTED;
        if (enabled && moveToolButton.isSelected()) {
            moveToolButton.setSelected(false);
            earthMapCanvas.setMoveToolEnabled(false);
            refreshMoveToolButton();
        }
        if (modifyToolButton.isSelected() != enabled) {
            modifyToolButton.setSelected(enabled);
        }
        earthMapCanvas.setModifyToolEnabled(enabled);
        refreshModifyToolButton();
        statusLabel.setText(enabled
                ? "Modify tool enabled - click a trajectory node, then click to place it"
                : "Modify tool disabled");
    }

    private void updateModifyToolAvailability() {
        boolean enabled = !isScenarioEditingLocked()
                && targetPanelActive
                && drawingMode == EarthMapCanvas.DrawingMode.SEGMENTED;
        modifyToolButton.setEnabled(enabled);
        if (!enabled && modifyToolButton.isSelected()) {
            modifyToolButton.setSelected(false);
            earthMapCanvas.setModifyToolEnabled(false);
        }
        refreshModifyToolButton();
    }

    private void refreshModifyToolButton() {
        boolean selected = modifyToolButton.isSelected();
        modifyToolButton.setText(selected ? "Modify: On" : "Modify: Off");
        modifyToolButton.setBackground(selected
                ? new Color(203, 236, 210)
                : new Color(235, 238, 242));
        modifyToolButton.setForeground(new Color(28, 36, 44));
        modifyToolButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected
                        ? new Color(52, 130, 72)
                        : new Color(168, 176, 184)),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        modifyToolButton.repaint();
    }

    private void setTrajectoryArrowsVisible(boolean visible) {
        if (trajectoryArrowButton.isSelected() != visible) {
            trajectoryArrowButton.setSelected(visible);
        }
        earthMapCanvas.setTrajectoryArrowsVisible(visible);
        refreshTrajectoryArrowButton();
        statusLabel.setText(visible
                ? "Trajectory arrows enabled"
                : "Trajectory arrows hidden");
    }

    private void refreshTrajectoryArrowButton() {
        boolean selected = trajectoryArrowButton.isSelected();
        trajectoryArrowButton.setText(selected ? "Arrow: On" : "Arrow: Off");
        trajectoryArrowButton.setBackground(selected
                ? new Color(199, 231, 255)
                : new Color(235, 238, 242));
        trajectoryArrowButton.setForeground(new Color(28, 36, 44));
        trajectoryArrowButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(selected
                        ? new Color(61, 126, 174)
                        : new Color(168, 176, 184)),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        trajectoryArrowButton.repaint();
    }

    private void clearSelectedPath() {
        if (presetScenarioActive || isScenarioEditingLocked()) {
            return;
        }
        earthMapCanvas.cancelNodeModification();
        TargetTrajectory target = motionTelemetryPanel.selectedTarget();
        if (target == null) {
            return;
        }
        selectedTarget = target;
        clearPathForTarget(playback, target);
        earthMapCanvas.finishPath();
        reconcileActiveExtrapolations();
        motionTelemetryPanel.setSelectedTarget(target);
        statusLabel.setText("Path cleared for %s".formatted(target.id()));
        refreshTelemetry();
        timelinePanel.refresh();
        earthMapCanvas.repaint();
    }

    private void smoothSelectedPath() {
        if (presetScenarioActive || isScenarioEditingLocked()) {
            return;
        }
        earthMapCanvas.cancelNodeModification();
        TargetTrajectory target = motionTelemetryPanel.selectedTarget();
        if (target == null) {
            return;
        }
        selectedTarget = target;
        resetCompletedPlayback();
        if (target.smoothPath()) {
            reconcileActiveExtrapolations();
            refreshTelemetry();
            timelinePanel.refresh();
            earthMapCanvas.repaint();
            statusLabel.setText("Smoothed path for %s".formatted(target.id()));
        } else {
            statusLabel.setText("Add at least three path points before smoothing");
        }
    }

    private void undoSmoothSelectedPath() {
        if (presetScenarioActive || isScenarioEditingLocked()) {
            return;
        }
        earthMapCanvas.cancelNodeModification();
        TargetTrajectory target = motionTelemetryPanel.selectedTarget();
        if (target == null) {
            return;
        }
        selectedTarget = target;
        resetCompletedPlayback();
        if (target.undoSmoothing()) {
            reconcileActiveExtrapolations();
            refreshTelemetry();
            timelinePanel.refresh();
            earthMapCanvas.repaint();
            statusLabel.setText("Restored pre-smoothing path for %s".formatted(target.id()));
        } else {
            statusLabel.setText("No smoothing pass to undo for %s".formatted(target.id()));
        }
    }

    private void toggleSelectedExtrapolation() {
        if (presetScenarioActive || isScenarioEditingLocked()) {
            return;
        }
        earthMapCanvas.cancelNodeModification();
        TargetTrajectory target = motionTelemetryPanel.selectedTarget();
        if (target == null) {
            return;
        }
        selectedTarget = target;
        resetCompletedPlayback();
        if (target.extrapolatedToScenarioLength()) {
            if (target.removeExtrapolation()) {
                statusLabel.setText("Removed extrapolation for %s".formatted(target.id()));
            }
        } else if (model.durationSeconds() <= 0.0) {
            statusLabel.setText("Draw a runnable target trajectory before extrapolating");
        } else if (target.extrapolateToDuration(model.durationSeconds())) {
            statusLabel.setText("%s extrapolated to %s".formatted(
                    target.id(), formatClockTime(model.durationSeconds())));
        } else {
            statusLabel.setText("%s already reaches the scenario length".formatted(target.id()));
        }
        refreshTelemetry();
        timelinePanel.refresh();
        earthMapCanvas.repaint();
    }

    private void extrapolateAllTargets() {
        if (presetScenarioActive || isScenarioEditingLocked()) {
            return;
        }
        earthMapCanvas.cancelNodeModification();
        double durationSeconds = model.durationSeconds();
        if (durationSeconds <= 0.0) {
            statusLabel.setText("Draw a runnable target trajectory before extrapolating");
            return;
        }
        resetCompletedPlayback();
        int extrapolated = model.extrapolateTargetsToScenarioDuration();
        if (extrapolated > 0) {
            String noun = extrapolated == 1 ? "target" : "targets";
            statusLabel.setText("Extrapolated %d %s to %s".formatted(
                    extrapolated, noun, formatClockTime(durationSeconds)));
        } else {
            statusLabel.setText("All runnable targets already reach %s".formatted(
                    formatClockTime(durationSeconds)));
        }
        refreshTelemetry();
        timelinePanel.refresh();
        earthMapCanvas.repaint();
    }

    private void removeSelectedTarget() {
        if (presetScenarioActive || isScenarioEditingLocked()) {
            return;
        }
        earthMapCanvas.cancelNodeModification();
        TargetTrajectory target = motionTelemetryPanel.selectedTarget();
        if (target == null) {
            return;
        }
        playback.reset();
        model.removeTarget(target);
        reconcileActiveExtrapolations();
        selectedTarget = model.targets().isEmpty() ? null : model.targets().get(0);
        motionTelemetryPanel.replaceTargets(model.targets(), selectedTarget);
        updateStructuralEditingControls();
        refreshTelemetry();
        timelinePanel.refresh();
        earthMapCanvas.repaint();
        statusLabel.setText("Removed %s".formatted(target.id()));
    }

    static void clearPathForTarget(ScenarioPlayback playback, TargetTrajectory target) {
        playback.reset();
        target.clearPath();
    }

    private void beginBlackoutRegionDrawing() {
        if (analysisMode || presetScenarioActive || playback.isRunning() || playback.isComputing()) {
            statusLabel.setText("Blackout regions can be drawn in idle user-generated scenarios");
            return;
        }
        resetCompletedPlayback();
        earthMapCanvas.startBlackoutRegionDrawing();
        statusLabel.setText("Blackout drawing armed — click two opposite rectangle corners");
    }

    private void onBlackoutRegionCreated(BlackoutRegion region) {
        selectedBlackoutRegion = region;
        resetCompletedPlayback();
        sensorParametersPanel.refreshBlackoutRegions();
        sensorParametersPanel.setSelectedBlackoutRegion(region);
        earthMapCanvas.repaint();
        timelinePanel.refresh();
        statusLabel.setText("Added blackout region");
    }

    private void selectBlackoutRegion(BlackoutRegion region) {
        selectedBlackoutRegion = region;
        earthMapCanvas.repaint();
    }

    private void removeBlackoutRegion(BlackoutRegion region) {
        if (analysisMode || presetScenarioActive || playback.isRunning() || playback.isComputing()) {
            statusLabel.setText("Blackout regions can be removed in idle user-generated scenarios");
            return;
        }
        if (region == null) {
            return;
        }
        resetCompletedPlayback();
        if (model.removeBlackoutRegion(region)) {
            selectedBlackoutRegion = model.blackoutRegions().isEmpty()
                    ? null
                    : model.blackoutRegions().get(0);
            sensorParametersPanel.refreshBlackoutRegions();
            sensorParametersPanel.setSelectedBlackoutRegion(selectedBlackoutRegion);
            earthMapCanvas.repaint();
            timelinePanel.refresh();
            statusLabel.setText("Removed blackout region");
        }
    }

    private BlackoutRegion moveBlackoutRegion(
            BlackoutRegion region,
            GeodeticPoint newCenter) {
        if (analysisMode || presetScenarioActive || playback.isRunning() || playback.isComputing()) {
            return region;
        }
        resetCompletedPlayback();
        selectedBlackoutRegion = model.moveBlackoutRegion(region, newCenter);
        sensorParametersPanel.refreshBlackoutRegions();
        sensorParametersPanel.setSelectedBlackoutRegion(selectedBlackoutRegion);
        timelinePanel.refresh();
        return selectedBlackoutRegion;
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
        selectedBlackoutRegion = model.blackoutRegions().isEmpty()
                ? null
                : model.blackoutRegions().get(0);
        motionTelemetryPanel.replaceTargets(model.targets(), selectedTarget);
        sensorParametersPanel.refreshBlackoutRegions();
        sensorParametersPanel.setSelectedBlackoutRegion(selectedBlackoutRegion);
        updateStructuralEditingControls();
        earthMapCanvas.focusOnTargets();
        refreshTelemetry();
        timelinePanel.refresh();
        scenarioTimeLabel.setText(formatScenarioClock(0.0, model.durationSeconds()));
        statusLabel.setText("Loaded %s — preset target structure is locked".formatted(preset));
    }

    private void loadSavedUserScenario(SavedScenarioDefinition scenario) {
        if (analysisMode || scenario == null) {
            return;
        }
        playback.reset();
        try {
            savedScenarioRepository.loadInto(scenario, model);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(
                    this,
                    exception.getMessage(),
                    "Saved scenario load failed",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        presetScenarioActive = false;
        currentScenarioName = scenario.name();
        selectedTarget = model.targets().isEmpty() ? null : model.targets().get(0);
        selectedBlackoutRegion = model.blackoutRegions().isEmpty()
                ? null
                : model.blackoutRegions().get(0);
        motionTelemetryPanel.replaceTargets(model.targets(), selectedTarget);
        sensorParametersPanel.refreshBlackoutRegions();
        sensorParametersPanel.setSelectedBlackoutRegion(selectedBlackoutRegion);
        updateStructuralEditingControls();
        earthMapCanvas.focusOnTargets();
        refreshTelemetry();
        timelinePanel.refresh();
        scenarioTimeLabel.setText(formatScenarioClock(0.0, model.durationSeconds()));
        statusLabel.setText("Loaded saved user scenario %s — editing enabled"
                .formatted(scenario.name()));
    }

    private void updateUserScenarioLength(Double durationSeconds) {
        if (analysisMode || presetScenarioActive) {
            return;
        }
        try {
            model.setScenarioLengthSeconds(durationSeconds);
        } catch (IllegalArgumentException exception) {
            statusLabel.setText(exception.getMessage());
            return;
        }
        resetCompletedPlayback();
        reconcileActiveExtrapolations();
        refreshTelemetry();
        timelinePanel.refresh();
        earthMapCanvas.repaint();
        scenarioTimeLabel.setText(model.hasScenarioLength()
                ? formatScenarioClock(0.0, model.durationSeconds())
                : formatScenarioClock(0.0));
        statusLabel.setText(model.hasScenarioLength()
                ? "Scenario length set to %s".formatted(formatClockTime(model.durationSeconds()))
                : "Scenario length cleared");
    }

    private void reconcileActiveExtrapolations() {
        double durationSeconds = model.durationSeconds();
        for (TargetTrajectory target : model.targets()) {
            if (!target.extrapolatedToScenarioLength()) {
                continue;
            }
            if (durationSeconds > 0.0) {
                target.extrapolateToDuration(durationSeconds);
            } else {
                target.removeExtrapolation();
            }
        }
    }

    private void saveUserGeneratedScenario(String scenarioName) {
        if (analysisMode || presetScenarioActive) {
            JOptionPane.showMessageDialog(
                    this,
                    "Only user-generated scenarios can be saved from this section.",
                    "Scenario save unavailable",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        boolean hasAsset = model.targets().stream().anyMatch(target -> !target.path().isEmpty())
                || !model.blackoutRegions().isEmpty();
        if (!hasAsset) {
            JOptionPane.showMessageDialog(
                    this,
                    "Add at least one target path or blackout region before saving.",
                    "Nothing to save",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            SavedScenarioDefinition saved = savedScenarioRepository.save(scenarioName, model);
            refreshSavedScenarioChoices();
            currentScenarioName = saved.name();
            statusLabel.setText("Saved scenario %s".formatted(saved.name()));
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(
                    this,
                    exception.getMessage(),
                    "Invalid scenario name",
                    JOptionPane.WARNING_MESSAGE);
        } catch (IOException exception) {
            JOptionPane.showMessageDialog(
                    this,
                    exception.getMessage(),
                    "Scenario save failed",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void refreshSavedScenarioChoices() {
        try {
            presetScenarioPanel.setSavedScenarios(savedScenarioRepository.list());
        } catch (IOException exception) {
            statusLabel.setText("Saved scenario scan failed: " + exception.getMessage());
        }
    }

    private void activateUserGeneratedMode() {
        if (analysisMode) {
            return;
        }
        if (!presetScenarioActive && "User generated".equals(currentScenarioName)) {
            return;
        }
        playback.reset();
        presetScenarioActive = false;
        currentScenarioName = "User generated";
        selectedTarget = model.replaceTargets(1).get(0);
        selectedBlackoutRegion = null;
        motionTelemetryPanel.replaceTargets(model.targets(), selectedTarget);
        sensorParametersPanel.refreshBlackoutRegions();
        updateStructuralEditingControls();
        earthMapCanvas.resetView();
        refreshTelemetry();
        timelinePanel.refresh();
        scenarioTimeLabel.setText(formatScenarioClock(0.0));
        statusLabel.setText("User-generated mode — draw a target trajectory");
    }

    private boolean isScenarioEditingLocked() {
        return analysisMode
                || playback.isRunning()
                || playback.isComputing()
                || presetScenarioActive;
    }

    private void updateStructuralEditingControls() {
        motionTelemetryPanel.setEditingState(isScenarioEditingLocked(), presetScenarioActive);
        boolean canMove = !isScenarioEditingLocked();
        moveToolButton.setEnabled(canMove);
        if (!canMove && moveToolButton.isSelected()) {
            moveToolButton.setSelected(false);
            earthMapCanvas.setMoveToolEnabled(false);
        }
        refreshMoveToolButton();
        updateModifyToolAvailability();
    }

    private void precomputeScenario() {
        if (analysisMode) {
            return;
        }
        earthMapCanvas.cancelNodeModification();
        if (!validateSensorParameters()
                || !validateImmParameters()
                || !recordingPanel.commitParentFolder()) {
            return;
        }
        earthMapCanvas.finishPath();
        if (earthMapCanvas.hasPendingPathDirectionSelection()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Click the generated trajectory to choose its start point, then click again to choose its starting direction.",
                    "Trajectory direction required",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        statusLabel.setText(recorder.isArmed()
                ? "Pre-computing tracker history and writing one-second samples…"
                : "Pre-computing sensor and tracker history…");
        statusLabel.paintImmediately(statusLabel.getBounds());
        try {
            if (!playback.precompute(scenarioNameForRecording())) {
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

    private String scenarioNameForRecording() {
        return presetScenarioPanel.scenarioNameForRecording(currentScenarioName);
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
        if (!analysisMode) {
            playback.reset();
            earthMapCanvas.setProfileHighlightNormalizedTime(Double.NaN);
            updateStructuralEditingControls();
            refreshTelemetry();
            timelinePanel.refresh();
            earthMapCanvas.repaint();
            scenarioTimeLabel.setText(formatScenarioClock(0.0));
            statusLabel.setText("Scenario reset — editing unlocked");
            return;
        }
        if (!playback.rewindReplayPaused()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Pre-compute the scenario before resetting its replay.",
                    "Replay is not ready",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        statusLabel.setText("Replay reset to t = %s — press Resume to continue"
                .formatted(formatClockTime(0.0)));
    }

    private void onPathChanged() {
        if (selectedTarget == null) {
            return;
        }
        resetCompletedPlayback();
        reconcileActiveExtrapolations();
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
        if (selectedTarget == null) {
            return;
        }
        resetCompletedPlayback();
        reconcileActiveExtrapolations();
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
        scenarioTimeLabel.setText(formatScenarioClock(
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
            statusLabel.setText("Replay at %s — drag the timeline to seek"
                    .formatted(formatClockTime(playback.elapsedSeconds())));
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
        exitTrackStitchingAnalysis();
        playback.reset();
        analysisMode = enabled;
        if (enabled) {
            recorder.setArmed(false);
            recordingPanel.refresh();
            presetScenarioActive = false;
            currentScenarioName = "User generated";
            selectedTarget = model.replaceTargets(1).get(0);
            selectedBlackoutRegion = null;
            motionTelemetryPanel.replaceTargets(model.targets(), selectedTarget);
            sensorParametersPanel.refreshBlackoutRegions();
            dataModeLayout.show(dataModePanel, "analysis");
            analysisLoadPanel.setParentFolder(recorder.outputParent());
            analysisLoadPanel.setStitchingEnabled(false);
            loadedAnalysisScenario = null;
            statusLabel.setText("Analysis mode — select a recorded scenario folder to load");
        } else {
            analysisLoadPanel.setStitchingEnabled(false);
            loadedAnalysisScenario = null;
            dataModeLayout.show(dataModePanel, "generation");
            sensorParametersPanel.refreshBlackoutRegions();
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
        exitTrackStitchingAnalysis();
        loadedAnalysisScenario = null;
        analysisLoadPanel.setStitchingEnabled(false);
        setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        try {
            RecordedScenario scenario = TrackCsvReader.read(scenarioFolder);
            loadedAnalysisScenario = scenario;
            model.clearBlackoutRegions();
            scenario.blackoutRegions().forEach(model::addBlackoutRegion);
            selectedBlackoutRegion = model.blackoutRegions().isEmpty()
                    ? null
                    : model.blackoutRegions().get(0);
            sensorParametersPanel.refreshBlackoutRegions();
            sensorParametersPanel.setSelectedBlackoutRegion(selectedBlackoutRegion);
            playback.loadRecordedScenario(scenario);
            playback.rewindReplayPaused();
            earthMapCanvas.focusOnPoints(playback.scenarioExtentPoints());
            timelinePanel.refresh();
            scenarioTimeLabel.setText(formatScenarioClock(0.0, scenario.durationSeconds()));
            long trackCount = scenario.records().stream()
                    .map(record -> record.trackId())
                    .distinct()
                    .count();
            statusLabel.setText("Loaded %s — %d track(s), %s"
                    .formatted(scenario.scenarioName(), trackCount,
                            formatClockTime(scenario.durationSeconds())));
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
        if (stitchingAnalysisPanel != null) {
            return;
        }
        stitchingAnalysisPanel = new TrackStitchingAnalysisPanel(
                loadedAnalysisScenario,
                playback,
                earthMapCanvas,
                timelinePanel,
                this::exitTrackStitchingAnalysis,
                statusLabel::setText);
        mapArea.add(stitchingAnalysisPanel.tabStrip(), BorderLayout.NORTH);
        remove(controlSidebar);
        add(stitchingAnalysisPanel, BorderLayout.EAST);
        revalidate();
        repaint();
        statusLabel.setText("Track stitching analysis view");
    }

    private void exitTrackStitchingAnalysis() {
        if (stitchingAnalysisPanel == null) {
            return;
        }
        stitchingAnalysisPanel.deactivate();
        mapArea.remove(stitchingAnalysisPanel.tabStrip());
        remove(stitchingAnalysisPanel);
        stitchingAnalysisPanel = null;
        add(controlSidebar, BorderLayout.EAST);
        timelinePanel.clearCandidateMarkers();
        earthMapCanvas.clearStitchingFocus();
        revalidate();
        repaint();
        if (analysisMode && loadedAnalysisScenario != null) {
            statusLabel.setText("Returned to analysis replay view");
        }
    }

    private static JSeparator verticalSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 24));
        return separator;
    }

    private static String formatScenarioClock(double elapsedSeconds) {
        return "t = " + formatClockTime(elapsedSeconds);
    }

    private static String formatScenarioClock(double elapsedSeconds, double durationSeconds) {
        return "t = " + formatClockTime(elapsedSeconds)
                + " / " + formatClockTime(durationSeconds);
    }

    private static String formatClockTime(double seconds) {
        if (!Double.isFinite(seconds)) {
            return "--:--";
        }
        int totalSeconds = Math.max(0, (int) Math.round(seconds));
        return "%02d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
    }

    private final class MoveToolIcon implements Icon {
        private static final int SIZE = 14;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                boolean selected = moveToolButton.isSelected();
                Color fill = selected
                        ? new Color(31, 138, 65)
                        : new Color(150, 157, 164);
                if (selected) {
                    g2.setColor(new Color(31, 138, 65, 65));
                    g2.fillOval(x, y, SIZE, SIZE);
                }
                g2.setColor(fill);
                g2.fillOval(x + 2, y + 2, SIZE - 4, SIZE - 4);
                g2.setStroke(new BasicStroke(1.0f));
                g2.setColor(selected
                        ? new Color(18, 83, 40, 160)
                        : new Color(96, 103, 111, 140));
                g2.drawOval(x + 2, y + 2, SIZE - 4, SIZE - 4);
            } finally {
                g2.dispose();
            }
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

    private final class ModifyToolIcon implements Icon {
        private static final int SIZE = 14;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                boolean selected = modifyToolButton.isSelected();
                Color color = selected
                        ? new Color(35, 118, 58)
                        : new Color(145, 153, 161);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g2.drawLine(x + 2, y + 10, x + 7, y + 4);
                g2.drawLine(x + 7, y + 4, x + 12, y + 9);
                g2.fillOval(x, y + 8, 4, 4);
                g2.fillOval(x + 5, y + 2, 5, 5);
                g2.fillOval(x + 10, y + 7, 4, 4);
            } finally {
                g2.dispose();
            }
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

    private final class TrajectoryArrowIcon implements Icon {
        private static final int SIZE = 14;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                boolean selected = trajectoryArrowButton.isSelected();
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g2.setColor(selected
                        ? new Color(25, 105, 160)
                        : new Color(150, 157, 164));
                int midY = y + SIZE / 2;
                g2.drawLine(x + 2, midY, x + SIZE - 4, midY);
                Polygon head = new Polygon(
                        new int[]{x + SIZE - 3, x + SIZE - 8, x + SIZE - 8},
                        new int[]{midY, midY - 4, midY + 4},
                        3);
                g2.fillPolygon(head);
            } finally {
                g2.dispose();
            }
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
