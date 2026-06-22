package com.targettracker.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Bundled Natural Earth 1:10m vector detail; no runtime network access. */
final class NaturalEarthDetailLayer {
    static final double MINIMUM_CANVAS_ZOOM = 4.0;

    private static final String COASTLINE_RESOURCE =
            "/maps/natural_earth/ne_10m_coastline.geojson";
    private static final String BOUNDARY_RESOURCE =
            "/maps/natural_earth/ne_10m_admin_0_boundary_lines_land.geojson";
    private static final String RIVER_RESOURCE =
            "/maps/natural_earth/ne_10m_rivers_lake_centerlines.geojson";

    private final Runnable onLoaded;
    private final boolean asynchronousLoadingEnabled;
    private volatile DetailData data = DetailData.EMPTY;
    private volatile boolean loading;
    private volatile boolean loaded;
    private volatile String loadError;

    NaturalEarthDetailLayer(Runnable onLoaded, boolean asynchronousLoadingEnabled) {
        this.onLoaded = onLoaded;
        this.asynchronousLoadingEnabled = asynchronousLoadingEnabled;
    }

    void draw(
            Graphics2D graphics,
            Rectangle map,
            double centerLongitude,
            double centerLatitude,
            double longitudeSpan,
            double latitudeSpan,
            double canvasZoom) {
        if (canvasZoom < MINIMUM_CANVAS_ZOOM) {
            return;
        }
        ensureLoaded();
        if (!loaded) {
            return;
        }

        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clip(map);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            drawLines(g, map, data.rivers(), centerLongitude, centerLatitude,
                    longitudeSpan, latitudeSpan,
                    new Color(82, 198, 255, 185), 1.15f, null);
            drawLines(g, map, data.boundaries(), centerLongitude, centerLatitude,
                    longitudeSpan, latitudeSpan,
                    new Color(255, 215, 118, 185), 1.15f, new float[]{6.0f, 5.0f});
            drawLines(g, map, data.coastlines(), centerLongitude, centerLatitude,
                    longitudeSpan, latitudeSpan,
                    new Color(8, 18, 28, 205), 3.6f, null);
            drawLines(g, map, data.coastlines(), centerLongitude, centerLatitude,
                    longitudeSpan, latitudeSpan,
                    new Color(244, 250, 255, 235), 1.5f, null);
        } finally {
            g.dispose();
        }
    }

    String statusText() {
        if (loadError != null) {
            return "Natural Earth detail unavailable";
        }
        return loaded ? "Natural Earth offline detail" : "Loading offline map detail…";
    }

    boolean isLoaded() {
        return loaded;
    }

    int lineCount() {
        return data.coastlines().size() + data.boundaries().size() + data.rivers().size();
    }

    void loadSynchronously() {
        try {
            data = loadBundledData();
            loaded = true;
            loadError = null;
        } catch (IOException exception) {
            loadError = exception.getMessage();
            loaded = false;
        }
    }

    private synchronized void ensureLoaded() {
        if (loaded || loading || loadError != null || !asynchronousLoadingEnabled) {
            return;
        }
        loading = true;
        Thread loader = new Thread(() -> {
            loadSynchronously();
            loading = false;
            javax.swing.SwingUtilities.invokeLater(onLoaded);
        }, "natural-earth-map-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private static DetailData loadBundledData() throws IOException {
        return new DetailData(
                parseResource(COASTLINE_RESOURCE),
                parseResource(BOUNDARY_RESOURCE),
                parseResource(RIVER_RESOURCE));
    }

    static List<GeoLine> parseResource(String resource) throws IOException {
        try (InputStream input = NaturalEarthDetailLayer.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing bundled Natural Earth data: " + resource);
            }
            try (Reader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8), 65_536)) {
                List<GeoLine> lines = new ArrayList<>();
                JsonCursor cursor = new JsonCursor(reader);
                scanValue(cursor, lines);
                return List.copyOf(lines);
            }
        }
    }

    private static void drawLines(
            Graphics2D g,
            Rectangle map,
            List<GeoLine> lines,
            double centerLongitude,
            double centerLatitude,
            double longitudeSpan,
            double latitudeSpan,
            Color color,
            float width,
            float[] dash) {
        double west = centerLongitude - longitudeSpan / 2.0;
        double east = centerLongitude + longitudeSpan / 2.0;
        double south = centerLatitude - latitudeSpan / 2.0;
        double north = centerLatitude + latitudeSpan / 2.0;
        Path2D path = new Path2D.Double();

        for (GeoLine line : lines) {
            if (line.maximumLatitude() < south || line.minimumLatitude() > north) {
                continue;
            }
            double midpoint = (line.minimumLongitude() + line.maximumLongitude()) / 2.0;
            double shift = Math.rint((centerLongitude - midpoint) / 360.0) * 360.0;
            double shiftedMinimum = line.minimumLongitude() + shift;
            double shiftedMaximum = line.maximumLongitude() + shift;
            if (shiftedMaximum < west || shiftedMinimum > east) {
                continue;
            }

            double[] coordinates = line.coordinates();
            for (int index = 2; index < coordinates.length; index += 2) {
                double previousLongitude = coordinates[index - 2] + shift;
                double previousLatitude = coordinates[index - 1];
                double longitude = coordinates[index] + shift;
                double latitude = coordinates[index + 1];
                if (Math.abs(longitude - previousLongitude) > 180.0
                        || Math.max(previousLongitude, longitude) < west
                        || Math.min(previousLongitude, longitude) > east
                        || Math.max(previousLatitude, latitude) < south
                        || Math.min(previousLatitude, latitude) > north) {
                    continue;
                }
                path.moveTo(
                        map.x + (previousLongitude - west) / longitudeSpan * map.width,
                        map.y + (north - previousLatitude) / latitudeSpan * map.height);
                path.lineTo(
                        map.x + (longitude - west) / longitudeSpan * map.width,
                        map.y + (north - latitude) / latitudeSpan * map.height);
            }
        }

        g.setColor(color);
        g.setStroke(dash == null
                ? new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                : new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                10.0f, dash, 0.0f));
        g.draw(path);
    }

    private static void scanValue(JsonCursor cursor, List<GeoLine> lines) throws IOException {
        cursor.skipWhitespace();
        int next = cursor.peek();
        if (next == '{') {
            scanObject(cursor, lines);
        } else if (next == '[') {
            cursor.expect('[');
            cursor.skipWhitespace();
            if (cursor.consume(']')) {
                return;
            }
            do {
                scanValue(cursor, lines);
                cursor.skipWhitespace();
            } while (cursor.consume(','));
            cursor.expect(']');
        } else if (next == '"') {
            cursor.skipString();
        } else {
            cursor.skipPrimitive();
        }
    }

    private static void scanObject(JsonCursor cursor, List<GeoLine> lines) throws IOException {
        cursor.expect('{');
        cursor.skipWhitespace();
        if (cursor.consume('}')) {
            return;
        }
        do {
            String key = cursor.readString();
            cursor.skipWhitespace();
            cursor.expect(':');
            if ("geometry".equals(key)) {
                parseGeometry(cursor, lines);
            } else {
                scanValue(cursor, lines);
            }
            cursor.skipWhitespace();
        } while (cursor.consume(','));
        cursor.expect('}');
    }

    private static void parseGeometry(JsonCursor cursor, List<GeoLine> lines) throws IOException {
        cursor.skipWhitespace();
        if (cursor.peek() != '{') {
            scanValue(cursor, lines);
            return;
        }
        cursor.expect('{');
        String geometryType = null;
        cursor.skipWhitespace();
        if (cursor.consume('}')) {
            return;
        }
        do {
            String key = cursor.readString();
            cursor.skipWhitespace();
            cursor.expect(':');
            if ("type".equals(key)) {
                geometryType = cursor.readString();
            } else if ("coordinates".equals(key) && "LineString".equals(geometryType)) {
                GeoLine line = parseLineString(cursor);
                if (line != null) {
                    lines.add(line);
                }
            } else if ("coordinates".equals(key) && "MultiLineString".equals(geometryType)) {
                parseMultiLineString(cursor, lines);
            } else {
                scanValue(cursor, lines);
            }
            cursor.skipWhitespace();
        } while (cursor.consume(','));
        cursor.expect('}');
    }

    private static void parseMultiLineString(JsonCursor cursor, List<GeoLine> lines)
            throws IOException {
        cursor.expect('[');
        cursor.skipWhitespace();
        if (cursor.consume(']')) {
            return;
        }
        do {
            GeoLine line = parseLineString(cursor);
            if (line != null) {
                lines.add(line);
            }
            cursor.skipWhitespace();
        } while (cursor.consume(','));
        cursor.expect(']');
    }

    private static GeoLine parseLineString(JsonCursor cursor) throws IOException {
        cursor.expect('[');
        DoubleValues values = new DoubleValues();
        cursor.skipWhitespace();
        if (cursor.consume(']')) {
            return null;
        }
        do {
            cursor.expect('[');
            double longitude = cursor.readNumber();
            cursor.skipWhitespace();
            cursor.expect(',');
            double latitude = cursor.readNumber();
            while (true) {
                cursor.skipWhitespace();
                if (!cursor.consume(',')) {
                    break;
                }
                cursor.skipPrimitive();
            }
            cursor.expect(']');
            values.add(longitude);
            values.add(latitude);
            cursor.skipWhitespace();
        } while (cursor.consume(','));
        cursor.expect(']');
        return values.size() >= 4 ? GeoLine.from(values.toArray()) : null;
    }

    record GeoLine(
            double[] coordinates,
            double minimumLongitude,
            double maximumLongitude,
            double minimumLatitude,
            double maximumLatitude) {
        static GeoLine from(double[] coordinates) {
            double minimumLongitude = Double.POSITIVE_INFINITY;
            double maximumLongitude = Double.NEGATIVE_INFINITY;
            double minimumLatitude = Double.POSITIVE_INFINITY;
            double maximumLatitude = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < coordinates.length; index += 2) {
                minimumLongitude = Math.min(minimumLongitude, coordinates[index]);
                maximumLongitude = Math.max(maximumLongitude, coordinates[index]);
                minimumLatitude = Math.min(minimumLatitude, coordinates[index + 1]);
                maximumLatitude = Math.max(maximumLatitude, coordinates[index + 1]);
            }
            return new GeoLine(coordinates, minimumLongitude, maximumLongitude,
                    minimumLatitude, maximumLatitude);
        }
    }

    private record DetailData(
            List<GeoLine> coastlines,
            List<GeoLine> boundaries,
            List<GeoLine> rivers) {
        private static final DetailData EMPTY = new DetailData(List.of(), List.of(), List.of());
    }

    private static final class DoubleValues {
        private double[] values = new double[128];
        private int size;

        void add(double value) {
            if (size == values.length) {
                double[] expanded = new double[values.length * 2];
                System.arraycopy(values, 0, expanded, 0, values.length);
                values = expanded;
            }
            values[size++] = value;
        }

        int size() {
            return size;
        }

        double[] toArray() {
            double[] copy = new double[size];
            System.arraycopy(values, 0, copy, 0, size);
            return copy;
        }
    }

    private static final class JsonCursor {
        private final Reader reader;
        private int buffered = Integer.MIN_VALUE;

        JsonCursor(Reader reader) {
            this.reader = reader;
        }

        int peek() throws IOException {
            if (buffered == Integer.MIN_VALUE) {
                buffered = reader.read();
            }
            return buffered;
        }

        int read() throws IOException {
            int value = peek();
            buffered = Integer.MIN_VALUE;
            return value;
        }

        void skipWhitespace() throws IOException {
            while (Character.isWhitespace(peek())) {
                read();
            }
        }

        boolean consume(char expected) throws IOException {
            skipWhitespace();
            if (peek() != expected) {
                return false;
            }
            read();
            return true;
        }

        void expect(char expected) throws IOException {
            skipWhitespace();
            int actual = read();
            if (actual != expected) {
                throw new IOException("Malformed GeoJSON: expected '" + expected
                        + "' but found '" + (char) actual + "'");
            }
        }

        String readString() throws IOException {
            skipWhitespace();
            expect('"');
            StringBuilder value = new StringBuilder();
            while (true) {
                int character = read();
                if (character < 0) {
                    throw new IOException("Malformed GeoJSON string");
                }
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    character = readEscaped();
                }
                value.append((char) character);
            }
        }

        void skipString() throws IOException {
            skipWhitespace();
            expect('"');
            while (true) {
                int character = read();
                if (character < 0) {
                    throw new IOException("Malformed GeoJSON string");
                }
                if (character == '"') {
                    return;
                }
                if (character == '\\') {
                    int escaped = read();
                    if (escaped == 'u') {
                        for (int index = 0; index < 4; index++) {
                            read();
                        }
                    }
                }
            }
        }

        double readNumber() throws IOException {
            skipWhitespace();
            StringBuilder number = new StringBuilder(24);
            while (true) {
                int character = peek();
                if ((character >= '0' && character <= '9')
                        || character == '-' || character == '+' || character == '.'
                        || character == 'e' || character == 'E') {
                    number.append((char) read());
                } else {
                    break;
                }
            }
            try {
                return Double.parseDouble(number.toString());
            } catch (NumberFormatException exception) {
                throw new IOException("Malformed GeoJSON number: " + number, exception);
            }
        }

        void skipPrimitive() throws IOException {
            skipWhitespace();
            while (true) {
                int character = peek();
                if (character < 0 || character == ',' || character == ']'
                        || character == '}' || Character.isWhitespace(character)) {
                    return;
                }
                read();
            }
        }

        private int readEscaped() throws IOException {
            int escaped = read();
            if (escaped != 'u') {
                return switch (escaped) {
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> escaped;
                };
            }
            int value = 0;
            for (int index = 0; index < 4; index++) {
                int digit = Character.digit(read(), 16);
                if (digit < 0) {
                    throw new IOException("Malformed GeoJSON unicode escape");
                }
                value = value * 16 + digit;
            }
            return value;
        }
    }
}
