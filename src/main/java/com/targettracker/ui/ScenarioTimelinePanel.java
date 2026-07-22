package com.targettracker.ui;

import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.recording.TrackCsvRecorder;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.Timer;
import java.awt.Adjustable;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Combined target availability rows and seekable scenario replay ruler. */
final class ScenarioTimelinePanel extends JPanel {
    private static final int VISIBLE_TARGET_ROWS = 3;
    private static final int CANVAS_HEIGHT = 126;
    private static final int PANEL_EXTRA_HEIGHT = 34;

    private final ScenarioModel model;
    private final ScenarioPlayback playback;
    private final TrackCsvRecorder recorder;
    private final BooleanSupplier runWindowEditingLocked;
    private final Runnable onRunWindowChanged;
    private final JLabel stateLabel = new JLabel();
    private final TimelineCanvas canvas = new TimelineCanvas();
    private final JScrollBar targetScrollBar = new JScrollBar(Adjustable.VERTICAL);
    private List<Double> candidateMarkerTimes = List.of();
    private double selectedCandidateTime = Double.NaN;

    ScenarioTimelinePanel(
            ScenarioModel model,
            ScenarioPlayback playback,
            TrackCsvRecorder recorder) {
        this(model, playback, recorder, () -> true, () -> {
        });
    }

    ScenarioTimelinePanel(
            ScenarioModel model,
            ScenarioPlayback playback,
            TrackCsvRecorder recorder,
            BooleanSupplier runWindowEditingLocked,
            Runnable onRunWindowChanged) {
        super(new BorderLayout(8, 0));
        this.model = model;
        this.playback = playback;
        this.recorder = recorder;
        this.runWindowEditingLocked = runWindowEditingLocked;
        this.onRunWindowChanged = onRunWindowChanged;
        AppTheme.setRole(this, AppTheme.ROLE_STATUS);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, AppTheme.current().border()),
                BorderFactory.createEmptyBorder(5, 10, 4, 10)));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        controls.setOpaque(false);
        JLabel title = new JLabel("Scenario timeline");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        controls.add(title);
        stateLabel.setForeground(AppTheme.current().mutedText());
        controls.add(stateLabel);
        add(controls, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);

        targetScrollBar.setUnitIncrement(1);
        targetScrollBar.setBlockIncrement(VISIBLE_TARGET_ROWS);
        targetScrollBar.setVisible(false);
        targetScrollBar.addAdjustmentListener(event -> canvas.repaint());
        add(targetScrollBar, BorderLayout.EAST);
        refresh();
    }

    void refresh() {
        int targetCount = model.targets().size();
        canvas.setPreferredSize(new Dimension(700, CANVAS_HEIGHT));
        setPreferredSize(new Dimension(700, CANVAS_HEIGHT + PANEL_EXTRA_HEIGHT));
        updateTargetScrollBar(targetCount);
        boolean hasScenario = durationForScale() > 0.0;
        canvas.setSeekEnabled(playback.canSeek());
        stateLabel.setForeground(AppTheme.current().mutedText());

        if (recorder.isActive()) {
            stateLabel.setText("Track files are being written - seeking temporarily disabled");
        } else if (playback.isComputing()) {
            stateLabel.setText("Pre-computing sensor and tracker history...");
        } else if (playback.isRunning()) {
            stateLabel.setText("Replay playing - drag the black time bar to seek");
        } else if (playback.isReplayReady()) {
            stateLabel.setText("Replay ready - drag the black time bar");
        } else if (hasScenario) {
            stateLabel.setText("Drag the grey run window, then pre-compute for replay");
        } else {
            stateLabel.setText("Create a runnable scenario first");
        }
        revalidate();
        canvas.repaint();
    }

    private void updateTargetScrollBar(int targetCount) {
        int maximum = Math.max(VISIBLE_TARGET_ROWS, targetCount);
        int maxValue = Math.max(0, targetCount - VISIBLE_TARGET_ROWS);
        int value = Math.min(targetScrollBar.getValue(), maxValue);
        targetScrollBar.setValues(value, VISIBLE_TARGET_ROWS, 0, maximum);
        targetScrollBar.setVisible(targetCount > VISIBLE_TARGET_ROWS);
    }

    void refreshTheme() {
        AppTheme.setRole(this, AppTheme.ROLE_STATUS);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, AppTheme.current().border()),
                BorderFactory.createEmptyBorder(5, 10, 4, 10)));
        refresh();
    }

    void setCandidateMarkers(List<Double> times, double selectedTime) {
        candidateMarkerTimes = times == null
                ? List.of()
                : times.stream()
                .filter(Double::isFinite)
                .distinct()
                .sorted()
                .toList();
        selectedCandidateTime = selectedTime;
        canvas.repaint();
    }

    void clearCandidateMarkers() {
        candidateMarkerTimes = List.of();
        selectedCandidateTime = Double.NaN;
        canvas.repaint();
    }

    private double durationForScale() {
        double playbackDuration = playback.durationSeconds();
        return playbackDuration > 0.0 ? playbackDuration : model.durationSeconds();
    }

    private final class TimelineCanvas extends JComponent {
        private static final int LEFT = 78;
        private static final int RIGHT = 18;
        private static final int TOP = 20;
        private static final int HANDLE_WIDTH = 9;
        private static final int SEEK_COALESCE_MILLIS = 35;

        private final Timer seekTimer;
        private DragMode dragMode = DragMode.NONE;
        private double pendingSeekSeconds;
        private double dragAnchorTime;
        private double dragStartSeconds;
        private double dragStopSeconds;
        private boolean seekEnabled;
        private boolean seekPending;

        TimelineCanvas() {
            setToolTipText("Drag handles or the grey window to set run time; drag ruler to seek");
            seekTimer = new Timer(SEEK_COALESCE_MILLIS, event -> flushPendingSeek());
            seekTimer.setRepeats(false);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    dragMode = modeAt(event.getPoint());
                    if (dragMode == DragMode.START || dragMode == DragMode.STOP) {
                        applyRunHandleDrag(event.getX());
                    } else if (dragMode == DragMode.WINDOW) {
                        dragAnchorTime = timeAt(event.getX());
                        dragStartSeconds = model.runStartSeconds();
                        dragStopSeconds = model.runStopSeconds();
                    } else if (dragMode == DragMode.SEEK) {
                        seekImmediately(event.getX());
                    }
                    updateCursor(event.getPoint());
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    if (dragMode == DragMode.START || dragMode == DragMode.STOP) {
                        applyRunHandleDrag(event.getX());
                    } else if (dragMode == DragMode.WINDOW) {
                        applyWindowDrag(event.getX());
                    } else if (dragMode == DragMode.SEEK) {
                        queueSeek(event.getX());
                    }
                    updateCursor(event.getPoint());
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (dragMode == DragMode.SEEK) {
                        queueSeek(event.getX());
                        flushPendingSeek();
                    }
                    dragMode = DragMode.NONE;
                    updateCursor(event.getPoint());
                }

                @Override
                public void mouseMoved(MouseEvent event) {
                    updateCursor(event.getPoint());
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    setCursor(Cursor.getDefaultCursor());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(event -> {
                if (!targetScrollBar.isVisible()) {
                    return;
                }
                int maximumValue = Math.max(
                        targetScrollBar.getMinimum(),
                        targetScrollBar.getMaximum() - targetScrollBar.getVisibleAmount());
                int value = Math.max(
                        targetScrollBar.getMinimum(),
                        Math.min(maximumValue,
                                targetScrollBar.getValue() + event.getWheelRotation()));
                if (value != targetScrollBar.getValue()) {
                    targetScrollBar.setValue(value);
                    event.consume();
                }
            });
        }

        void setSeekEnabled(boolean enabled) {
            seekEnabled = enabled;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                draw(g);
            } finally {
                g.dispose();
            }
        }

        private void draw(Graphics2D g) {
            AppTheme.Palette palette = AppTheme.current();
            double duration = durationForScale();
            int width = trackWidth();
            int rulerBaseline = rulerBaseline();

            g.setColor(palette.statusBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(palette.chartGrid());
            g.drawRect(LEFT, TOP, width, Math.max(1, rulerBaseline - TOP + 8));

            g.setFont(g.getFont().deriveFont(Font.BOLD, 11.0f));
            g.setColor(palette.text());
            g.drawString("Targets", 12, 15);

            if (duration <= 0.0) {
                g.setFont(g.getFont().deriveFont(Font.PLAIN, 10.0f));
                g.setColor(palette.mutedText());
                g.drawString("Draw a runnable target to define scenario time", LEFT + 8, TOP + 24);
                return;
            }

            boolean hasRunWindow = model.durationSeconds() > 0.0;
            if (hasRunWindow) {
                drawRunWindow(g, duration);
            }
            drawTargets(g, duration);
            drawRuler(g, duration);
            drawCandidateMarkers(g, duration);
            drawPlayhead(g, duration);
            if (hasRunWindow) {
                drawRunHandles(g, duration);
            }
        }

        private void drawRunWindow(Graphics2D g, double duration) {
            AppTheme.Palette palette = AppTheme.current();
            int startX = xForTime(model.runStartSeconds(), duration);
            int stopX = xForTime(model.runStopSeconds(), duration);
            int top = runWindowTop();
            int height = runWindowHeight();

            g.setColor(withAlpha(palette.mutedText(), AppTheme.isDarkMode() ? 48 : 38));
            g.fillRect(startX, top, Math.max(1, stopX - startX), height);
            g.setColor(withAlpha(palette.text(), 85));
            g.drawRect(startX, top, Math.max(1, stopX - startX), height);
        }

        private void drawTargets(Graphics2D g, double duration) {
            List<TargetTrajectory> targets = model.targets();
            int firstTarget = targetScrollBar.isVisible() ? targetScrollBar.getValue() : 0;
            FontMetrics metrics = g.getFontMetrics();
            Shape oldClip = g.getClip();
            Stroke oldStroke = g.getStroke();
            g.clipRect(0, targetViewportTop(), getWidth(), targetViewportHeight());
            try {
                for (int index = firstTarget;
                        index < targets.size()
                                && index < firstTarget + VISIBLE_TARGET_ROWS;
                        index++) {
                    TargetTrajectory target = targets.get(index);
                    int y = targetRowY(index - firstTarget);
                    g.setColor(AppTheme.current().mutedText());
                    g.drawString(
                            target.id(),
                            Math.max(4, LEFT - metrics.stringWidth(target.id()) - 8),
                            y + 4);
                    int startX = xForTime(0.0, duration);
                    int stopX = xForTime(Math.min(duration, target.durationSeconds()), duration);
                    g.setColor(withAlpha(target.color(), target.isRunnable() ? 230 : 70));
                    g.setStroke(new BasicStroke(
                            target.isRunnable() ? 5.0f : 2.0f,
                            BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND));
                    g.drawLine(startX, y, Math.max(startX, stopX), y);
                }
            } finally {
                g.setClip(oldClip);
                g.setStroke(oldStroke);
            }
        }

        private void drawRuler(Graphics2D g, double duration) {
            AppTheme.Palette palette = AppTheme.current();
            int width = trackWidth();
            int baseline = rulerBaseline();
            g.setColor(seekEnabled ? palette.text() : palette.mutedText());
            g.setStroke(new BasicStroke(1.4f));
            g.drawLine(LEFT, baseline, LEFT + width, baseline);

            double majorStep = niceTimeStep(Math.max(1.0, duration / 7.0));
            g.setFont(g.getFont().deriveFont(Font.PLAIN, 10.0f));
            FontMetrics metrics = g.getFontMetrics();
            for (double time = 0.0; time < duration - 1.0e-9; time += majorStep) {
                int x = xForTime(time, duration);
                g.drawLine(x, baseline - 6, x, baseline + 6);
                String label = formatTime(time);
                int labelX = Math.max(0,
                        Math.min(getWidth() - metrics.stringWidth(label),
                                x - metrics.stringWidth(label) / 2));
                g.drawString(label, labelX, baseline + 20);
                double minorStep = majorStep / 5.0;
                for (int minor = 1; minor < 5; minor++) {
                    double minorTime = time + minor * minorStep;
                    if (minorTime >= duration) {
                        break;
                    }
                    int minorX = xForTime(minorTime, duration);
                    g.drawLine(minorX, baseline - 3, minorX, baseline + 3);
                }
            }
            int endX = LEFT + width;
            g.drawLine(endX, baseline - 7, endX, baseline + 7);
            String endLabel = formatTime(duration);
            g.drawString(endLabel, getWidth() - metrics.stringWidth(endLabel), baseline + 20);
        }

        private void drawCandidateMarkers(Graphics2D g, double duration) {
            if (candidateMarkerTimes.isEmpty()) {
                return;
            }
            Stroke oldStroke = g.getStroke();
            int baseline = rulerBaseline();
            for (double time : candidateMarkerTimes) {
                if (time < -1.0e-9 || time > duration + 1.0e-9) {
                    continue;
                }
                boolean selected = Math.abs(time - selectedCandidateTime) <= 1.0e-6;
                int x = xForTime(time, duration);
                g.setColor(selected ? new Color(255, 0, 0) : new Color(255, 35, 35, 210));
                g.setStroke(new BasicStroke(selected ? 3.2f : 2.1f));
                g.drawLine(x, TOP, x, baseline + 12);
            }
            g.setStroke(oldStroke);
        }

        private void drawPlayhead(Graphics2D g, double duration) {
            AppTheme.Palette palette = AppTheme.current();
            int playheadX = xForTime(playback.elapsedSeconds(), duration);
            int baseline = rulerBaseline();
            g.setColor(palette.text());
            g.fillRect(playheadX - 2, baseline - 18, 4, 26);
            int[] triangleX = {playheadX - 5, playheadX + 5, playheadX};
            int[] triangleY = {baseline - 18, baseline - 18, baseline - 12};
            g.fillPolygon(triangleX, triangleY, 3);
        }

        private void drawRunHandles(Graphics2D g, double duration) {
            int top = runWindowTop();
            int height = runWindowHeight();
            drawHandle(g, xForTime(model.runStartSeconds(), duration), top, height);
            drawHandle(g, xForTime(model.runStopSeconds(), duration), top, height);
        }

        private void drawHandle(Graphics2D g, int x, int top, int height) {
            AppTheme.Palette palette = AppTheme.current();
            int handleX = x - HANDLE_WIDTH / 2;
            g.setColor(withAlpha(palette.card(), 225));
            g.fillRoundRect(handleX, top + 1, HANDLE_WIDTH, height - 2, 5, 5);
            g.setColor(palette.border());
            g.drawRoundRect(handleX, top + 1, HANDLE_WIDTH, height - 2, 5, 5);
        }

        private DragMode modeAt(Point point) {
            double duration = durationForScale();
            if (duration <= 0.0 || point == null) {
                return DragMode.NONE;
            }
            if (!runWindowEditingLocked.getAsBoolean() && model.durationSeconds() > 0.0) {
                int startX = xForTime(model.runStartSeconds(), duration);
                int stopX = xForTime(model.runStopSeconds(), duration);
                boolean inRunWindowBand = point.y >= runWindowTop()
                        && point.y <= runWindowTop() + runWindowHeight();
                if (inRunWindowBand) {
                    if (Math.abs(point.x - startX) <= HANDLE_WIDTH + 3) {
                        return DragMode.START;
                    }
                    if (Math.abs(point.x - stopX) <= HANDLE_WIDTH + 3) {
                        return DragMode.STOP;
                    }
                    if (point.x > startX && point.x < stopX) {
                        return DragMode.WINDOW;
                    }
                }
            }
            return seekEnabled && playback.canSeek() ? DragMode.SEEK : DragMode.NONE;
        }

        private void applyRunHandleDrag(int mouseX) {
            double time = timeAt(mouseX);
            double start = model.runStartSeconds();
            double stop = model.runStopSeconds();
            if (dragMode == DragMode.START) {
                model.setRunWindowSeconds(Math.min(time, stop), stop);
            } else if (dragMode == DragMode.STOP) {
                model.setRunWindowSeconds(start, Math.max(time, start));
            }
            onRunWindowChanged.run();
            repaint();
        }

        private void applyWindowDrag(int mouseX) {
            double duration = model.durationSeconds();
            double windowLength = Math.max(0.0, dragStopSeconds - dragStartSeconds);
            if (duration <= 0.0 || windowLength <= 0.0) {
                return;
            }
            double delta = timeAt(mouseX) - dragAnchorTime;
            double start = dragStartSeconds + delta;
            start = Math.max(0.0, Math.min(start, duration - windowLength));
            model.setRunWindowSeconds(start, start + windowLength);
            onRunWindowChanged.run();
            repaint();
        }

        private void seekImmediately(int mouseX) {
            if (!seekEnabled || !playback.canSeek()) {
                return;
            }
            seekPending = false;
            seekTimer.stop();
            playback.seekTo(timeAt(mouseX));
        }

        private void queueSeek(int mouseX) {
            if (!seekEnabled || !playback.canSeek()) {
                return;
            }
            pendingSeekSeconds = timeAt(mouseX);
            seekPending = true;
            if (!seekTimer.isRunning()) {
                seekTimer.start();
            }
        }

        private void flushPendingSeek() {
            if (!seekPending || !seekEnabled || !playback.canSeek()) {
                seekPending = false;
                seekTimer.stop();
                return;
            }
            seekPending = false;
            seekTimer.stop();
            playback.seekTo(pendingSeekSeconds);
        }

        private void updateCursor(Point point) {
            DragMode mode = modeAt(point);
            int cursor = switch (mode) {
                case START, STOP -> Cursor.E_RESIZE_CURSOR;
                case WINDOW -> Cursor.MOVE_CURSOR;
                case SEEK -> Cursor.HAND_CURSOR;
                case NONE -> Cursor.DEFAULT_CURSOR;
            };
            setCursor(Cursor.getPredefinedCursor(cursor));
        }

        private int xForTime(double seconds, double duration) {
            double fraction = duration <= 0.0
                    ? 0.0
                    : Math.max(0.0, Math.min(1.0, seconds / duration));
            return LEFT + (int) Math.round(fraction * trackWidth());
        }

        private double timeAt(int mouseX) {
            double duration = durationForScale();
            double fraction = (double) (mouseX - LEFT) / trackWidth();
            return Math.max(0.0, Math.min(1.0, fraction)) * duration;
        }

        private int trackWidth() {
            return Math.max(1, getWidth() - LEFT - RIGHT);
        }

        private int rulerBaseline() {
            return Math.max(TOP + 38, getHeight() - 31);
        }

        private int runWindowTop() {
            return TOP;
        }

        private int runWindowHeight() {
            int available = Math.max(1, getHeight() - TOP - 7);
            return Math.max(1, available / 2);
        }

        private int targetViewportTop() {
            return TOP + 15;
        }

        private int targetViewportHeight() {
            return targetRowGap() * VISIBLE_TARGET_ROWS + 7;
        }

        private int targetRowY(int visibleIndex) {
            return TOP + 24 + visibleIndex * targetRowGap();
        }

        private int targetRowGap() {
            return 16;
        }
    }

    private enum DragMode {
        NONE,
        START,
        STOP,
        WINDOW,
        SEEK
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static double niceTimeStep(double desiredSeconds) {
        double[] steps = {1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1_800, 3_600};
        for (double step : steps) {
            if (step >= desiredSeconds) {
                return step;
            }
        }
        return Math.ceil(desiredSeconds / 3_600.0) * 3_600.0;
    }

    private static String formatTime(double secondsValue) {
        int totalSeconds = Math.max(0, (int) Math.round(secondsValue));
        return "%02d:%02d".formatted(totalSeconds / 60, totalSeconds % 60);
    }
}
