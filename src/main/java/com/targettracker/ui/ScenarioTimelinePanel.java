package com.targettracker.ui;

import com.targettracker.model.ScenarioModel;
import com.targettracker.recording.TrackCsvRecorder;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/** Video-style scenario ruler that becomes seekable when replay data exists. */
final class ScenarioTimelinePanel extends JPanel {
    private final ScenarioModel model;
    private final ScenarioPlayback playback;
    private final TrackCsvRecorder recorder;
    private final JLabel stateLabel = new JLabel();
    private final TimelineRuler ruler = new TimelineRuler();
    private List<Double> candidateMarkerTimes = List.of();
    private double selectedCandidateTime = Double.NaN;

    ScenarioTimelinePanel(
            ScenarioModel model,
            ScenarioPlayback playback,
            TrackCsvRecorder recorder) {
        super(new BorderLayout(8, 0));
        this.model = model;
        this.playback = playback;
        this.recorder = recorder;
        setBackground(new Color(247, 249, 251));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(210, 216, 222)),
                BorderFactory.createEmptyBorder(5, 10, 4, 10)));
        setPreferredSize(new Dimension(700, 84));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
        controls.setOpaque(false);
        JLabel title = new JLabel("Scenario timeline");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        controls.add(title);
        stateLabel.setForeground(new Color(84, 96, 108));
        controls.add(stateLabel);
        add(controls, BorderLayout.NORTH);
        add(ruler, BorderLayout.CENTER);
        refresh();
    }

    void refresh() {
        boolean hasScenario = playback.durationSeconds() > 0.0;
        ruler.setEnabled(playback.canSeek());

        if (recorder.isActive()) {
            stateLabel.setText("Track files are being written — seeking temporarily disabled");
        } else if (playback.isComputing()) {
            stateLabel.setText("Pre-computing sensor and tracker history…");
        } else if (playback.isRunning()) {
            stateLabel.setText("Replay playing — drag the black time bar to seek");
        } else if (playback.isReplayReady()) {
            stateLabel.setText("Replay ready — drag the black time bar");
        } else if (hasScenario) {
            stateLabel.setText("Pre-compute the scenario to enable replay and seeking");
        } else {
            stateLabel.setText("Create a runnable scenario first");
        }
        ruler.repaint();
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
        ruler.repaint();
    }

    void clearCandidateMarkers() {
        candidateMarkerTimes = List.of();
        selectedCandidateTime = Double.NaN;
        ruler.repaint();
    }

    private final class TimelineRuler extends JComponent {
        private static final int LEFT = 18;
        private static final int RIGHT = 18;

        TimelineRuler() {
            setPreferredSize(new Dimension(600, 47));
            setToolTipText("Drag to seek through the pre-computed scenario");
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    seek(event.getX());
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    seek(event.getX());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(enabled
                    ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    : Cursor.getDefaultCursor());
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int width = Math.max(1, getWidth() - LEFT - RIGHT);
                int baseline = 19;
                double duration = playback.durationSeconds();

                g.setColor(isEnabled() ? new Color(68, 77, 86) : new Color(143, 150, 157));
                g.setStroke(new BasicStroke(1.4f));
                g.drawLine(LEFT, baseline, LEFT + width, baseline);

                double majorStep = niceTimeStep(Math.max(1.0, duration / 7.0));
                if (duration > 0.0) {
                    g.setFont(g.getFont().deriveFont(Font.PLAIN, 10.0f));
                    FontMetrics metrics = g.getFontMetrics();
                    for (double time = 0.0; time < duration - 1.0e-9; time += majorStep) {
                        int x = LEFT + (int) Math.round(time / duration * width);
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
                            int minorX = LEFT + (int) Math.round(minorTime / duration * width);
                            g.drawLine(minorX, baseline - 3, minorX, baseline + 3);
                        }
                    }
                    int endX = LEFT + width;
                    g.drawLine(endX, baseline - 7, endX, baseline + 7);
                    String endLabel = formatTime(duration);
                    g.drawString(endLabel,
                            getWidth() - metrics.stringWidth(endLabel), baseline + 20);
                    drawCandidateMarkers(g, width, baseline, duration);
                }

                double fraction = duration <= 0.0
                        ? 0.0
                        : Math.max(0.0, Math.min(1.0, playback.elapsedSeconds() / duration));
                int playheadX = LEFT + (int) Math.round(fraction * width);
                g.setColor(Color.BLACK);
                g.fillRect(playheadX - 2, 1, 4, baseline + 8);
                int[] triangleX = {playheadX - 5, playheadX + 5, playheadX};
                int[] triangleY = {1, 1, 7};
                g.fillPolygon(triangleX, triangleY, 3);
            } finally {
                g.dispose();
            }
        }

        private void seek(int mouseX) {
            if (!isEnabled() || !playback.canSeek()) {
                return;
            }
            int width = Math.max(1, getWidth() - LEFT - RIGHT);
            double fraction = Math.max(0.0, Math.min(1.0, (double) (mouseX - LEFT) / width));
            playback.seekTo(fraction * playback.durationSeconds());
        }

        private void drawCandidateMarkers(
                Graphics2D g,
                int width,
                int baseline,
                double duration) {
            if (candidateMarkerTimes.isEmpty()) {
                return;
            }
            Stroke oldStroke = g.getStroke();
            for (double time : candidateMarkerTimes) {
                if (time < -1.0e-9 || time > duration + 1.0e-9) {
                    continue;
                }
                boolean selected = Math.abs(time - selectedCandidateTime) <= 1.0e-6;
                int x = LEFT + (int) Math.round(
                        Math.max(0.0, Math.min(1.0, time / duration)) * width);
                g.setColor(selected ? new Color(255, 0, 0) : new Color(255, 35, 35, 210));
                g.setStroke(new BasicStroke(selected ? 3.2f : 2.1f));
                g.drawLine(x, 0, x, baseline + 12);
            }
            g.setStroke(oldStroke);
        }
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
