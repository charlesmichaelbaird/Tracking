package com.targettracker.ui;

import com.targettracker.model.EnuFrame;
import com.targettracker.model.EnuPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;

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
    private final ScenarioModel model = new ScenarioModel(EnuFrame.defaultFrame());
    private final ScenarioPlayback playback;
    private final TrajectoryCanvas trajectoryCanvas;
    private final TargetInspectorPanel inspectorPanel;
    private final ProfileWindow profileWindow;

    private final JLabel statusLabel = new JLabel("Ready");
    private final JLabel scenarioTimeLabel = new JLabel("t = 0.0 s");
    private final JButton pauseButton = new JButton("Pause");
    private TargetTrajectory selectedTarget;

    public TrackerFrame() {
        super("ENU Target Tracker");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 620));
        setSize(1120, 760);

        selectedTarget = model.addTarget();
        playback = new ScenarioPlayback(model, this::onPlaybackUpdated);
        trajectoryCanvas = new TrajectoryCanvas(
                model,
                playback,
                () -> selectedTarget,
                playback::isRunning,
                this::onPathChanged,
                this::onCursorChanged);
        inspectorPanel = new TargetInspectorPanel(model, this::selectTarget);
        profileWindow = new ProfileWindow(
                this,
                () -> selectedTarget,
                playback,
                playback::isRunning,
                this::onProfileChanged);

        setLayout(new BorderLayout());
        add(createHeader(), BorderLayout.NORTH);
        add(trajectoryCanvas, BorderLayout.CENTER);
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
        JLabel title = new JLabel("ENU Target Tracker");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19.0f));
        titleRow.add(title, BorderLayout.WEST);
        JLabel frameLabel = new JLabel("Local frame: E/N ±5,000 m • Up from altitude profile");
        frameLabel.setForeground(new Color(91, 103, 115));
        titleRow.add(frameLabel, BorderLayout.EAST);
        container.add(titleRow);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 7));
        toolbar.setOpaque(false);

        JButton newTargetButton = new JButton("New target");
        newTargetButton.addActionListener(event -> addTarget());
        toolbar.add(newTargetButton);

        toolbar.add(verticalSeparator());
        toolbar.add(new JLabel("Drawing:"));
        JComboBox<String> drawingMode = new JComboBox<>(new String[]{"Free-hand", "Segmented line"});
        drawingMode.addActionListener(event -> trajectoryCanvas.setDrawingMode(
                drawingMode.getSelectedIndex() == 0
                        ? TrajectoryCanvas.DrawingMode.FREE_HAND
                        : TrajectoryCanvas.DrawingMode.SEGMENTED));
        toolbar.add(drawingMode);

        JButton finishButton = new JButton("Finish path");
        finishButton.addActionListener(event -> trajectoryCanvas.finishPath());
        toolbar.add(finishButton);

        JButton clearButton = new JButton("Clear path");
        clearButton.addActionListener(event -> clearSelectedPath());
        toolbar.add(clearButton);

        JButton profilesButton = new JButton("Profiles…");
        profilesButton.addActionListener(event -> showProfileWindow());
        toolbar.add(profilesButton);

        toolbar.add(verticalSeparator());
        JButton runButton = new JButton("Run scenario");
        runButton.addActionListener(event -> runScenario());
        toolbar.add(runButton);

        pauseButton.setEnabled(false);
        pauseButton.addActionListener(event -> playback.togglePause());
        toolbar.add(pauseButton);

        container.add(toolbar);
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
        trajectoryCanvas.repaint();
    }

    private void clearSelectedPath() {
        if (playback.isRunning() || selectedTarget == null) {
            return;
        }
        playback.reset();
        selectedTarget.clearPath();
        trajectoryCanvas.finishPath();
        statusLabel.setText("Path cleared for %s".formatted(selectedTarget.id()));
        refreshTelemetry();
    }

    private void runScenario() {
        trajectoryCanvas.finishPath();
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

    private void onPathChanged() {
        if (selectedTarget == null) {
            return;
        }
        resetCompletedPlayback();
        refreshTelemetry();
        statusLabel.setText(selectedTarget.path().size() < 2
                ? "%s: add another point".formatted(selectedTarget.id())
                : "%s: %d path points, %,.0f m".formatted(
                        selectedTarget.id(),
                        selectedTarget.path().size(),
                        selectedTarget.horizontalLengthMeters()));
    }

    private void onProfileChanged() {
        resetCompletedPlayback();
        refreshTelemetry();
        trajectoryCanvas.repaint();
        statusLabel.setText("Updated motion profile for %s".formatted(selectedTarget.id()));
    }

    private void onCursorChanged(EnuPoint point) {
        if (!playback.isRunning()) {
            statusLabel.setText("Cursor  E %,.0f m   N %,.0f m".formatted(point.east(), point.north()));
        }
    }

    private void onPlaybackUpdated() {
        trajectoryCanvas.repaint();
        profileWindow.refresh(selectedTarget);
        refreshTelemetry();
        scenarioTimeLabel.setText("t = %.1f / %.1f s".formatted(
                playback.elapsedSeconds(), model.durationSeconds()));
        pauseButton.setEnabled(playback.isRunning());
        pauseButton.setText(playback.isPaused() ? "Resume" : "Pause");
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
        Rectangle screen = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        Rectangle usable = new Rectangle(
                screen.x + insets.left,
                screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom);

        int proposedX = getX() + getWidth() + 8;
        int x;
        if (proposedX + profileWindow.getWidth() <= usable.x + usable.width) {
            x = proposedX;
        } else if (getWidth() + 8 + profileWindow.getWidth() <= usable.width) {
            int combinedWidth = getWidth() + 8 + profileWindow.getWidth();
            int mainX = usable.x + (usable.width - combinedWidth) / 2;
            int mainY = Math.max(usable.y, Math.min(getY(), usable.y + usable.height - getHeight()));
            setLocation(mainX, mainY);
            x = mainX + getWidth() + 8;
        } else {
            x = Math.max(usable.x, getX() + getWidth() - profileWindow.getWidth() - 24);
        }
        int y = Math.max(usable.y, Math.min(getY(), usable.y + usable.height - profileWindow.getHeight()));
        profileWindow.setLocation(x, y);
    }

    private void resetCompletedPlayback() {
        if (!playback.isRunning() && playback.elapsedSeconds() > 0.0) {
            playback.reset();
        }
    }

    private static JSeparator verticalSeparator() {
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setPreferredSize(new Dimension(1, 24));
        return separator;
    }
}
