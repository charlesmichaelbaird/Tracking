package com.targettracker.ui;

import com.targettracker.model.EnuFrame;
import com.targettracker.model.EnuPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class TrajectoryCanvas extends JPanel {
    enum DrawingMode {
        FREE_HAND,
        SEGMENTED
    }

    private static final int PADDING = 54;
    private static final Color GRID_COLOR = new Color(219, 225, 232);
    private static final Color AXIS_COLOR = new Color(126, 139, 151);
    private static final Stroke PLANNED_STROKE = new BasicStroke(
            1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            10.0f, new float[]{7.0f, 6.0f}, 0.0f);
    private static final Stroke SELECTED_PLANNED_STROKE = new BasicStroke(
            2.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
            10.0f, new float[]{9.0f, 5.0f}, 0.0f);
    private static final Stroke HISTORY_STROKE = new BasicStroke(
            5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    private final ScenarioModel model;
    private final ScenarioPlayback playback;
    private final Supplier<TargetTrajectory> selectedTarget;
    private final BooleanSupplier editingLocked;
    private final Runnable onPathChanged;
    private final Consumer<EnuPoint> onCursorChanged;

    private DrawingMode drawingMode = DrawingMode.FREE_HAND;
    private boolean freeHandDrawing;
    private Point mousePoint;

    TrajectoryCanvas(
            ScenarioModel model,
            ScenarioPlayback playback,
            Supplier<TargetTrajectory> selectedTarget,
            BooleanSupplier editingLocked,
            Runnable onPathChanged,
            Consumer<EnuPoint> onCursorChanged) {
        this.model = model;
        this.playback = playback;
        this.selectedTarget = selectedTarget;
        this.editingLocked = editingLocked;
        this.onPathChanged = onPathChanged;
        this.onCursorChanged = onCursorChanged;
        setBackground(new Color(247, 249, 252));
        setPreferredSize(new Dimension(760, 620));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (editingLocked.getAsBoolean() || !insidePlot(event.getPoint())) {
                    return;
                }
                TargetTrajectory target = selectedTarget.get();
                if (target == null) {
                    return;
                }

                if (drawingMode == DrawingMode.FREE_HAND) {
                    target.clearPath();
                    target.addPathPoint(toEnu(event.getPoint()));
                    freeHandDrawing = true;
                } else {
                    target.addPathPoint(toEnu(event.getPoint()));
                    if (event.getClickCount() >= 2) {
                        finishPath();
                    }
                }
                onPathChanged.run();
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                updateMouse(event);
                if (!freeHandDrawing || editingLocked.getAsBoolean()) {
                    return;
                }
                TargetTrajectory target = selectedTarget.get();
                if (target != null && insidePlot(event.getPoint())) {
                    target.addPathPoint(toEnu(event.getPoint()));
                    onPathChanged.run();
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (freeHandDrawing) {
                    freeHandDrawing = false;
                    onPathChanged.run();
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                updateMouse(event);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                mousePoint = null;
                repaint();
            }
        };
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
    }

    void setDrawingMode(DrawingMode drawingMode) {
        this.drawingMode = drawingMode;
        freeHandDrawing = false;
        repaint();
    }

    void finishPath() {
        freeHandDrawing = false;
        mousePoint = null;
        onPathChanged.run();
        repaint();
    }

    private void updateMouse(MouseEvent event) {
        mousePoint = event.getPoint();
        if (insidePlot(mousePoint)) {
            onCursorChanged.accept(toEnu(mousePoint));
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawGrid(g);
            drawPlannedTrajectories(g);
            drawRecentHistory(g);
            drawCurrentTargets(g);
            drawSegmentPreview(g);
        } finally {
            g.dispose();
        }
    }

    private void drawGrid(Graphics2D g) {
        EnuFrame frame = model.frame();
        double eastStep = chooseGridStep(frame.maxEastMeters() - frame.minEastMeters());
        double northStep = chooseGridStep(frame.maxNorthMeters() - frame.minNorthMeters());
        FontMetrics metrics = g.getFontMetrics();

        g.setStroke(new BasicStroke(1.0f));
        for (double east = firstGrid(frame.minEastMeters(), eastStep);
             east <= frame.maxEastMeters(); east += eastStep) {
            int x = toScreen(new EnuPoint(east, 0.0, 0.0)).x;
            g.setColor(Math.abs(east) < 0.001 ? AXIS_COLOR : GRID_COLOR);
            g.drawLine(x, PADDING, x, getHeight() - PADDING);
            g.setColor(new Color(86, 97, 108));
            String label = formatAxis(east);
            g.drawString(label, x - metrics.stringWidth(label) / 2, getHeight() - PADDING + 20);
        }
        for (double north = firstGrid(frame.minNorthMeters(), northStep);
             north <= frame.maxNorthMeters(); north += northStep) {
            int y = toScreen(new EnuPoint(0.0, north, 0.0)).y;
            g.setColor(Math.abs(north) < 0.001 ? AXIS_COLOR : GRID_COLOR);
            g.drawLine(PADDING, y, getWidth() - PADDING, y);
            g.setColor(new Color(86, 97, 108));
            String label = formatAxis(north);
            g.drawString(label, PADDING - metrics.stringWidth(label) - 8, y + 4);
        }

        g.setColor(new Color(52, 63, 74));
        g.drawString("East (m)", getWidth() / 2 - 22, getHeight() - 12);
        g.rotate(-Math.PI / 2.0);
        g.drawString("North (m)", -getHeight() / 2 - 28, 17);
        g.rotate(Math.PI / 2.0);

        g.setColor(AXIS_COLOR);
        g.drawRect(PADDING, PADDING, plotWidth(), plotHeight());
    }

    private void drawPlannedTrajectories(Graphics2D g) {
        TargetTrajectory selected = selectedTarget.get();
        for (TargetTrajectory target : model.targets()) {
            if (target.path().size() < 2) {
                continue;
            }
            g.setColor(withAlpha(target.color(), target == selected ? 210 : 125));
            g.setStroke(target == selected ? SELECTED_PLANNED_STROKE : PLANNED_STROKE);
            drawPolyline(g, target.path());
        }
    }

    private void drawRecentHistory(Graphics2D g) {
        g.setStroke(HISTORY_STROKE);
        for (Map.Entry<TargetTrajectory, Deque<EnuPoint>> entry : playback.recentHistory().entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            g.setColor(entry.getKey().color());
            drawPolyline(g, new ArrayList<>(entry.getValue()));
        }
    }

    private void drawCurrentTargets(Graphics2D g) {
        for (TargetTrajectory target : model.targets()) {
            EnuPoint position = playback.currentPosition(target);
            if (position == null || (!playback.isRunning() && playback.elapsedSeconds() <= 0.0)) {
                continue;
            }
            Point point = toScreen(position);
            int radius = target == selectedTarget.get() ? 9 : 7;
            g.setColor(Color.WHITE);
            g.fillOval(point.x - radius - 2, point.y - radius - 2, radius * 2 + 4, radius * 2 + 4);
            g.setColor(target.color());
            g.fillOval(point.x - radius, point.y - radius, radius * 2, radius * 2);
            g.setColor(new Color(31, 38, 45));
            g.drawString(target.id(), point.x + radius + 5, point.y - radius - 2);
        }
    }

    private void drawSegmentPreview(Graphics2D g) {
        if (drawingMode != DrawingMode.SEGMENTED || mousePoint == null || editingLocked.getAsBoolean()) {
            return;
        }
        TargetTrajectory target = selectedTarget.get();
        if (target == null || target.path().isEmpty() || !insidePlot(mousePoint)) {
            return;
        }
        Point start = toScreen(target.path().get(target.path().size() - 1));
        g.setColor(withAlpha(target.color(), 135));
        g.setStroke(new BasicStroke(1.4f));
        g.drawLine(start.x, start.y, mousePoint.x, mousePoint.y);
    }

    private void drawPolyline(Graphics2D g, List<EnuPoint> points) {
        Point previous = toScreen(points.get(0));
        for (int i = 1; i < points.size(); i++) {
            Point next = toScreen(points.get(i));
            g.drawLine(previous.x, previous.y, next.x, next.y);
            previous = next;
        }
    }

    private Point toScreen(EnuPoint point) {
        EnuFrame frame = model.frame();
        double xFraction = (point.east() - frame.minEastMeters())
                / (frame.maxEastMeters() - frame.minEastMeters());
        double yFraction = (point.north() - frame.minNorthMeters())
                / (frame.maxNorthMeters() - frame.minNorthMeters());
        return new Point(
                PADDING + (int) Math.round(xFraction * plotWidth()),
                getHeight() - PADDING - (int) Math.round(yFraction * plotHeight()));
    }

    private EnuPoint toEnu(Point point) {
        EnuFrame frame = model.frame();
        double xFraction = (double) (point.x - PADDING) / Math.max(1, plotWidth());
        double yFraction = (double) (getHeight() - PADDING - point.y) / Math.max(1, plotHeight());
        double east = frame.minEastMeters()
                + xFraction * (frame.maxEastMeters() - frame.minEastMeters());
        double north = frame.minNorthMeters()
                + yFraction * (frame.maxNorthMeters() - frame.minNorthMeters());
        return new EnuPoint(east, north, 0.0);
    }

    private boolean insidePlot(Point point) {
        return point.x >= PADDING && point.x <= getWidth() - PADDING
                && point.y >= PADDING && point.y <= getHeight() - PADDING;
    }

    private int plotWidth() {
        return Math.max(1, getWidth() - PADDING * 2);
    }

    private int plotHeight() {
        return Math.max(1, getHeight() - PADDING * 2);
    }

    private static double chooseGridStep(double range) {
        double rough = range / 10.0;
        double magnitude = Math.pow(10.0, Math.floor(Math.log10(rough)));
        double normalized = rough / magnitude;
        double step = normalized <= 1.0 ? 1.0 : normalized <= 2.0 ? 2.0 : normalized <= 5.0 ? 5.0 : 10.0;
        return step * magnitude;
    }

    private static double firstGrid(double minimum, double step) {
        return Math.ceil(minimum / step) * step;
    }

    private static String formatAxis(double meters) {
        if (Math.abs(meters) >= 1_000.0) {
            return "%.0fk".formatted(meters / 1_000.0);
        }
        return "%.0f".formatted(meters);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
