package com.targettracker.ui;

import com.targettracker.analysis.TrackStitchingAnalyzer;
import com.targettracker.model.EcefPoint;
import com.targettracker.model.GeodeticPoint;
import com.targettracker.model.Wgs84;
import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.tracking.TrackRecord;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static, locally focused world view for one stitching-candidate timestamp. */
final class StitchingMapPanel extends JPanel {
    private static final String EARTH_IMAGE_RESOURCE = "/maps/blue_marble_2048.png";
    private static final int MARGIN = 54;
    private static final BufferedImage EARTH_IMAGE = loadEarthImage();

    private final RecordedScenario scenario;
    private final TrackStitchingAnalyzer.EventResult event;
    private final Set<String> oldTrackIds = new HashSet<>();
    private final Set<String> newTrackIds = new HashSet<>();
    private final double centerLongitude;
    private final double centerLatitude;
    private final double longitudeSpan;
    private final double latitudeSpan;

    StitchingMapPanel(
            RecordedScenario scenario,
            TrackStitchingAnalyzer.EventResult event) {
        this.scenario = scenario;
        this.event = event;
        event.oldSegments().forEach(segment -> oldTrackIds.add(segment.trackId()));
        event.newSegments().forEach(segment -> newTrackIds.add(segment.trackId()));
        Bounds bounds = actionBounds();
        centerLongitude = bounds.centerLongitude();
        centerLatitude = bounds.centerLatitude();
        longitudeSpan = bounds.longitudeSpan();
        latitudeSpan = bounds.latitudeSpan();
        setBackground(new Color(246, 248, 251));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Rectangle map = mapBounds();
            g.setColor(new Color(2, 12, 38));
            g.fillRect(map.x, map.y, map.width, map.height);
            Graphics2D clipped = (Graphics2D) g.create();
            try {
                clipped.clip(map);
                drawRaster(clipped, map);
                clipped.setColor(new Color(2, 10, 27, 35));
                clipped.fillRect(map.x, map.y, map.width, map.height);
                drawTruth(clipped);
                drawTracks(clipped);
                drawMeasurements(clipped);
            } finally {
                clipped.dispose();
            }
            drawAxes(g, map);
            drawHeader(g, map);
        } finally {
            g.dispose();
        }
    }

    private void drawTruth(Graphics2D g) {
        Map<String, List<EcefPoint>> histories = new LinkedHashMap<>();
        for (GroundTruthRecord record : scenario.groundTruth()) {
            if (record.timeSeconds() <= event.timeSeconds() + 1.0e-8) {
                histories.computeIfAbsent(record.targetId(), ignored -> new ArrayList<>())
                        .add(point(record.state()));
            }
        }
        int colorIndex = 0;
        Color[] colors = {new Color(50, 150, 255), new Color(255, 132, 55),
                new Color(80, 190, 105), new Color(186, 88, 220)};
        for (Map.Entry<String, List<EcefPoint>> entry : histories.entrySet()) {
            Color color = colors[colorIndex++ % colors.length];
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 190));
            g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawPath(g, entry.getValue());
            if (!entry.getValue().isEmpty()) {
                Point head = toScreen(entry.getValue().get(entry.getValue().size() - 1));
                if (head != null) {
                    g.setColor(Color.WHITE);
                    g.fillOval(head.x - 5, head.y - 5, 10, 10);
                    g.setColor(color);
                    g.fillOval(head.x - 3, head.y - 3, 6, 6);
                }
            }
        }
    }

    private void drawTracks(Graphics2D g) {
        Map<String, List<EcefPoint>> histories = new LinkedHashMap<>();
        for (TrackRecord record : scenario.records()) {
            if (record.timeSeconds() <= event.timeSeconds() + 1.0e-8) {
                histories.computeIfAbsent(record.trackId(), ignored -> new ArrayList<>())
                        .add(point(record.state()));
            }
        }
        for (Map.Entry<String, List<EcefPoint>> entry : histories.entrySet()) {
            Color color = oldTrackIds.contains(entry.getKey())
                    ? new Color(255, 178, 55)
                    : newTrackIds.contains(entry.getKey())
                    ? new Color(72, 232, 255)
                    : new Color(205, 210, 218);
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 225));
            g.setStroke(new BasicStroke(
                    oldTrackIds.contains(entry.getKey()) || newTrackIds.contains(entry.getKey())
                            ? 4.0f : 2.0f,
                    BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND));
            drawPath(g, entry.getValue());
            if (!entry.getValue().isEmpty()) {
                Point head = toScreen(entry.getValue().get(entry.getValue().size() - 1));
                if (head != null) {
                    g.setColor(new Color(15, 21, 29));
                    g.fillRect(head.x - 6, head.y - 6, 12, 12);
                    g.setColor(color);
                    g.fillRect(head.x - 4, head.y - 4, 8, 8);
                    g.drawString(entry.getKey(), head.x + 7, head.y - 7);
                }
            }
        }
    }

    private void drawMeasurements(Graphics2D g) {
        g.setStroke(new BasicStroke(2.0f));
        for (RecordedMeasurement measurement : scenario.measurements()) {
            if (measurement.timeSeconds() > event.timeSeconds() + 1.0e-8) {
                continue;
            }
            Point point = toScreen(point(measurement.mean()));
            if (point == null) {
                continue;
            }
            int radius = 5;
            g.setColor(Color.WHITE);
            g.drawLine(point.x - radius, point.y - radius, point.x + radius, point.y + radius);
            g.drawLine(point.x - radius, point.y + radius, point.x + radius, point.y - radius);
        }
    }

    private void drawAxes(Graphics2D g, Rectangle map) {
        g.setColor(new Color(255, 255, 255, 85));
        g.setStroke(new BasicStroke(1.0f));
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 10.0f));
        for (int index = 0; index <= 5; index++) {
            int x = map.x + map.width * index / 5;
            int y = map.y + map.height * index / 5;
            g.drawLine(x, map.y, x, map.y + map.height);
            g.drawLine(map.x, y, map.x + map.width, y);
            g.setColor(new Color(73, 86, 97));
            double longitude = centerLongitude - longitudeSpan / 2.0
                    + longitudeSpan * index / 5.0;
            double latitude = centerLatitude + latitudeSpan / 2.0
                    - latitudeSpan * index / 5.0;
            g.drawString("%.4f°".formatted(GeodeticPoint.normalizeLongitude(longitude)),
                    x - 18, map.y + map.height + 17);
            g.drawString("%.4f°".formatted(latitude), 3, y + 4);
            g.setColor(new Color(255, 255, 255, 85));
        }
        g.setColor(new Color(58, 68, 78));
        g.drawRect(map.x, map.y, map.width, map.height);
    }

    private void drawHeader(Graphics2D g, Rectangle map) {
        String text = "t = %s  •  old: %s  •  new: %s".formatted(
                formatTime(event.timeSeconds()),
                String.join(", ", oldTrackIds),
                String.join(", ", newTrackIds));
        g.setFont(g.getFont().deriveFont(Font.BOLD, 14.0f));
        int width = g.getFontMetrics().stringWidth(text) + 18;
        g.setColor(new Color(255, 255, 255, 220));
        g.fillRoundRect(map.x + 8, map.y + 8, width, 27, 10, 10);
        g.setColor(new Color(31, 39, 47));
        g.drawString(text, map.x + 17, map.y + 27);
    }

    private Bounds actionBounds() {
        Set<String> candidateIds = new HashSet<>();
        candidateIds.addAll(oldTrackIds);
        candidateIds.addAll(newTrackIds);
        List<EcefPoint> actionPoints = new ArrayList<>();
        for (String id : candidateIds) {
            TrackRecord last = null;
            for (TrackRecord record : scenario.records()) {
                if (record.trackId().equals(id)
                        && record.timeSeconds() <= event.timeSeconds() + 1.0e-8) {
                    last = record;
                }
            }
            if (last != null) {
                actionPoints.add(point(last.state()));
            }
        }
        for (RecordedMeasurement measurement : scenario.measurements()) {
            if (Math.abs(measurement.timeSeconds() - event.timeSeconds()) < 1.0e-6) {
                actionPoints.add(point(measurement.mean()));
            }
        }
        if (actionPoints.isEmpty()) {
            scenario.records().stream().findFirst()
                    .ifPresent(record -> actionPoints.add(point(record.state())));
        }
        if (actionPoints.isEmpty()) {
            return new Bounds(0.0, 0.0, 10.0, 5.0);
        }

        double referenceLongitude = Wgs84.toGeodetic(actionPoints.get(0)).longitudeDegrees();
        double minimumLongitude = Double.POSITIVE_INFINITY;
        double maximumLongitude = Double.NEGATIVE_INFINITY;
        double minimumLatitude = Double.POSITIVE_INFINITY;
        double maximumLatitude = Double.NEGATIVE_INFINITY;
        for (EcefPoint point : actionPoints) {
            GeodeticPoint geodetic = Wgs84.toGeodetic(point);
            double longitude = referenceLongitude + GeodeticPoint.normalizeLongitude(
                    geodetic.longitudeDegrees() - referenceLongitude);
            minimumLongitude = Math.min(minimumLongitude, longitude);
            maximumLongitude = Math.max(maximumLongitude, longitude);
            minimumLatitude = Math.min(minimumLatitude, geodetic.latitudeDegrees());
            maximumLatitude = Math.max(maximumLatitude, geodetic.latitudeDegrees());
        }
        double longitudeRange = Math.max(0.01, maximumLongitude - minimumLongitude);
        double latitudeRange = Math.max(0.005, maximumLatitude - minimumLatitude);
        return new Bounds(
                GeodeticPoint.normalizeLongitude((minimumLongitude + maximumLongitude) / 2.0),
                (minimumLatitude + maximumLatitude) / 2.0,
                longitudeRange * 1.5,
                latitudeRange * 1.5);
    }

    private void drawRaster(Graphics2D g, Rectangle map) {
        double minimumLongitude = centerLongitude - longitudeSpan / 2.0;
        double maximumLatitude = centerLatitude + latitudeSpan / 2.0;
        int firstCopy = (int) Math.floor((minimumLongitude + 180.0) / 360.0) - 1;
        int lastCopy = firstCopy + 3;
        double destinationY = map.y + (maximumLatitude - 90.0) / latitudeSpan * map.height;
        double destinationHeight = 180.0 / latitudeSpan * map.height;
        double destinationWidth = 360.0 / longitudeSpan * map.width;
        for (int copy = firstCopy; copy <= lastCopy; copy++) {
            double tileMinimumLongitude = -180.0 + 360.0 * copy;
            double destinationX = map.x
                    + (tileMinimumLongitude - minimumLongitude) / longitudeSpan * map.width;
            AffineTransform transform = new AffineTransform();
            transform.translate(destinationX, destinationY);
            transform.scale(destinationWidth / EARTH_IMAGE.getWidth(),
                    destinationHeight / EARTH_IMAGE.getHeight());
            g.drawImage(EARTH_IMAGE, transform, null);
        }
    }

    private void drawPath(Graphics2D g, List<EcefPoint> points) {
        Point previous = null;
        for (EcefPoint point : points) {
            Point next = toScreen(point);
            if (previous != null && next != null) {
                g.drawLine(previous.x, previous.y, next.x, next.y);
            }
            previous = next;
        }
    }

    private Point toScreen(EcefPoint point) {
        GeodeticPoint geodetic = Wgs84.toGeodetic(point);
        double longitudeDelta = GeodeticPoint.normalizeLongitude(
                geodetic.longitudeDegrees() - centerLongitude);
        double latitudeDelta = geodetic.latitudeDegrees() - centerLatitude;
        if (Math.abs(longitudeDelta) > longitudeSpan / 2.0
                || Math.abs(latitudeDelta) > latitudeSpan / 2.0) {
            return null;
        }
        Rectangle map = mapBounds();
        return new Point(
                map.x + map.width / 2
                        + (int) Math.round(longitudeDelta / longitudeSpan * map.width),
                map.y + map.height / 2
                        - (int) Math.round(latitudeDelta / latitudeSpan * map.height));
    }

    private Rectangle mapBounds() {
        return new Rectangle(
                MARGIN,
                MARGIN,
                Math.max(1, getWidth() - MARGIN * 2),
                Math.max(1, getHeight() - MARGIN * 2));
    }

    private static EcefPoint point(double[] state) {
        return new EcefPoint(state[0], state[1], state[2]);
    }

    private static String formatTime(double seconds) {
        int whole = Math.max(0, (int) Math.floor(seconds));
        int tenths = (int) Math.round((seconds - whole) * 10.0);
        if (tenths == 10) {
            whole++;
            tenths = 0;
        }
        return "%02d:%02d.%01d".formatted(
                whole / 60, whole % 60, tenths);
    }

    private static BufferedImage loadEarthImage() {
        try (InputStream input = StitchingMapPanel.class.getResourceAsStream(EARTH_IMAGE_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled Earth map");
            }
            return ImageIO.read(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load bundled Earth map", exception);
        }
    }

    private record Bounds(
            double centerLongitude,
            double centerLatitude,
            double longitudeSpan,
            double latitudeSpan) {
    }
}
