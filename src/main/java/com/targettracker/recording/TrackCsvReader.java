package com.targettracker.recording;

import com.targettracker.tracking.AssociatedMeasurement;
import com.targettracker.tracking.TrackRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Reads current and legacy MATLAB-friendly track CSV folders. */
public final class TrackCsvReader {
    private static final int STATE_SIZE = 9;
    private static final int MEASUREMENT_SIZE = 6;
    private static final String METADATA_FILE = "scenario_metadata.properties";

    private TrackCsvReader() {
    }

    public static RecordedScenario read(Path folder) throws IOException {
        if (folder == null || !Files.isDirectory(folder)) {
            throw new IOException("Choose an existing scenario folder");
        }
        Path trackFolder = Files.isDirectory(folder.resolve(TrackCsvRecorder.TRACK_DIRECTORY))
                ? folder.resolve(TrackCsvRecorder.TRACK_DIRECTORY)
                : folder;
        List<Path> csvFiles = csvFiles(trackFolder);
        List<TrackRecord> records = new ArrayList<>();
        for (Path csvFile : csvFiles) {
            readTrackFile(csvFile, records);
        }
        records.sort(Comparator.comparingDouble(TrackRecord::timeSeconds)
                .thenComparing(TrackRecord::trackId));
        List<GroundTruthRecord> groundTruth = readGroundTruth(folder);
        List<RecordedMeasurement> measurements = readMeasurements(folder);
        if (measurements.isEmpty()) {
            measurements = legacyMeasurements(records);
        }
        if (records.isEmpty() && groundTruth.isEmpty() && measurements.isEmpty()) {
            throw new IOException("The selected folder contains no replay data");
        }

        Metadata metadata = readMetadata(folder);
        double maximumRecordTime = records.stream()
                .mapToDouble(TrackRecord::timeSeconds)
                .max()
                .orElse(0.0);
        double maximumTruthTime = groundTruth.stream()
                .mapToDouble(GroundTruthRecord::timeSeconds)
                .max()
                .orElse(0.0);
        double maximumMeasurementTime = measurements.stream()
                .mapToDouble(RecordedMeasurement::timeSeconds)
                .max()
                .orElse(0.0);
        double duration = Math.max(metadata.durationSeconds(),
                Math.max(maximumRecordTime, Math.max(maximumTruthTime, maximumMeasurementTime)));
        String name = metadata.scenarioName().isBlank()
                ? folder.getFileName().toString()
                : metadata.scenarioName();
        return new RecordedScenario(
                folder, name, duration, records, groundTruth, measurements);
    }

    private static List<Path> csvFiles(Path folder) throws IOException {
        try (Stream<Path> paths = Files.list(folder)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".csv"))
                    .sorted()
                    .toList();
        }
    }

    private static List<GroundTruthRecord> readGroundTruth(Path runFolder) throws IOException {
        Path folder = runFolder.resolve(TrackCsvRecorder.GROUND_TRUTH_DIRECTORY);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<GroundTruthRecord> records = new ArrayList<>();
        for (Path file : csvFiles(folder)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                continue;
            }
            Map<String, Integer> columns = columns(parseCsvLine(lines.get(0)));
            requireColumn(columns, "target_id", file);
            requireColumn(columns, "time_s", file);
            String[] stateColumns = stateColumns();
            for (String column : stateColumns) {
                requireColumn(columns, column, file);
            }
            for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
                if (lines.get(lineNumber).isBlank()) {
                    continue;
                }
                List<String> values = parseCsvLine(lines.get(lineNumber));
                double[] state = new double[STATE_SIZE];
                for (int index = 0; index < STATE_SIZE; index++) {
                    state[index] = parseDouble(values, columns.get(stateColumns[index]));
                }
                records.add(new GroundTruthRecord(
                        value(values, columns.get("target_id")),
                        parseDouble(values, columns.get("time_s")),
                        state));
            }
        }
        records.sort(Comparator.comparingDouble(GroundTruthRecord::timeSeconds)
                .thenComparing(GroundTruthRecord::targetId));
        return List.copyOf(records);
    }

    private static List<RecordedMeasurement> readMeasurements(Path runFolder) throws IOException {
        Path file = runFolder.resolve(TrackCsvRecorder.MEASUREMENT_DIRECTORY)
                .resolve(TrackCsvRecorder.MEASUREMENT_FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> columns = columns(parseCsvLine(lines.get(0)));
        requireColumn(columns, "sensor_id", file);
        requireColumn(columns, "time_s", file);
        String[] meanColumns = measurementMeanColumns();
        for (String column : meanColumns) {
            requireColumn(columns, column, file);
        }
        for (int row = 0; row < MEASUREMENT_SIZE; row++) {
            for (int column = 0; column < MEASUREMENT_SIZE; column++) {
                requireColumn(columns, "r_" + row + column, file);
            }
        }
        List<RecordedMeasurement> measurements = new ArrayList<>();
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            if (lines.get(lineNumber).isBlank()) {
                continue;
            }
            List<String> values = parseCsvLine(lines.get(lineNumber));
            double[] mean = new double[MEASUREMENT_SIZE];
            for (int index = 0; index < MEASUREMENT_SIZE; index++) {
                mean[index] = parseDouble(values, columns.get(meanColumns[index]));
            }
            double[][] covariance = new double[MEASUREMENT_SIZE][MEASUREMENT_SIZE];
            for (int row = 0; row < MEASUREMENT_SIZE; row++) {
                for (int column = 0; column < MEASUREMENT_SIZE; column++) {
                    covariance[row][column] = parseDouble(
                            values, columns.get("r_" + row + column));
                }
            }
            double positionUncertainty = optionalDouble(
                    values, columns.get("position_uncertainty_m"), axisUncertainty(covariance, 0));
            double velocityUncertainty = optionalDouble(
                    values, columns.get("velocity_uncertainty_mps"), axisUncertainty(covariance, 3));
            Integer targetColumn = columns.get("target_id");
            Integer trackColumn = columns.get("associated_track_id");
            measurements.add(new RecordedMeasurement(
                    value(values, columns.get("sensor_id")),
                    targetColumn == null ? "" : value(values, targetColumn),
                    trackColumn == null ? "" : value(values, trackColumn),
                    parseDouble(values, columns.get("time_s")),
                    mean,
                    covariance,
                    positionUncertainty,
                    velocityUncertainty));
        }
        measurements.sort(Comparator.comparingDouble(RecordedMeasurement::timeSeconds));
        return List.copyOf(measurements);
    }

    private static List<RecordedMeasurement> legacyMeasurements(List<TrackRecord> records) {
        List<RecordedMeasurement> measurements = new ArrayList<>();
        for (TrackRecord record : records) {
            AssociatedMeasurement measurement = record.measurement();
            if (measurement == null) {
                continue;
            }
            double[][] covariance = measurement.covariance();
            measurements.add(new RecordedMeasurement(
                    "GOD-SENSOR-001",
                    measurement.targetId(),
                    record.trackId(),
                    record.timeSeconds(),
                    measurement.mean(),
                    covariance,
                    axisUncertainty(covariance, 0),
                    axisUncertainty(covariance, 3)));
        }
        return List.copyOf(measurements);
    }

    private static void readTrackFile(Path file, List<TrackRecord> records) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return;
        }
        List<String> header = parseCsvLine(lines.get(0));
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            columns.put(header.get(index), index);
        }
        requireColumn(columns, "track_id", file);
        requireColumn(columns, "time_s", file);
        requireColumn(columns, "updated", file);
        String[] stateColumns = stateColumns();
        for (String column : stateColumns) {
            requireColumn(columns, column, file);
        }
        for (int row = 0; row < STATE_SIZE; row++) {
            for (int column = 0; column < STATE_SIZE; column++) {
                requireColumn(columns, "p_" + row + column, file);
            }
        }

        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            if (lines.get(lineNumber).isBlank()) {
                continue;
            }
            try {
                List<String> values = parseCsvLine(lines.get(lineNumber));
                String trackId = value(values, columns.get("track_id"));
                double time = parseDouble(values, columns.get("time_s"));
                boolean updated = Boolean.parseBoolean(value(values, columns.get("updated")));
                double[] state = new double[STATE_SIZE];
                for (int index = 0; index < STATE_SIZE; index++) {
                    state[index] = parseDouble(values, columns.get(stateColumns[index]));
                }
                double[][] covariance = new double[STATE_SIZE][STATE_SIZE];
                for (int row = 0; row < STATE_SIZE; row++) {
                    for (int column = 0; column < STATE_SIZE; column++) {
                        covariance[row][column] = parseDouble(
                                values, columns.get("p_" + row + column));
                    }
                }
                AssociatedMeasurement measurement = readMeasurement(values, columns, updated);
                records.add(new TrackRecord(
                        trackId, time, state, covariance, updated, measurement));
            } catch (IllegalArgumentException exception) {
                throw new IOException(
                        "Invalid track data in %s at row %d: %s".formatted(
                                file.getFileName(), lineNumber + 1, exception.getMessage()),
                        exception);
            }
        }
    }

    private static AssociatedMeasurement readMeasurement(
            List<String> values,
            Map<String, Integer> columns,
            boolean updated) {
        Integer firstMeanColumn = columns.get("meas_x_m");
        if (!updated || firstMeanColumn == null || value(values, firstMeanColumn).isBlank()) {
            return null;
        }
        String[] meanColumns = {
                "meas_x_m", "meas_y_m", "meas_z_m",
                "meas_vx_mps", "meas_vy_mps", "meas_vz_mps"
        };
        double[] mean = new double[MEASUREMENT_SIZE];
        for (int index = 0; index < MEASUREMENT_SIZE; index++) {
            Integer column = columns.get(meanColumns[index]);
            if (column == null) {
                throw new IllegalArgumentException("Incomplete measurement mean columns");
            }
            mean[index] = parseDouble(values, column);
        }
        double[][] covariance = new double[MEASUREMENT_SIZE][MEASUREMENT_SIZE];
        for (int row = 0; row < MEASUREMENT_SIZE; row++) {
            for (int column = 0; column < MEASUREMENT_SIZE; column++) {
                Integer covarianceColumn = columns.get("r_" + row + column);
                if (covarianceColumn == null) {
                    throw new IllegalArgumentException("Incomplete measurement covariance columns");
                }
                covariance[row][column] = parseDouble(values, covarianceColumn);
            }
        }
        Integer targetColumn = columns.get("measurement_target_id");
        String targetId = targetColumn == null ? "" : value(values, targetColumn);
        return new AssociatedMeasurement(targetId, mean, covariance);
    }

    private static Metadata readMetadata(Path folder) throws IOException {
        Path metadataFile = folder.resolve(METADATA_FILE);
        if (!Files.isRegularFile(metadataFile)) {
            return new Metadata("", 0.0);
        }
        String name = "";
        double duration = 0.0;
        for (String line : Files.readAllLines(metadataFile, StandardCharsets.UTF_8)) {
            int equals = line.indexOf('=');
            if (equals < 0) {
                continue;
            }
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if ("scenario_name".equals(key)) {
                name = value;
            } else if ("duration_seconds".equals(key)) {
                try {
                    duration = Double.parseDouble(value);
                } catch (NumberFormatException exception) {
                    throw new IOException("Invalid duration in " + METADATA_FILE, exception);
                }
            }
        }
        return new Metadata(name, duration);
    }

    private static void requireColumn(Map<String, Integer> columns, String name, Path file)
            throws IOException {
        if (!columns.containsKey(name)) {
            throw new IOException("Missing column " + name + " in " + file.getFileName());
        }
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int index = 0; index < header.size(); index++) {
            columns.put(header.get(index), index);
        }
        return columns;
    }

    private static String[] stateColumns() {
        return new String[]{
                "x_m", "y_m", "z_m", "vx_mps", "vy_mps", "vz_mps",
                "ax_mps2", "ay_mps2", "az_mps2"
        };
    }

    private static String[] measurementMeanColumns() {
        return new String[]{
                "meas_x_m", "meas_y_m", "meas_z_m",
                "meas_vx_mps", "meas_vy_mps", "meas_vz_mps"
        };
    }

    private static double optionalDouble(
            List<String> values,
            Integer column,
            double fallback) {
        return column == null || value(values, column).isBlank()
                ? fallback
                : parseDouble(values, column);
    }

    private static double axisUncertainty(double[][] covariance, int offset) {
        return Math.sqrt(Math.max(0.0,
                (covariance[offset][offset]
                        + covariance[offset + 1][offset + 1]
                        + covariance[offset + 2][offset + 2]) / 3.0));
    }

    private static double parseDouble(List<String> values, int column) {
        String text = value(values, column);
        double parsed = Double.parseDouble(text);
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("Non-finite numeric value");
        }
        return parsed;
    }

    private static String value(List<String> values, int column) {
        return column >= 0 && column < values.size() ? values.get(column) : "";
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else {
                value.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unclosed quoted CSV value");
        }
        values.add(value.toString());
        return values;
    }

    private record Metadata(String scenarioName, double durationSeconds) {
    }
}
