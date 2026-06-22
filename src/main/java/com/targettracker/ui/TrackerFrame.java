package com.targettracker.ui;

import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.PresetScenarioGenerator;
import com.targettracker.model.PresetScenarioParameters;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.ScenarioPreset;
import com.targettracker.model.SensorSettings;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.recording.TrackCsvRecorder;
import com.targettracker.tracking.ImmSettings;
import com.targettracker.tracking.ImmTracker;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.SwingConstants;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;

public final class TrackerFrame extends JFrame {
    private final ScenarioModel model = new ScenarioModel();
    private final SensorSettings sensorSettings = new SensorSettings();
    private final ImmSettings immSettings = new ImmSettings();
    private final MeasurementEngine measurementEngine;
    private final ImmTracker immTracker;
    private final TrackCsvRecorder recorder = new TrackCsvRecorder();
    private final ScenarioPlayback playback;
    private final EarthMapCanvas earthMapCanvas;
    private final TargetInspectorPanel inspectorPanel;
    private final ProfileWindow profileWindow;
    private final SensorWindow sensorWindow;
    private final ImmWindow immWindow;
    private final RecordingPanel recordingPanel;
    private final PresetScenarioPanel presetScenarioPanel;

    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel scenarioTimeLabel = new JLabel("t = 0.0 s");
    private final JButton pauseButton = new JButton("Pause");
    private final JButton newTargetButton = new JButton("New target");
    private final JButton clearPathButton = new JButton("Clear path");
    private TargetTrajectory selectedTarget;
    private boolean presetScenarioActive;

    public TrackerFrame() {
        super("ECEF Target Tracker");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setSize(1120, 760);

        selectedTarget = model.addTarget();
        measurementEngine = new MeasurementEngine(model, sensorSettings);
        immTracker = new ImmTracker(immSettings);
        playback = new ScenarioPlayback(
                model, this::onPlaybackUpdated, measurementEngine, immTracker, recorder);
        recordingPanel = new RecordingPanel(this, recorder, playback);
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
                immTracker,
                () -> selectedTarget,
                this::isScenarioEditingLocked,
                this::onPathChanged,
                this::onCursorChanged);
        inspectorPanel = new TargetInspectorPanel(
                model, this::showSensorWindow, this::showImmWindow, this::selectTarget);
        profileWindow = new ProfileWindow(
                this,
                () -> selectedTarget,
                playback,
                this::isScenarioEditingLocked,
                this::onProfileChanged);
        sensorWindow = new SensorWindow(this, sensorSettings, this::onSensorParametersChanged);
        immWindow = new ImmWindow(this, immSettings, this::onImmParametersChanged);

        setLayout(new BorderLayout());
        add(createHeader(), BorderLayout.NORTH);
        add(earthMapCanvas, BorderLayout.CENTER);
        add(inspectorPanel, BorderLayout.EAST);
        add(createStatusBar(), BorderLayout.SOUTH);

        inspectorPanel.setSelectedTarget(selectedTarget);
        refreshTelemetry();
        setLocationRelativeTo(null);
    }

    public void showProfileWindow() {
        positionProfileWindow();
        profileWindow.setVisible(true);
        profileWindow.refresh(selectedTarget);
    }

    private JPanel createHeader() {
        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(Color.WHITE);
        container.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(214, 220, 227)));

        JPanel titleRow = new JPanel(new BorderLayout());
        titleRow.setOpaque(false);
        titleRow.setBorder(BorderFactory.createEmptyBorder(10, 14, 4, 14));
        JLabel title = new JLabel("ECEF Target Tracker");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19.0f));
        titleRow.add(title, BorderLayout.WEST);
        JLabel frameLabel = new JLabel("WGS-84 ECEF • Plate Carrée view • Ellipsoidal altitude");
        frameLabel.setForeground(new Color(91, 103, 115));
        titleRow.add(frameLabel, BorderLayout.EAST);
        container.add(titleRow);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 7));
        toolbar.setOpaque(false);

        newTargetButton.addActionListener(event -> addTarget());
        toolbar.add(newTargetButton);

        toolbar.add(verticalSeparator());
        toolbar.add(new JLabel("Drawing:"));
        JComboBox<String> drawingMode = new JComboBox<>(new String[]{"Free-hand", "Segmented line"});
        drawingMode.addActionListener(event -> earthMapCanvas.setDrawingMode(
                drawingMode.getSelectedIndex() == 0
                        ? EarthMapCanvas.DrawingMode.FREE_HAND
                        : EarthMapCanvas.DrawingMode.SEGMENTED));
        toolbar.add(drawingMode);

        JButton finishButton = new JButton("Finish path");
        finishButton.addActionListener(event -> earthMapCanvas.finishPath());
        toolbar.add(finishButton);

        clearPathButton.addActionListener(event -> clearSelectedPath());
        toolbar.add(clearPathButton);

        JButton profilesButton = new JButton("Profiles…");
        profilesButton.addActionListener(event -> showProfileWindow());
        toolbar.add(profilesButton);

        JButton sensorButton = new JButton("Sensor…");
        sensorButton.addActionListener(event -> showSensorWindow());
        toolbar.add(sensorButton);

        JButton immButton = new JButton("IMM…");
        immButton.addActionListener(event -> showImmWindow());
        toolbar.add(immButton);

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
        JButton runButton = new JButton("Run scenario");
        runButton.addActionListener(event -> runScenario());
        toolbar.add(runButton);

        pauseButton.setEnabled(false);
        pauseButton.addActionListener(event -> playback.togglePause());
        toolbar.add(pauseButton);

        JButton resetScenarioButton = new JButton("Reset");
        resetScenarioButton.setToolTipText("Rewind to t = 0 and pause");
        resetScenarioButton.addActionListener(event -> resetScenario());
        toolbar.add(resetScenarioButton);

        container.add(toolbar);
        container.add(presetScenarioPanel);
        container.add(recordingPanel);
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
        if (presetScenarioActive) {
            return;
        }
        playback.reset();
        TargetTrajectory target = model.addTarget();
        inspectorPanel.targetAdded(target);
        selectTarget(target);
        statusLabel.setText("Created %s — draw its trajectory".formatted(target.id()));
    }

    private void selectTarget(TargetTrajectory target) {
        if (target == null) {
            return;
        }
        selectedTarget = target;
        inspectorPanel.setSelectedTarget(target);
        profileWindow.refresh(target);
        refreshTelemetry();
        earthMapCanvas.repaint();
    }

    private void clearSelectedPath() {
        if (presetScenarioActive) {
            return;
        }
        TargetTrajectory target = inspectorPanel.selectedTarget();
        if (target == null) {
            return;
        }
        selectedTarget = target;
        clearPathForTarget(playback, target);
        earthMapCanvas.finishPath();
        inspectorPanel.setSelectedTarget(target);
        statusLabel.setText("Path cleared for %s".formatted(target.id()));
        refreshTelemetry();
        earthMapCanvas.repaint();
    }

    static void clearPathForTarget(ScenarioPlayback playback, TargetTrajectory target) {
        playback.reset();
        target.clearPath();
    }

    private void loadPresetScenario(
            ScenarioPreset preset,
            PresetScenarioParameters parameters) {
        playback.reset();
        PresetScenarioGenerator.generate(model, preset, parameters);
        presetScenarioActive = true;
        updateStructuralEditingControls();
        selectedTarget = model.targets().get(0);
        inspectorPanel.replaceTargets(model.targets(), selectedTarget);
        profileWindow.refresh(selectedTarget);
        earthMapCanvas.focusOnTargets();
        refreshTelemetry();
        scenarioTimeLabel.setText("t = 0.0 / %.1f s".formatted(model.durationSeconds()));
        statusLabel.setText("Loaded %s — preset target structure is locked".formatted(preset));
    }

    private void activateUserGeneratedMode() {
        if (!presetScenarioActive) {
            return;
        }
        playback.reset();
        presetScenarioActive = false;
        updateStructuralEditingControls();
        selectedTarget = model.replaceTargets(1).get(0);
        inspectorPanel.replaceTargets(model.targets(), selectedTarget);
        profileWindow.refresh(selectedTarget);
        earthMapCanvas.resetView();
        refreshTelemetry();
        scenarioTimeLabel.setText("t = 0.0 s");
        statusLabel.setText("User-generated mode — draw a target trajectory");
    }

    private boolean isScenarioEditingLocked() {
        return playback.isRunning() || presetScenarioActive;
    }

    private void updateStructuralEditingControls() {
        newTargetButton.setEnabled(!presetScenarioActive);
        clearPathButton.setEnabled(!presetScenarioActive);
    }

    private void runScenario() {
        if (!validateSensorParameters()
                || !validateImmParameters()
                || !recordingPanel.commitParentFolder()) {
            return;
        }
        earthMapCanvas.finishPath();
        if (!playback.start()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Draw at least one two-point trajectory with a non-zero velocity profile first.",
                    "Scenario is not ready",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        statusLabel.setText("Scenario running");
    }

    private void resetScenario() {
        if (!validateSensorParameters()
                || !validateImmParameters()
                || !recordingPanel.commitParentFolder()) {
            return;
        }
        earthMapCanvas.finishPath();
        if (!playback.rewindPaused()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Draw at least one two-point trajectory with a non-zero velocity profile first.",
                    "Scenario is not ready",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        statusLabel.setText("Scenario reset to t = 0.0 s — press Resume to continue");
    }

    private void onPathChanged() {
        if (selectedTarget == null) {
            return;
        }
        resetCompletedPlayback();
        refreshTelemetry();
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
        earthMapCanvas.repaint();
        statusLabel.setText("Updated motion profile for %s".formatted(selectedTarget.id()));
    }

    private void onCursorChanged(GeodeticPoint point) {
        if (!playback.isRunning()) {
            statusLabel.setText("Cursor  Lat %.5f°   Lon %.5f°   View %s".formatted(
                    point.latitudeDegrees(), point.longitudeDegrees(), earthMapCanvas.viewDescription()));
        }
    }

    private void onPlaybackUpdated() {
        earthMapCanvas.repaint();
        profileWindow.refresh(selectedTarget);
        refreshTelemetry();
        scenarioTimeLabel.setText("t = %.1f / %.1f s".formatted(
                playback.elapsedSeconds(), model.durationSeconds()));
        pauseButton.setEnabled(playback.isRunning());
        pauseButton.setText(playback.isPaused() ? "Resume" : "Pause");
        recordingPanel.refresh();
        if (playback.isPaused()) {
            statusLabel.setText("Scenario paused");
        } else if (playback.isRunning()) {
            statusLabel.setText("Scenario running");
        } else if (playback.elapsedSeconds() > 0.0
                && playback.elapsedSeconds() >= model.durationSeconds()) {
            statusLabel.setText("Scenario complete");
        }
    }

    private void refreshTelemetry() {
        inspectorPanel.refresh(selectedTarget, playback);
        profileWindow.refresh(selectedTarget);
    }

    private void positionProfileWindow() {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        Rectangle deviceScreen = configuration.getBounds();
        Insets deviceInsets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        double scaleX = configuration.getDefaultTransform().getScaleX();
        double scaleY = configuration.getDefaultTransform().getScaleY();
        Rectangle screen = new Rectangle(
                (int) Math.round(deviceScreen.x / scaleX),
                (int) Math.round(deviceScreen.y / scaleY),
                (int) Math.round(deviceScreen.width / scaleX),
                (int) Math.round(deviceScreen.height / scaleY));
        Insets insets = new Insets(
                (int) Math.round(deviceInsets.top / scaleY),
                (int) Math.round(deviceInsets.left / scaleX),
                (int) Math.round(deviceInsets.bottom / scaleY),
                (int) Math.round(deviceInsets.right / scaleX));
        Rectangle usable = new Rectangle(
                screen.x + insets.left,
                screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom);

        int gap = 8;
        int maximumMainWidth = usable.width - profileWindow.getWidth() - gap;
        if (getWidth() + profileWindow.getWidth() + gap > usable.width
                && maximumMainWidth >= getMinimumSize().width) {
            setSize(maximumMainWidth, Math.min(getHeight(), usable.height));
        }

        int combinedWidth = getWidth() + gap + profileWindow.getWidth();
        if (combinedWidth <= usable.width) {
            int mainX = usable.x + (usable.width - combinedWidth) / 2;
            int mainY = Math.max(usable.y, Math.min(getY(), usable.y + usable.height - getHeight()));
            setLocation(mainX, mainY);
            int profileY = Math.max(
                    usable.y,
                    Math.min(mainY, usable.y + usable.height - profileWindow.getHeight()));
            profileWindow.setLocation(mainX + getWidth() + gap, profileY);
            return;
        }

        int x = Math.max(usable.x,
                Math.min(getX() + getWidth() - profileWindow.getWidth(),
                        usable.x + usable.width - profileWindow.getWidth()));
        int y = Math.max(usable.y,
                Math.min(getY(), usable.y + usable.height - profileWindow.getHeight()));
        profileWindow.setLocation(x, y);
    }

    private void resetCompletedPlayback() {
        if (!playback.isRunning() && playback.elapsedSeconds() > 0.0) {
            playback.reset();
        }
    }

    private boolean validateSensorParameters() {
        if (sensorWindow.commitSensorParameters()) {
            return true;
        }
        showSensorWindow();
        JOptionPane.showMessageDialog(
                this,
                "Correct the highlighted God sensor parameters before running the scenario.",
                "Invalid sensor parameters",
                JOptionPane.WARNING_MESSAGE);
        return false;
    }

    public void showSensorWindow() {
        sensorWindow.setLocation(getX() + 20, getY() + 40);
        sensorWindow.setVisible(true);
        sensorWindow.toFront();
        sensorWindow.requestFocus();
    }

    private void onSensorParametersChanged() {
        measurementEngine.parametersChanged(playback.elapsedSeconds());
        earthMapCanvas.repaint();
    }

    public void showImmWindow() {
        int rightAlignedX = getX() + Math.max(
                20, getWidth() - immWindow.getWidth() - 20);
        immWindow.setLocation(rightAlignedX, getY() + 30);
        immWindow.setVisible(true);
        immWindow.toFront();
        immWindow.requestFocus();
    }

    private void onImmParametersChanged() {
        immTracker.parametersChanged(playback.elapsedSeconds());
        earthMapCanvas.repaint();
    }

    private boolean validateImmParameters() {
        if (immWindow.commitParameters()) {
            return true;
        }
        showImmWindow();
        JOptionPane.showMessageDialog(
                this,
                "Correct the highlighted IMM tracker parameters before running the scenario.",
                "Invalid IMM parameters",
                JOptionPane.WARNING_MESSAGE);
        return false;
    }

    private static JSeparator verticalSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 24));
        return separator;
    }
}
