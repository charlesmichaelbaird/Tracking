package com.targettracker.ui;

import com.targettracker.analysis.TrackStitchingAnalyzer;
import com.targettracker.model.BlackoutRegion;
import com.targettracker.model.EcefPoint;
import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetMeasurement;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.model.Wgs84;
import com.targettracker.model.Wgs84Geodesic;
import com.targettracker.tracking.TrackRecord;
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
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Polygon;
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
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class EarthMapCanvas extends JPanel {
    enum DrawingMode {
        FREE_HAND,
        SEGMENTED
    }

    enum StitchingVisibilityMode {
        ALL,
        GREYED,
        ONLY_STITCHED
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
    private final DisplayHistorySettings displaySettings;
    private final Supplier<TargetTrajectory> selectedTarget;
    private final BooleanSupplier editingLocked;
    private final Runnable onPathChanged;
    private final Consumer<BlackoutRegion> onBlackoutRegionCreated;
    private final Consumer<GeodeticPoint> onCursorChanged;
    private final BufferedImage earthImage;
    private final NaturalEarthDetailLayer detailLayer;

    private DrawingMode drawingMode = DrawingMode.FREE_HAND;
    private boolean freeHandDrawing;
    private boolean blackoutDrawing;
    private boolean panning;
    private TargetTrajectory finishedSegmentedTarget;
    private Point mousePoint;
    private GeodeticPoint blackoutFirstCorner;
    private Point panAnchor;
    private double panStartLongitude;
    private double panStartLatitude;
    private double centerLongitude;
    private double centerLatitude;
    private double zoom = MIN_ZOOM;
    private boolean highResolutionMapEnabled = true;
    private boolean detailLayerActive;
    private Set<String> stitchingTrackIds = Set.of();
    private Set<String> stitchingTargetIds = Set.of();
    private List<TrackStitchingAnalyzer.Segment> stitchingSegments = List.of();
    private StitchingVisibilityMode stitchingVisibilityMode = StitchingVisibilityMode.ALL;
    private double profileHighlightNormalizedTime = Double.NaN;

    EarthMapCanvas(
            ScenarioModel model,
            ScenarioPlayback playback,
            MeasurementEngine measurementEngine,
            DisplayHistorySettings displaySettings,
            Supplier<TargetTrajectory> selectedTarget,
            BooleanSupplier editingLocked,
            Runnable onPathChanged,
            Consumer<BlackoutRegion> onBlackoutRegionCreated,
            Consumer<GeodeticPoint> onCursorChanged) {
        this.model = model;
        this.playback = playback;
        this.measurementEngine = measurementEngine;
        this.displaySettings = displaySettings;
        this.selectedTarget = selectedTarget;
        this.editingLocked = editingLocked;
        this.onPathChanged = onPathChanged;
        this.onBlackoutRegionCreated = onBlackoutRegionCreated;
        this.onCursorChanged = onCursorChanged;
        this.earthImage = loadEarthImage();
        this.detailLayer = new NaturalEarthDetailLayer(
                this::repaint, !GraphicsEnvironment.isHeadless());
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
                if (blackoutDrawing) {
                    handleBlackoutClick(event.getPoint());
                    return;
                }
                TargetTrajectory target = selectedTarget.get();
                if (target == null) {
                    return;
                }

                if (drawingMode == DrawingMode.FREE_HAND) {
                    target.clearPath();
                    finishedSegmentedTarget = null;
                    addMapPoint(target, event.getPoint());
                    freeHandDrawing = true;
                } else {
                    if (target == finishedSegmentedTarget && !target.path().isEmpty()) {
                        return;
                    }
                    finishedSegmentedTarget = null;
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
        blackoutDrawing = false;
        blackoutFirstCorner = null;
        finishedSegmentedTarget = null;
        repaint();
    }

    void finishPath() {
        freeHandDrawing = false;
        blackoutDrawing = false;
        blackoutFirstCorner = null;
        TargetTrajectory target = selectedTarget.get();
        if (drawingMode == DrawingMode.SEGMENTED && target != null && !target.path().isEmpty()) {
            finishedSegmentedTarget = target;
        } else if (target == null || target.path().isEmpty()) {
            finishedSegmentedTarget = null;
        }
        mousePoint = null;
        onPathChanged.run();
        repaint();
    }

    void startBlackoutRegionDrawing() {
        freeHandDrawing = false;
        blackoutDrawing = true;
        blackoutFirstCorner = null;
        finishedSegmentedTarget = null;
        mousePoint = null;
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
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

    void focusOnTargets() {
        List<EcefPoint> points = new ArrayList<>();
        for (TargetTrajectory target : model.targets()) {
            points.addAll(target.path());
        }
        for (BlackoutRegion region : model.blackoutRegions()) {
            for (GeodeticPoint corner : region.corners()) {
                points.add(Wgs84.toEcef(corner));
            }
        }
        focusOnPoints(points);
    }

    void focusOnPoints(List<EcefPoint> points) {
        Double referenceLongitude = null;
        double minimumLongitude = Double.POSITIVE_INFINITY;
        double maximumLongitude = Double.NEGATIVE_INFINITY;
        double minimumLatitude = Double.POSITIVE_INFINITY;
        double maximumLatitude = Double.NEGATIVE_INFINITY;
        for (EcefPoint point : points) {
                GeodeticPoint geodetic = Wgs84.toGeodetic(point);
                if (referenceLongitude == null) {
                    referenceLongitude = geodetic.longitudeDegrees();
                }
                double unwrappedLongitude = referenceLongitude
                        + GeodeticPoint.normalizeLongitude(
                        geodetic.longitudeDegrees() - referenceLongitude);
                minimumLongitude = Math.min(minimumLongitude, unwrappedLongitude);
                maximumLongitude = Math.max(maximumLongitude, unwrappedLongitude);
                minimumLatitude = Math.min(minimumLatitude, geodetic.latitudeDegrees());
                maximumLatitude = Math.max(maximumLatitude, geodetic.latitudeDegrees());
        }
        if (referenceLongitude == null) {
            resetView();
            return;
        }

        centerLongitude = GeodeticPoint.normalizeLongitude(
                (minimumLongitude + maximumLongitude) / 2.0);
        double longitudeRange = Math.max(0.002, maximumLongitude - minimumLongitude);
        double latitudeRange = Math.max(0.001, maximumLatitude - minimumLatitude);
        double wantedLongitudeSpan = longitudeRange * 1.35;
        double wantedLatitudeSpan = latitudeRange * 1.35;
        zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM,
                Math.min(360.0 / wantedLongitudeSpan, 180.0 / wantedLatitudeSpan)));
        centerLatitude = clampCenterLatitude((minimumLatitude + maximumLatitude) / 2.0);
        repaint();
    }

    void setHighResolutionMapEnabled(boolean enabled) {
        highResolutionMapEnabled = enabled;
        repaint();
    }

    void setProfileHighlightNormalizedTime(double normalizedTime) {
        profileHighlightNormalizedTime = Double.isFinite(normalizedTime)
                ? Math.max(0.0, Math.min(1.0, normalizedTime))
                : Double.NaN;
        repaint();
    }

    void setStitchingFocus(
            Set<String> trackIds,
            Set<String> targetIds,
            StitchingVisibilityMode mode) {
        setStitchingFocus(trackIds, targetIds, mode, List.of());
    }

    void setStitchingFocus(
            Set<String> trackIds,
            Set<String> targetIds,
            StitchingVisibilityMode mode,
            List<TrackStitchingAnalyzer.Segment> segments) {
        stitchingTrackIds = trackIds == null ? Set.of() : Set.copyOf(trackIds);
        stitchingTargetIds = targetIds == null ? Set.of() : Set.copyOf(targetIds);
        stitchingSegments = segments == null ? List.of() : List.copyOf(segments);
        stitchingVisibilityMode = mode == null ? StitchingVisibilityMode.ALL : mode;
        repaint();
    }

    void clearStitchingFocus() {
        stitchingTrackIds = Set.of();
        stitchingTargetIds = Set.of();
        stitchingSegments = List.of();
        stitchingVisibilityMode = StitchingVisibilityMode.ALL;
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
            drawBlackoutRegions(g);
            drawBlackoutPreview(g);
            drawScaleBar(g);
            drawPlannedTrajectories(g);
            drawRecentHistory(g);
            drawMeasurements(g);
            drawTracks(g);
            drawDeadStitchingSegments(g);
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
            detailLayerActive = highResolutionMapEnabled
                    && zoom >= NaturalEarthDetailLayer.MINIMUM_CANVAS_ZOOM;
            if (detailLayerActive) {
                detailLayer.draw(
                        clipped,
                        map,
                        centerLongitude,
                        centerLatitude,
                        visibleLongitudeSpan(),
                        visibleLatitudeSpan(),
                        zoom);
            }
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
            if (displaySettings.gridVisible()) {
                g.setColor(GRID_COLOR);
                g.drawLine(x, map.y, x, map.y + map.height);
            }
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
            if (displaySettings.gridVisible()) {
                g.setColor(GRID_COLOR);
                g.drawLine(map.x, y, map.x + map.width, y);
            }
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

    private void drawBlackoutRegions(Graphics2D g) {
        if (model.blackoutRegions().isEmpty()) {
            return;
        }
        Rectangle map = mapBounds();
        for (BlackoutRegion region : model.blackoutRegions()) {
            Polygon polygon = new Polygon();
            for (GeodeticPoint corner : region.corners()) {
                Point point = toScreenUnclipped(corner);
                polygon.addPoint(point.x, point.y);
            }
            Graphics2D zone = (Graphics2D) g.create();
            try {
                zone.clip(map);
                zone.setColor(new Color(210, 214, 219, 124));
                zone.fillPolygon(polygon);
                zone.clip(polygon);
                zone.setStroke(new BasicStroke(7.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL));
                zone.setColor(new Color(92, 98, 105, 118));
                Rectangle bounds = polygon.getBounds();
                int start = bounds.x - bounds.height - 40;
                int end = bounds.x + bounds.width + 40;
                for (int x = start; x <= end; x += 22) {
                    zone.drawLine(x, bounds.y + bounds.height + 40,
                            x + bounds.height + 40, bounds.y);
                }
            } finally {
                zone.dispose();
            }
            g.setColor(new Color(60, 65, 72, 180));
            g.setStroke(new BasicStroke(1.4f));
            g.drawPolygon(polygon);
        }
    }

    private void drawBlackoutPreview(Graphics2D g) {
        if (!blackoutDrawing || blackoutFirstCorner == null || mousePoint == null
                || !mapBounds().contains(mousePoint)) {
            return;
        }
        Point first = toScreen(blackoutFirstCorner);
        if (first == null) {
            return;
        }
        int x = Math.min(first.x, mousePoint.x);
        int y = Math.min(first.y, mousePoint.y);
        int width = Math.abs(mousePoint.x - first.x);
        int height = Math.abs(mousePoint.y - first.y);
        if (width < 2 || height < 2) {
            return;
        }
        Stroke previousStroke = g.getStroke();
        g.setColor(new Color(230, 234, 238, 92));
        g.fillRect(x, y, width, height);
        g.setStroke(new BasicStroke(
                2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                10.0f, new float[]{8.0f, 5.0f}, 0.0f));
        g.setColor(new Color(35, 41, 48, 210));
        g.drawRect(x, y, width, height);
        g.setStroke(previousStroke);
        g.setFont(g.getFont().deriveFont(Font.BOLD, 11.0f));
        g.setColor(new Color(255, 255, 255, 225));
        g.fillRoundRect(mousePoint.x + 10, mousePoint.y - 28, 122, 22, 9, 9);
        g.setColor(new Color(45, 52, 60));
        g.drawString("Click second corner", mousePoint.x + 18, mousePoint.y - 13);
    }

    private void drawPlannedTrajectories(Graphics2D g) {
        if (!displaySettings.groundTruthVisible()) {
            return;
        }
        if (playback.isImportedReplay()) {
            for (GroundTruthView truth : playback.currentGroundTruthViews()) {
                if (hideTargetForStitching(truth.id())) {
                    continue;
                }
                if (truth.plannedPath().size() < 2) {
                    continue;
                }
                boolean greyed = greyTargetForStitching(truth.id());
                Color color = greyed ? mutedColor(truth.color()) : truth.color();
                g.setColor(withAlpha(color, greyed ? 70 : 170));
                g.setStroke(PLANNED_STROKE);
                drawEcefPath(g, truth.plannedPath());
            }
            return;
        }
        TargetTrajectory selected = selectedTarget.get();
        for (TargetTrajectory target : model.targets()) {
            if (hideTargetForStitching(target.id())) {
                continue;
            }
            if (target.path().size() < 2) {
                continue;
            }
            boolean greyed = greyTargetForStitching(target.id());
            Color color = greyed ? mutedColor(target.color()) : target.color();
            int alpha = greyed ? 70 : target == selected ? 215 : 130;
            g.setColor(withAlpha(color, alpha));
            g.setStroke(target == selected ? SELECTED_PLANNED_STROKE : PLANNED_STROKE);
            drawEcefPath(g, sampledGeodesicPath(target.path()));
            if (target == selected && Double.isFinite(profileHighlightNormalizedTime)) {
                drawProfileHighlight(g, target, color);
            }
        }
    }

    private void drawProfileHighlight(Graphics2D g, TargetTrajectory target, Color color) {
        if (!target.isRunnable() || profileHighlightNormalizedTime <= 0.0) {
            return;
        }
        List<EcefPoint> prefix = trajectoryPrefix(target, profileHighlightNormalizedTime);
        if (prefix.size() < 2) {
            return;
        }
        g.setColor(withAlpha(color, 245));
        g.setStroke(new BasicStroke(7.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        drawEcefPath(g, sampledGeodesicPath(prefix));
    }

    private void drawRecentHistory(Graphics2D g) {
        if (!displaySettings.groundTruthVisible()
                || displaySettings.groundTruthHistoryFraction() <= 0.0) {
            return;
        }
        g.setStroke(HISTORY_STROKE);
        if (playback.isImportedReplay()) {
            for (GroundTruthView truth : playback.currentGroundTruthViews()) {
                if (hideTargetForStitching(truth.id())) {
                    continue;
                }
                List<EcefPoint> history = historySlice(
                        truth.history(), displaySettings.groundTruthHistoryFraction());
                if (history.size() >= 2) {
                    boolean greyed = greyTargetForStitching(truth.id());
                    Color color = greyed ? mutedColor(truth.color()) : truth.color();
                    g.setColor(withAlpha(color, greyed ? 95 : 255));
                    drawEcefPath(g, history);
                }
            }
            return;
        }
        for (Map.Entry<TargetTrajectory, Deque<EcefPoint>> entry : playback.recentHistory().entrySet()) {
            if (hideTargetForStitching(entry.getKey().id())) {
                continue;
            }
            List<EcefPoint> history = historySlice(
                    new ArrayList<>(entry.getValue()),
                    displaySettings.groundTruthHistoryFraction());
            if (history.size() < 2) {
                continue;
            }
            boolean greyed = greyTargetForStitching(entry.getKey().id());
            Color color = greyed ? mutedColor(entry.getKey().color()) : entry.getKey().color();
            g.setColor(withAlpha(color, greyed ? 95 : 255));
            drawEcefPath(g, history);
        }
    }

    private void drawCurrentTargets(Graphics2D g) {
        if (!displaySettings.groundTruthVisible()) {
            return;
        }
        if (playback.isImportedReplay()) {
            for (GroundTruthView truth : playback.currentGroundTruthViews()) {
                if (hideTargetForStitching(truth.id())) {
                    continue;
                }
                boolean greyed = greyTargetForStitching(truth.id());
                Color color = greyed ? mutedColor(truth.color()) : truth.color();
                drawTargetMarker(g, truth.id(), truth.currentPosition(), color, 4);
            }
            return;
        }
        for (TargetTrajectory target : model.targets()) {
            if (hideTargetForStitching(target.id())) {
                continue;
            }
            EcefPoint position = playback.currentPosition(target);
            if (position == null || (!playback.isRunning()
                    && !playback.isReplayDisplayActive()
                    && playback.elapsedSeconds() <= 0.0)) {
                continue;
            }
            boolean greyed = greyTargetForStitching(target.id());
            Color color = greyed ? mutedColor(target.color()) : target.color();
            int radius = target == selectedTarget.get() ? 6 : 4;
            drawTargetMarker(g, target.id(), position, color, radius);
        }
    }

    private void drawMeasurements(Graphics2D g) {
        if (!displaySettings.measurementsVisible()) {
            return;
        }
        for (TargetMeasurement measurement
                : measurementEngine.measurementHistoryAt(
                        playback.elapsedSeconds(),
                        displaySettings.measurementHistoryFraction())) {
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

    private void drawTargetMarker(
            Graphics2D g,
            String id,
            EcefPoint position,
            Color color,
            int radius) {
        Point point = toScreen(Wgs84.toGeodetic(position));
        if (point == null) {
            return;
        }
        g.setColor(Color.WHITE);
        g.fillOval(point.x - radius - 2, point.y - radius - 2,
                radius * 2 + 4, radius * 2 + 4);
        g.setColor(color);
        g.fillOval(point.x - radius, point.y - radius, radius * 2, radius * 2);
        g.setColor(new Color(31, 38, 45));
        g.drawString(id, point.x + radius + 5, point.y - radius - 2);
    }

    private static List<EcefPoint> historySlice(
            List<EcefPoint> history,
            double fraction) {
        if (history.isEmpty() || fraction <= 0.0) {
            return List.of();
        }
        int count = Math.max(1, (int) Math.ceil(history.size() * fraction));
        return history.subList(Math.max(0, history.size() - count), history.size());
    }

    private void drawTracks(Graphics2D g) {
        for (TrackView track : playback.currentTrackViews()) {
            if (hideTrackForStitching(track.id())) {
                continue;
            }
            boolean greyed = greyTrackForStitching(track.id());
            Color color = track.dead()
                    ? new Color(145, 150, 156)
                    : greyed ? mutedColor(track.color()) : track.color();
            if (track.tail().size() >= 2) {
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(),
                        track.dead() ? 105 : greyed ? 135 : 210));
                g.setStroke(new BasicStroke(
                        track.dead() || greyed ? 2.0f : 3.2f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                drawEcefPath(g, track.tail());
            }
            drawTrackCovariance(g, track, color, greyed);

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
            g.setColor(track.dead() || greyed
                    ? new Color(105, 110, 116)
                    : new Color(25, 31, 37));
            String label = track.dead() ? track.id() + " (dead)" : track.id();
            g.drawString(label, point.x + radius + 5, point.y - radius - 2);
        }
    }

    private void drawDeadStitchingSegments(Graphics2D g) {
        if (stitchingSegments.isEmpty()) {
            return;
        }
        Color deadColor = new Color(145, 150, 156);
        for (TrackStitchingAnalyzer.Segment segment : stitchingSegments) {
            if (!segment.deadAtEvent()) {
                continue;
            }
            List<EcefPoint> tail = segment.history().stream()
                    .map(TrackRecord::state)
                    .map(EarthMapCanvas::pointFromState)
                    .toList();
            if (tail.size() >= 2) {
                g.setColor(new Color(
                        deadColor.getRed(), deadColor.getGreen(), deadColor.getBlue(), 175));
                g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                drawEcefPath(g, tail);
            }
            TrackRecord last = segment.lastObservedRecord();
            Point point = toScreen(Wgs84.toGeodetic(pointFromState(last.state())));
            if (point == null) {
                continue;
            }
            int radius = 7;
            g.setColor(new Color(20, 25, 31, 210));
            g.fillRect(point.x - radius - 2, point.y - radius - 2,
                    radius * 2 + 4, radius * 2 + 4);
            g.setColor(deadColor);
            g.fillRect(point.x - radius, point.y - radius, radius * 2, radius * 2);
            g.setColor(Color.WHITE);
            g.drawRect(point.x - radius, point.y - radius, radius * 2, radius * 2);
            g.setColor(new Color(105, 110, 116));
            g.drawString(segment.trackId() + " (dead)", point.x + radius + 5, point.y - radius - 2);
        }
    }

    private void drawTrackCovariance(
            Graphics2D g,
            TrackView track,
            Color color,
            boolean greyed) {
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
                track.dead() ? 75 : greyed ? 110 : 190));
        g.setStroke(new BasicStroke(track.dead() || greyed ? 1.2f : 1.8f));
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
        if (target == finishedSegmentedTarget) {
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
        String source = detailLayerActive
                ? "NASA Blue Marble + " + detailLayer.statusText()
                : "NASA Blue Marble";
        String text = "%s • %s • %s"
                .formatted(source, viewDescription(), viewSizeDescription());
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
                playback.elapsedSeconds(), playback.durationSeconds());
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

    private static List<EcefPoint> trajectoryPrefix(
            TargetTrajectory target,
            double normalizedTime) {
        if (target.path().size() < 2) {
            return List.of();
        }
        double wantedDistance = target.surfaceLengthMeters()
                * target.velocityProfile().normalizedIntegralAt(normalizedTime);
        if (wantedDistance <= 1.0e-6) {
            return List.of(target.path().get(0));
        }
        List<EcefPoint> prefix = new ArrayList<>();
        prefix.add(target.path().get(0));
        double traversed = 0.0;
        for (int index = 1; index < target.path().size(); index++) {
            EcefPoint start = target.path().get(index - 1);
            EcefPoint end = target.path().get(index);
            GeodeticPoint startGeo = Wgs84.toGeodetic(start);
            GeodeticPoint endGeo = Wgs84.toGeodetic(end);
            double segmentLength = Wgs84Geodesic.inverse(startGeo, endGeo).distanceMeters();
            if (traversed + segmentLength >= wantedDistance) {
                double fraction = segmentLength <= 1.0e-9
                        ? 0.0
                        : (wantedDistance - traversed) / segmentLength;
                prefix.add(Wgs84.toEcef(Wgs84Geodesic.interpolate(
                        startGeo, endGeo, fraction, 0.0)));
                return List.copyOf(prefix);
            }
            prefix.add(end);
            traversed += segmentLength;
        }
        return List.copyOf(prefix);
    }

    private void addMapPoint(TargetTrajectory target, Point point) {
        target.addPathPoint(Wgs84.toEcef(toGeodetic(point).withAltitude(0.0)));
    }

    private void handleBlackoutClick(Point point) {
        GeodeticPoint corner = toGeodetic(point).withAltitude(0.0);
        if (blackoutFirstCorner == null) {
            blackoutFirstCorner = corner;
            mousePoint = point;
            repaint();
            return;
        }
        BlackoutRegion region = model.addUserBlackoutRegion(blackoutFirstCorner, corner);
        blackoutDrawing = false;
        blackoutFirstCorner = null;
        onBlackoutRegionCreated.accept(region);
        repaint();
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
        return toScreenUnclipped(point);
    }

    private Point toScreenUnclipped(GeodeticPoint point) {
        double longitudeDelta = GeodeticPoint.normalizeLongitude(
                point.longitudeDegrees() - centerLongitude);
        double latitudeDelta = point.latitudeDegrees() - centerLatitude;
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

    private static Color mutedColor(Color color) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        return Color.getHSBColor(
                hsb[0],
                Math.max(0.08f, hsb[1] * 0.28f),
                Math.min(0.92f, Math.max(0.45f, hsb[2] * 0.82f + 0.12f)));
    }

    private static EcefPoint pointFromState(double[] state) {
        return new EcefPoint(state[0], state[1], state[2]);
    }

    private boolean stitchingFocusActive() {
        return !stitchingTrackIds.isEmpty() || !stitchingTargetIds.isEmpty();
    }

    private boolean hideTrackForStitching(String trackId) {
        return stitchingFocusActive()
                && stitchingVisibilityMode == StitchingVisibilityMode.ONLY_STITCHED
                && !stitchingTrackIds.contains(trackId);
    }

    private boolean greyTrackForStitching(String trackId) {
        return stitchingFocusActive()
                && stitchingVisibilityMode == StitchingVisibilityMode.GREYED
                && !stitchingTrackIds.contains(trackId);
    }

    private boolean hideTargetForStitching(String targetId) {
        return stitchingFocusActive()
                && stitchingVisibilityMode == StitchingVisibilityMode.ONLY_STITCHED
                && !stitchingTargetIds.contains(targetId);
    }

    private boolean greyTargetForStitching(String targetId) {
        return stitchingFocusActive()
                && stitchingVisibilityMode == StitchingVisibilityMode.GREYED
                && !stitchingTargetIds.contains(targetId);
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
