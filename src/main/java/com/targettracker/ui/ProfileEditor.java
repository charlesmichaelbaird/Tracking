package com.targettracker.ui;

import com.targettracker.model.ScalarProfile;
import com.targettracker.model.TargetTrajectory;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

final class ProfileEditor extends JPanel {
    private static final int LEFT = 62;
    private static final int RIGHT = 22;
    private static final int TOP = 34;
    private static final int BOTTOM = 42;

    private final String title;
    private final String unit;
    private final Supplier<TargetTrajectory> selectedTarget;
    private final Function<TargetTrajectory, ScalarProfile> profileGetter;
    private final ScenarioPlayback playback;
    private final BooleanSupplier editingLocked;
    private final Runnable onProfileChanged;

    private int previousIndex = -1;
    private double previousValue;

    ProfileEditor(
            String title,
            String unit,
            Supplier<TargetTrajectory> selectedTarget,
            Function<TargetTrajectory, ScalarProfile> profileGetter,
            ScenarioPlayback playback,
            BooleanSupplier editingLocked,
            Runnable onProfileChanged) {
        this.title = title;
        this.unit = unit;
        this.selectedTarget = selectedTarget;
        this.profileGetter = profileGetter;
        this.playback = playback;
        this.editingLocked = editingLocked;
        this.onProfileChanged = onProfileChanged;
        setPreferredSize(new Dimension(390, 220));
        setMinimumSize(new Dimension(300, 180));
        setBackground(Color.WHITE);

        MouseAdapter handler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (editingLocked.getAsBoolean()) {
                    return;
                }
                previousIndex = -1;
                applyPoint(event);
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (!editingLocked.getAsBoolean()) {
                    applyPoint(event);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                previousIndex = -1;
            }
        };
        addMouseListener(handler);
        addMouseMotionListener(handler);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawChart(g);
        } finally {
            g.dispose();
        }
    }

    private void drawChart(Graphics2D g) {
        TargetTrajectory target = selectedTarget.get();
        ScalarProfile profile = target == null ? null : profileGetter.apply(target);
        int width = chartWidth();
        int height = chartHeight();

        g.setColor(new Color(43, 51, 59));
        g.setFont(g.getFont().deriveFont(Font.BOLD, 14.0f));
        g.drawString(title, LEFT, 21);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11.0f));
        g.setColor(new Color(102, 113, 124));

        g.setStroke(new BasicStroke(1.0f));
        FontMetrics metrics = g.getFontMetrics();
        for (int i = 0; i <= 4; i++) {
            int x = LEFT + width * i / 4;
            int y = TOP + height * i / 4;
            g.setColor(new Color(226, 231, 236));
            g.drawLine(x, TOP, x, TOP + height);
            g.drawLine(LEFT, y, LEFT + width, y);
            g.setColor(new Color(103, 113, 124));
            String xLabel = (i * 25) + "%";
            g.drawString(xLabel, x - metrics.stringWidth(xLabel) / 2, TOP + height + 20);
            if (profile != null) {
                double value = profile.maximum()
                        - (profile.maximum() - profile.minimum()) * i / 4.0;
                String yLabel = formatValue(value);
                g.drawString(yLabel, LEFT - metrics.stringWidth(yLabel) - 8, y + 4);
            }
        }

        g.setColor(new Color(143, 153, 163));
        g.drawRect(LEFT, TOP, width, height);
        if (profile == null) {
            return;
        }

        Polygon fill = new Polygon();
        fill.addPoint(LEFT, TOP + height);
        for (int i = 0; i < profile.sampleCount(); i++) {
            fill.addPoint(toX(i, profile), toY(profile.sample(i), profile));
        }
        fill.addPoint(LEFT + width, TOP + height);
        g.setColor(withAlpha(target.color(), 34));
        g.fillPolygon(fill);

        g.setColor(target.color());
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int previousX = toX(0, profile);
        int previousY = toY(profile.sample(0), profile);
        for (int i = 1; i < profile.sampleCount(); i++) {
            int x = toX(i, profile);
            int y = toY(profile.sample(i), profile);
            g.drawLine(previousX, previousY, x, y);
            previousX = x;
            previousY = y;
        }

        if (target.isRunnable()
                && (playback.isRunning()
                || playback.isReplayDisplayActive()
                || playback.elapsedSeconds() > 0.0)) {
            double normalizedTime = target.normalizedTimeAt(playback.elapsedSeconds());
            int x = LEFT + (int) Math.round(normalizedTime * width);
            g.setColor(new Color(37, 44, 51, 175));
            g.setStroke(new BasicStroke(1.5f));
            g.drawLine(x, TOP, x, TOP + height);
            double value = profile.valueAt(normalizedTime);
            int y = toY(value, profile);
            g.setColor(Color.WHITE);
            g.fillOval(x - 6, y - 6, 12, 12);
            g.setColor(target.color());
            g.fillOval(x - 4, y - 4, 8, 8);
        }

        g.setColor(new Color(102, 113, 124));
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10.0f));
        String range = "%s to %s %s".formatted(
                formatValue(profile.minimum()), formatValue(profile.maximum()), unit);
        g.drawString(range, LEFT + width - g.getFontMetrics().stringWidth(range), 21);
    }

    private void applyPoint(MouseEvent event) {
        TargetTrajectory target = selectedTarget.get();
        if (target == null) {
            return;
        }
        ScalarProfile profile = profileGetter.apply(target);
        int x = Math.max(LEFT, Math.min(LEFT + chartWidth(), event.getX()));
        int y = Math.max(TOP, Math.min(TOP + chartHeight(), event.getY()));
        int index = (int) Math.round((double) (x - LEFT) / chartWidth() * (profile.sampleCount() - 1));
        double fractionFromBottom = (double) (TOP + chartHeight() - y) / chartHeight();
        double value = profile.minimum() + fractionFromBottom * (profile.maximum() - profile.minimum());

        if (previousIndex < 0) {
            profile.setSample(index, value);
        } else {
            profile.setBetween(previousIndex, previousValue, index, value);
        }
        previousIndex = index;
        previousValue = value;
        onProfileChanged.run();
        repaint();
    }

    private int toX(int index, ScalarProfile profile) {
        return LEFT + (int) Math.round((double) index / (profile.sampleCount() - 1) * chartWidth());
    }

    private int toY(double value, ScalarProfile profile) {
        double normalized = (value - profile.minimum()) / (profile.maximum() - profile.minimum());
        return TOP + chartHeight() - (int) Math.round(normalized * chartHeight());
    }

    private int chartWidth() {
        return Math.max(1, getWidth() - LEFT - RIGHT);
    }

    private int chartHeight() {
        return Math.max(1, getHeight() - TOP - BOTTOM);
    }

    private String formatValue(double value) {
        if ("m".equals(unit) && Math.abs(value) >= 1_000.0) {
            return "%.0fk".formatted(value / 1_000.0);
        }
        return "%.0f".formatted(value);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
