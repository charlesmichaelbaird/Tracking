package com.targettracker.ui;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetMeasurement;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.model.Wgs84;
import com.targettracker.model.Wgs84Geodesic;
import com.targettracker.tracking.ImmTracker;
import com.targettracker.tracking.TrackView;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class EarthMapCanvas extends JPanel {
    enum DrawingMode {
        FREE_HAND,
        SEGMENTED
    }

    private static final int HORIZONTAL_MARGIN = 66;
    private static final int VERTICAL_MARGIN = 48;
    private static final double MIN_ZOOM = 1.0;
    private static final double MAX_ZOOM = 32_768.0;
    private static final String EARTH_IMAGE_RESOURCE = "/maps/blue_marble_2048.png";
    private static final Color GRID_COLOR = new Color(255, 255, 255, 95);
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
    private final MeasurementEngine measurementEngine;
    private final ImmTracker immTracker;
    private final Supplier<TargetTrajectory> selectedTarget;
    private final BooleanSupplier editingLocked;
    private final Runnable onPathChanged;
    private final Consumer<GeodeticPoint> onCursorChanged;
    private final BufferedImage earthImage;

    private DrawingMode drawingMode = DrawingMode.FREE_HAND;
    private boolean freeHandDrawing;
    private boolean panning;
    private Point mousePoint;
    private Point panAnchor;
    private double panStartLongitude;
    private double panStartLatitude;
    private double centerLongitude;
    private double centerLatitude;
    private double zoom = MIN_ZOOM;

    EarthMapCanvas(
            ScenarioModel model,
            ScenarioPlayback playback,
            MeasurementEngine measurementEngine,
            ImmTracker immTracker,
            Supplier<TargetTrajectory> selectedTarget,
            BooleanSupplier editingLocked,
            Runnable onPathChanged,
            Consumer<GeodeticPoint> onCursorChanged) {
        this.model = model;
        this.playback = playback;
        this.measurementEngine = measurementEngine;
        this.immTracker = immTracker;
        this.selectedTarget = selectedTarget;
        this.editingLocked = editingLocked;
        this.onPathChanged = onPathChanged;
        this.onCursorChanged = onCursorChanged;
        this.earthImage = loadEarthImage();
        setBackground(new Color(246, 248, 251));
        setPreferredSize(new Dimension(820, 620));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (SwingUtilities.isRightMouseButton(event)
                        || SwingUtilities.isMiddleMouseButton(event)) {
                    panning = true;
                    panAnchor = event.getPoint();
                    panStartLongitude = centerLongitude;
                    panStartLatitude = centerLatitude;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
                if (!SwingUtilities.isLeftMouseButton(event)
                        || editingLocked.getAsBoolean()
                        || !mapBounds().contains(event.getPoint())) {
                    return;
                }
                TargetTrajectory target = selectedTarget.get();
                if (target == null) {
                    return;
                }

                if (drawingMode == DrawingMode.FREE_HAND) {
                    target.clearPath();
                    addMapPoint(target, event.getPoint());
                    freeHandDrawing = true;
                } else {
                    addMapPoint(target, event.getPoint());
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
                if (panning && panAnchor != null) {
                    Rectangle map = mapBounds();
                    double deltaLongitude = -(event.getX() - panAnchor.x)
                            / (double) map.width * visibleLongitudeSpan();
                    double deltaLatitude = (event.getY() - panAnchor.y)
                            / (double) map.height * visibleLatitudeSpan();
                    centerLongitude = GeodeticPoint.normalizeLongitude(panStartLongitude + deltaLongitude);
                    centerLatitude = clampCenterLatitude(panStartLatitude + deltaLatitude);
                    repaint();
                    return;
                }
                if (!freeHandDrawing || editingLocked.getAsBoolean()) {
                    return;
                }
                TargetTrajectory target = selectedTarget.get();
                if (target != null && mapBounds().contains(event.getPoint())) {
                    addMapPoint(target, event.getPoint());
                    onPathChanged.run();
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (panning) {
                    panning = false;
                    panAnchor = null;
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                }
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

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                zoomAt(event.getPoint(), Math.pow(1.6, -event.getPreciseWheelRotation()));
            }
        };
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addMouseWheelListener(mouseHandler);
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

    void zoomIn() {
        zoomAt(mapCenter(), 2.0);
    }

    void zoomOut() {
        zoomAt(mapCenter(), 0.5);
    }

    void resetView() {
        zoom = MIN_ZOOM;
        centerLongitude = 0.0;
        centerLatitude = 0.0;
        repaint();
    }

    String viewDescription() {
        return zoom < 10.0 ? "%.1f×".formatted(zoom) : "%,.0f×".formatted(zoom);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawEarth(g);
            drawGridAndAxes(g);
            drawScaleBar(g);
            drawPlannedTrajectories(g);
            drawRecentHistory(g);
            drawMeasurements(g);
            drawTracks(g);
            drawCurrentTargets(g);
            drawSegmentPreview(g);
            drawViewBadge(g);
            drawScenarioTimer(g);
        } finally {
            g.dispose();
        }
    }

    private void drawEarth(Graphics2D g) {
        Rectangle map = mapBounds();
        g.setColor(new Color(2, 12, 38));
        g.fillRect(map.x, map.y, map.width, map.height);
        Graphics2D clipped = (Graphics2D) g.create();
        try {
            clipped.clip(map);
            clipped.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            drawRasterViewport(clipped, map);
        } finally {
            clipped.dispose();
        }
        g.setColor(new Color(102, 119, 132));
        g.drawRect(map.x, map.y, map.width, map.height);
    }

    private void drawGridAndAxes(Graphics2D g) {
        Rectangle map = mapBounds();
        double longitudeSpan = visibleLongitudeSpan();
        double latitudeSpan = visibleLatitudeSpan();
        double longitudeStep = chooseDegreeStep(longitudeSpan);
        double latitudeStep = chooseDegreeStep(latitudeSpan);
        double minimumLongitude = centerLongitude - longitudeSpan / 2.0;
        double maximumLongitude = centerLongitude + longitudeSpan / 2.0;
        double minimumLatitude = centerLatitude - latitudeSpan / 2.0;
        double maximumLatitude = centerLatitude + latitudeSpan / 2.0;
        FontMetrics metrics = g.getFontMetrics();

        g.setStroke(new BasicStroke(1.0f));
        for (double longitude = Math.ceil(minimumLongitude / longitudeStep) * longitudeStep;
             longitude <= maximumLongitude + 1.0e-9; longitude += longitudeStep) {
            int x = map.x + (int) Math.round(
                    (longitude - minimumLongitude) / longitudeSpan * map.width);
            g.setColor(GRID_COLOR);
            g.drawLine(x, map.y, x, map.y + map.height);
            g.setColor(new Color(73, 86, 97));
            String label = longitudeLabel(
                    GeodeticPoint.normalizeLongitude(longitude), longitudeStep);
            g.drawString(label, x - metrics.stringWidth(label) / 2, map.y + map.height + 18);
        }
        for (double latitude = Math.ceil(minimumLatitude / latitudeStep) * latitudeStep;
             latitude <= maximumLatitude + 1.0e-9; latitude += latitudeStep) {
            if (latitude < -90.0 || latitude > 90.0) {
                continue;
            }
            int y = map.y + map.height - (int) Math.round(
                    (latitude - minimumLatitude) / latitudeSpan * map.height);
            g.setColor(GRID_COLOR);
            g.drawLine(map.x, y, map.x + map.width, y);
            g.setColor(new Color(73, 86, 97));
            String label = latitudeLabel(latitude, latitudeStep);
            g.drawString(label, map.x - metrics.stringWidth(label) - 8, y + 4);
        }

        g.setColor(new Color(52, 63, 74));
        g.drawString("Longitude", map.x + map.width / 2 - 24, map.y + map.height + 38);
        g.rotate(-Math.PI / 2.0);
        g.drawString("Latitude", -map.y - map.height / 2 - 20, 17);
        g.rotate(Math.PI / 2.0);
    }

    private void drawPlannedTrajectories(Graphics2D g) {
        TargetTrajectory selected = selectedTarget.get();
        for (TargetTrajectory target : model.targets()) {
            if (target.path().size() < 2) {
                continue;
            }
            g.setColor(withAlpha(target.color(), target == selected ? 215 : 130));
            g.setStroke(target == selected ? SELECTED_PLANNED_STROKE : PLANNED_STROKE);
            drawEcefPath(g, sampledGeodesicPath(target.path()));
        }
    }

    private void drawRecentHistory(Graphics2D g) {
        g.setStroke(HISTORY_STROKE);
        for (Map.Entry<TargetTrajectory, Deque<EcefPoint>> entry : playback.recentHistory().entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            g.setColor(entry.getKey().color());
            drawEcefPath(g, new ArrayList<>(entry.getValue()));
        }
    }

    private void drawCurrentTargets(Graphics2D g) {
        for (TargetTrajectory target : model.targets()) {
            EcefPoint position = playback.currentPosition(target);
            if (position == null || (!playback.isRunning() && playback.elapsedSeconds() <= 0.0)) {
                continue;
            }
            Point point = toScreen(Wgs84.toGeodetic(position));
            if (point == null) {
                continue;
            }
            int radius = target == selectedTarget.get() ? 6 : 4;
            g.setColor(Color.WHITE);
            g.fillOval(point.x - radius - 2, point.y - radius - 2, radius * 2 + 4, radius * 2 + 4);
            g.setColor(target.color());
            g.fillOval(point.x - radius, point.y - radius, radius * 2, radius * 2);
            g.setColor(new Color(31, 38, 45));
            g.drawString(target.id(), point.x + radius + 5, point.y - radius - 2);
        }
    }

    private void drawMeasurements(Graphics2D g) {
        for (TargetMeasurement measurement : measurementEngine.visibleMeasurements()) {
            Point point = toScreen(Wgs84.toGeodetic(measurement.measuredPosition()));
            if (point == null) {
                continue;
            }
            int radius = 7;
            g.setColor(new Color(16, 22, 30, 210));
            g.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(point.x - radius, point.y - radius, point.x + radius, point.y + radius);
            g.drawLine(point.x - radius, point.y + radius, point.x + radius, point.y - radius);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(point.x - radius, point.y - radius, point.x + radius, point.y + radius);
            g.drawLine(point.x - radius, point.y + radius, point.x + radius, point.y - radius);
        }
    }

    private void drawTracks(Graphics2D g) {
        for (TrackView track : immTracker.currentViews()) {
            Color color = track.dead() ? new Color(145, 150, 156) : track.color();
            if (track.tail().size() >= 2) {
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(),
                        track.dead() ? 105 : 210));
                g.setStroke(new BasicStroke(
                        track.dead() ? 2.0f : 3.2f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                drawEcefPath(g, track.tail());
            }
            drawTrackCovariance(g, track, color);

            Point point = toScreen(Wgs84.toGeodetic(track.meanPosition()));
            if (point == null) {
                continue;
            }
            int radius = 7;
            g.setColor(new Color(20, 25, 31, 210));
            g.fillRect(point.x - radius - 2, point.y - radius - 2,
                    radius * 2 + 4, radius * 2 + 4);
            g.setColor(color);
            g.fillRect(point.x - radius, point.y - radius, radius * 2, radius * 2);
            g.setColor(Color.WHITE);
            g.drawRect(point.x - radius, point.y - radius, radius * 2, radius * 2);
            g.setColor(track.dead() ? new Color(105, 110, 116) : new Color(25, 31, 37));
            String label = track.dead() ? track.id() + " (dead)" : track.id();
            g.drawString(label, point.x + radius + 5, point.y - radius - 2);
        }
    }

    private void drawTrackCovariance(Graphics2D g, TrackView track, Color color) {
        GeodeticPoint geodetic = Wgs84.toGeodetic(track.meanPosition());
        double latitude = Math.toRadians(geodetic.latitudeDegrees());
        double longitude = Math.toRadians(geodetic.longitudeDegrees());
        double[] eastUnit = {-Math.sin(longitude), Math.cos(longitude), 0.0};
        double[] northUnit = {
                -Math.sin(latitude) * Math.cos(longitude),
                -Math.sin(latitude) * Math.sin(longitude),
                Math.cos(latitude)
        };
        double[][] covariance = track.positionCovariance();
        double eastVariance = projectedCovariance(eastUnit, covariance, eastUnit);
        double northVariance = projectedCovariance(northUnit, covariance, northUnit);
        double crossVariance = projectedCovariance(eastUnit, covariance, northUnit);
        double discriminant = Math.sqrt(Math.max(0.0,
                (eastVariance - northVariance) * (eastVariance - northVariance)
                        + 4.0 * crossVariance * crossVariance));
        double majorSigma = Math.sqrt(Math.max(0.0,
                (eastVariance + northVariance + discriminant) / 2.0));
        double minorSigma = Math.sqrt(Math.max(0.0,
                (eastVariance + northVariance - discriminant) / 2.0));
        if (!Double.isFinite(majorSigma) || majorSigma <= 0.0) {
            return;
        }
        double angle = 0.5 * Math.atan2(
                2.0 * crossVariance, eastVariance - northVariance);
        double cosAngle = Math.cos(angle);
        double sinAngle = Math.sin(angle);

        Point previous = null;
        Point first = null;
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(),
                track.dead() ? 90 : 190));
        g.setStroke(new BasicStroke(track.dead() ? 1.2f : 1.8f));
        for (int sample = 0; sample <= 48; sample++) {
            double phase = 2.0 * Math.PI * sample / 48.0;
            double major = majorSigma * Math.cos(phase);
            double minor = minorSigma * Math.sin(phase);
            double east = major * cosAngle - minor * sinAngle;
            double north = major * sinAngle + minor * cosAngle;
            EcefPoint ellipsePoint = new EcefPoint(
                    track.meanPosition().x() + east * eastUnit[0] + north * northUnit[0],
                    track.meanPosition().y() + east * eastUnit[1] + north * northUnit[1],
                    track.meanPosition().z() + east * eastUnit[2] + north * northUnit[2]);
            Point point = toScreen(Wgs84.toGeodetic(ellipsePoint));
            if (sample == 0) {
                first = point;
            }
            if (previous != null && point != null
                    && Math.abs(point.x - previous.x) < mapBounds().width / 2) {
                g.drawLine(previous.x, previous.y, point.x, point.y);
            }
            previous = point;
        }
        if (previous != null && first != null
                && Math.abs(previous.x - first.x) < mapBounds().width / 2) {
            g.drawLine(previous.x, previous.y, first.x, first.y);
        }
    }

    private static double projectedCovariance(
            double[] left,
            double[][] covariance,
            double[] right) {
        double value = 0.0;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                value += left[row] * covariance[row][column] * right[column];
            }
        }
        return value;
    }

    private void drawSegmentPreview(Graphics2D g) {
        if (drawingMode != DrawingMode.SEGMENTED || mousePoint == null || editingLocked.getAsBoolean()) {
            return;
        }
        TargetTrajectory target = selectedTarget.get();
        if (target == null || target.path().isEmpty() || !mapBounds().contains(mousePoint)) {
            return;
        }
        EcefPoint end = Wgs84.toEcef(toGeodetic(mousePoint).withAltitude(0.0));
        List<EcefPoint> preview = List.of(target.path().get(target.path().size() - 1), end);
        g.setColor(withAlpha(target.color(), 145));
        g.setStroke(new BasicStroke(1.4f));
        drawEcefPath(g, sampledGeodesicPath(preview));
    }

    private void drawViewBadge(Graphics2D g) {
        Rectangle map = mapBounds();
        String text = "NASA Blue Marble • %s • %s"
                .formatted(viewDescription(), viewSizeDescription());
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11.0f));
        int width = g.getFontMetrics().stringWidth(text) + 16;
        g.setColor(new Color(255, 255, 255, 210));
        g.fillRoundRect(map.x + 8, map.y + 8, width, 24, 10, 10);
        g.setColor(new Color(66, 78, 89));
        g.drawString(text, map.x + 16, map.y + 24);
    }

    private void drawScenarioTimer(Graphics2D g) {
        Rectangle map = mapBounds();
        String text = "%.1f s / %.1f s".formatted(
                playback.elapsedSeconds(), model.durationSeconds());
        g.setFont(g.getFont().deriveFont(Font.BOLD, 14.0f));
        FontMetrics metrics = g.getFontMetrics();
        int width = metrics.stringWidth(text) + 20;
        int x = map.x + map.width - width - 8;
        int y = map.y + 8;
        g.setColor(new Color(255, 255, 255, 220));
        g.fillRoundRect(x, y, width, 27, 10, 10);
        g.setColor(new Color(31, 39, 47));
        g.drawString(text, x + 10, y + 19);
    }

    private void drawScaleBar(Graphics2D g) {
        Rectangle map = mapBounds();
        double metersPerPixel = visibleLongitudeSpan()
                * Wgs84.metersPerDegreeLongitude(centerLatitude) / map.width;
        double scaleMeters = niceDistance(metersPerPixel * 120.0);
        int scalePixels = Math.max(1, (int) Math.round(scaleMeters / metersPerPixel));
        String label = formatScaleDistance(scaleMeters);
        FontMetrics metrics = g.getFontMetrics();
        int boxWidth = Math.max(scalePixels, metrics.stringWidth(label)) + 20;
        int boxHeight = 37;
        int x = map.x + 10;
        int y = map.y + map.height - boxHeight - 10;

        g.setColor(new Color(10, 18, 30, 170));
        g.fillRoundRect(x, y, boxWidth, boxHeight, 10, 10);
        int lineX = x + 10;
        int lineY = y + 23;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2.0f));
        g.drawLine(lineX, lineY, lineX + scalePixels, lineY);
        g.drawLine(lineX, lineY - 4, lineX, lineY + 4);
        g.drawLine(lineX + scalePixels, lineY - 4, lineX + scalePixels, lineY + 4);
        g.drawString(label, lineX, y + 13);
    }

    private String viewSizeDescription() {
        double widthMeters = visibleLongitudeSpan()
                * Wgs84.metersPerDegreeLongitude(centerLatitude);
        double heightMeters = visibleLatitudeSpan()
                * Wgs84.metersPerDegreeLatitude(centerLatitude);
        double largestDimension = Math.max(widthMeters, heightMeters);
        if (largestDimension >= 1_000.0) {
            int decimals = largestDimension >= 1_000_000.0
                    ? 0
                    : largestDimension >= 100_000.0 ? 1 : 2;
            String format = "%,." + decimals + "f × %,." + decimals + "f km";
            return format.formatted(widthMeters / 1_000.0, heightMeters / 1_000.0);
        }
        return "%,.0f × %,.0f m".formatted(widthMeters, heightMeters);
    }

    private void drawEcefPath(Graphics2D g, List<EcefPoint> points) {
        Point previous = null;
        for (EcefPoint ecef : points) {
            Point next = toScreen(Wgs84.toGeodetic(ecef));
            if (previous != null && next != null
                    && Math.abs(next.x - previous.x) < mapBounds().width / 2) {
                g.drawLine(previous.x, previous.y, next.x, next.y);
            }
            previous = next;
        }
    }

    private List<EcefPoint> sampledGeodesicPath(List<EcefPoint> controlPoints) {
        List<EcefPoint> sampled = new ArrayList<>();
        sampled.add(controlPoints.get(0));
        for (int i = 1; i < controlPoints.size(); i++) {
            GeodeticPoint start = Wgs84.toGeodetic(controlPoints.get(i - 1));
            GeodeticPoint end = Wgs84.toGeodetic(controlPoints.get(i));
            double distance = Wgs84Geodesic.inverse(start, end).distanceMeters();
            int subdivisions = Math.max(4, Math.min(96,
                    (int) Math.ceil(distance / Math.max(20_000.0, 400_000.0 / zoom))));
            for (int step = 1; step <= subdivisions; step++) {
                GeodeticPoint point = Wgs84Geodesic.interpolate(
                        start, end, (double) step / subdivisions, 0.0);
                sampled.add(Wgs84.toEcef(point));
            }
        }
        return sampled;
    }

    private void addMapPoint(TargetTrajectory target, Point point) {
        target.addPathPoint(Wgs84.toEcef(toGeodetic(point).withAltitude(0.0)));
    }

    private void updateMouse(MouseEvent event) {
        mousePoint = event.getPoint();
        if (mapBounds().contains(mousePoint)) {
            onCursorChanged.accept(toGeodetic(mousePoint));
        }
        repaint();
    }

    private void zoomAt(Point anchor, double factor) {
        Rectangle map = mapBounds();
        if (!map.contains(anchor)) {
            anchor = mapCenter();
        }
        GeodeticPoint anchoredPosition = toGeodetic(anchor);
        double xFraction = (anchor.x - (map.x + map.width / 2.0)) / map.width;
        double yFraction = ((map.y + map.height / 2.0) - anchor.y) / map.height;
        double newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * factor));
        if (Math.abs(newZoom - zoom) < 1.0e-9) {
            return;
        }
        zoom = newZoom;
        centerLongitude = GeodeticPoint.normalizeLongitude(
                anchoredPosition.longitudeDegrees() - xFraction * visibleLongitudeSpan());
        centerLatitude = clampCenterLatitude(
                anchoredPosition.latitudeDegrees() - yFraction * visibleLatitudeSpan());
        repaint();
    }

    private Point toScreen(GeodeticPoint point) {
        double longitudeDelta = GeodeticPoint.normalizeLongitude(
                point.longitudeDegrees() - centerLongitude);
        double latitudeDelta = point.latitudeDegrees() - centerLatitude;
        if (Math.abs(longitudeDelta) > visibleLongitudeSpan() / 2.0 + 1.0e-8
                || Math.abs(latitudeDelta) > visibleLatitudeSpan() / 2.0 + 1.0e-8) {
            return null;
        }
        Rectangle map = mapBounds();
        return new Point(
                map.x + map.width / 2 + (int) Math.round(
                        longitudeDelta / visibleLongitudeSpan() * map.width),
                map.y + map.height / 2 - (int) Math.round(
                        latitudeDelta / visibleLatitudeSpan() * map.height));
    }

    private GeodeticPoint toGeodetic(Point point) {
        Rectangle map = mapBounds();
        double longitude = centerLongitude
                + (point.x - (map.x + map.width / 2.0)) / map.width * visibleLongitudeSpan();
        double latitude = centerLatitude
                + ((map.y + map.height / 2.0) - point.y) / map.height * visibleLatitudeSpan();
        return new GeodeticPoint(latitude, longitude, 0.0);
    }

    private Rectangle mapBounds() {
        int availableWidth = Math.max(2, getWidth() - HORIZONTAL_MARGIN * 2);
        int availableHeight = Math.max(1, getHeight() - VERTICAL_MARGIN * 2);
        int mapWidth = availableWidth;
        int mapHeight = mapWidth / 2;
        if (mapHeight > availableHeight) {
            mapHeight = availableHeight;
            mapWidth = mapHeight * 2;
        }
        return new Rectangle(
                (getWidth() - mapWidth) / 2,
                (getHeight() - mapHeight) / 2,
                mapWidth,
                mapHeight);
    }

    private Point mapCenter() {
        Rectangle map = mapBounds();
        return new Point(map.x + map.width / 2, map.y + map.height / 2);
    }

    private double visibleLongitudeSpan() {
        return 360.0 / zoom;
    }

    private double visibleLatitudeSpan() {
        return 180.0 / zoom;
    }

    private double clampCenterLatitude(double latitude) {
        double limit = Math.max(0.0, 90.0 - visibleLatitudeSpan() / 2.0);
        return Math.max(-limit, Math.min(limit, latitude));
    }

    private static double chooseDegreeStep(double visibleSpan) {
        double[] steps = {90.0, 60.0, 45.0, 30.0, 20.0, 15.0, 10.0, 5.0,
                2.0, 1.0, 0.5, 0.25, 0.1};
        double desired = visibleSpan / 8.0;
        for (double step : steps) {
            if (step <= desired) {
                return step;
            }
        }
        double magnitude = Math.pow(10.0, Math.floor(Math.log10(desired)));
        double normalized = desired / magnitude;
        double multiplier = normalized >= 5.0 ? 5.0 : normalized >= 2.0 ? 2.0 : 1.0;
        return multiplier * magnitude;
    }

    private static String longitudeLabel(double longitude, double step) {
        if (Math.abs(longitude) < 1.0e-9) {
            return "0°";
        }
        if (Math.abs(Math.abs(longitude) - 180.0) < 1.0e-9) {
            return "180°";
        }
        return "%s°%s".formatted(
                formatDegrees(Math.abs(longitude), step), longitude > 0.0 ? "E" : "W");
    }

    private static String latitudeLabel(double latitude, double step) {
        if (Math.abs(latitude) < 1.0e-9) {
            return "0°";
        }
        return "%s°%s".formatted(
                formatDegrees(Math.abs(latitude), step), latitude > 0.0 ? "N" : "S");
    }

    private static String formatDegrees(double degrees, double step) {
        int decimalPlaces = 0;
        double scaledStep = step;
        while (decimalPlaces < 7 && Math.abs(scaledStep - Math.rint(scaledStep)) > 1.0e-8) {
            scaledStep *= 10.0;
            decimalPlaces++;
        }
        return ("%." + decimalPlaces + "f").formatted(degrees);
    }

    private static double niceDistance(double desiredMeters) {
        if (desiredMeters <= 0.0) {
            return 1.0;
        }
        double magnitude = Math.pow(10.0, Math.floor(Math.log10(desiredMeters)));
        double normalized = desiredMeters / magnitude;
        double multiplier = normalized >= 5.0 ? 5.0 : normalized >= 2.0 ? 2.0 : 1.0;
        return multiplier * magnitude;
    }

    private static String formatScaleDistance(double meters) {
        if (meters >= 1_000.0) {
            return "%,.0f km".formatted(meters / 1_000.0);
        }
        return "%,.0f m".formatted(meters);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private void drawRasterViewport(Graphics2D g, Rectangle map) {
        double longitudeSpan = visibleLongitudeSpan();
        double latitudeSpan = visibleLatitudeSpan();
        double minimumLongitude = centerLongitude - longitudeSpan / 2.0;
        double maximumLongitude = centerLongitude + longitudeSpan / 2.0;
        double minimumLatitude = centerLatitude - latitudeSpan / 2.0;
        double maximumLatitude = centerLatitude + latitudeSpan / 2.0;
        int firstCopy = (int) Math.floor((minimumLongitude + 180.0) / 360.0) - 1;
        int lastCopy = (int) Math.ceil((maximumLongitude + 180.0) / 360.0) + 1;

        double destinationY = map.y
                + (maximumLatitude - 90.0) / latitudeSpan * map.height;
        double destinationHeight = 180.0 / latitudeSpan * map.height;
        double destinationWidth = 360.0 / longitudeSpan * map.width;

        for (int copy = firstCopy; copy <= lastCopy; copy++) {
            double tileMinimumLongitude = -180.0 + 360.0 * copy;
            double destinationX = map.x
                    + (tileMinimumLongitude - minimumLongitude) / longitudeSpan * map.width;
            if (destinationX + destinationWidth < map.x
                    || destinationX > map.x + map.width) {
                continue;
            }

            AffineTransform transform = new AffineTransform();
            transform.translate(destinationX, destinationY);
            transform.scale(
                    destinationWidth / earthImage.getWidth(),
                    destinationHeight / earthImage.getHeight());
            g.drawImage(earthImage, transform, null);
        }
    }

    private static BufferedImage loadEarthImage() {
        try (InputStream input = EarthMapCanvas.class.getResourceAsStream(EARTH_IMAGE_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled Earth map: " + EARTH_IMAGE_RESOURCE);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null || image.getWidth() != image.getHeight() * 2) {
                throw new IllegalStateException(
                        "Earth map must be a readable 2:1 equirectangular image");
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load bundled Earth map", exception);
        }
    }
}
