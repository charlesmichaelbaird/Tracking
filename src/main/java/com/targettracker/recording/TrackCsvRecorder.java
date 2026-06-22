package com.targettracker.recording;

import com.targettracker.tracking.AssociatedMeasurement;
import com.targettracker.tracking.TrackRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Writes separate MATLAB-friendly truth, track, and measurement datasets. */
public final class TrackCsvRecorder implements AutoCloseable {
    public static final String GROUND_TRUTH_DIRECTORY = "ground_truth_data";
    public static final String TRACK_DIRECTORY = "track_data";
    public static final String MEASUREMENT_DIRECTORY = "measurement_data";
    public static final String MEASUREMENT_FILE = "measurements.csv";

    private static final String SENSOR_ID = "GOD-SENSOR-001";
    private static final int STATE_SIZE = 9;
    private static final int MEASUREMENT_SIZE = 6;
    private static final DateTimeFormatter RUN_FOLDER_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS");
    private static final String[] STATE_COLUMNS = {
            "x_m", "y_m", "z_m",
            "vx_mps", "vy_mps", "vz_mps",
            "ax_mps2", "ay_mps2", "az_mps2"
    };

    private final Clock clock;
    private final Map<String, BufferedWriter> trackWriters = new LinkedHashMap<>();
    private final Map<String, BufferedWriter> groundTruthWriters = new LinkedHashMap<>();
    private Path outputParent = Path.of("recordings").toAbsolutePath().normalize();
    private Path runDirectory;
    private Path trackDirectory;
    private Path groundTruthDirectory;
    private Path measurementDirectory;
    private BufferedWriter measurementWriter;
    private boolean armed;
    private boolean active;
    private String lastError;

    public TrackCsvRecorder() {
        this(Clock.systemDefaultZone());
    }

    TrackCsvRecorder(Clock clock) {
        this.clock = clock;
    }

    public Path outputParent() {
        return outputParent;
    }

    public void setOutputParent(Path outputParent) {
        if (active) {
            throw new IllegalStateException("The recording folder cannot change during a run");
        }
        if (outputParent == null) {
            throw new IllegalArgumentException("An output parent folder is required");
        }
        this.outputParent = outputParent.toAbsolutePath().normalize();
        lastError = null;
    }

    public boolean isArmed() {
        return armed;
    }

    public void setArmed(boolean armed) {
        this.armed = armed;
        if (!armed) {
            finishRun();
        }
    }

    public boolean isActive() {
        return active;
    }

    public Path runDirectory() {
        return runDirectory;
    }

    public String lastError() {
        return lastError;
    }

    public boolean beginRun() {
        return beginRun("scenario", 0.0);
    }

    /** Starts a named run with three dedicated data subdirectories. */
    public boolean beginRun(String scenarioName, double durationSeconds) {
        finishRun();
        if (!armed) {
            return true;
        }
        if (!Double.isFinite(durationSeconds) || durationSeconds < 0.0) {
            throw new IllegalArgumentException("Scenario duration must be finite and non-negative");
        }
        try {
            Files.createDirectories(outputParent);
            runDirectory = uniqueRunDirectory(scenarioName);
            Files.createDirectory(runDirectory);
            groundTruthDirectory = Files.createDirectory(
                    runDirectory.resolve(GROUND_TRUTH_DIRECTORY));
            trackDirectory = Files.createDirectory(runDirectory.resolve(TRACK_DIRECTORY));
            measurementDirectory = Files.createDirectory(
                    runDirectory.resolve(MEASUREMENT_DIRECTORY));
            writeMetadata(scenarioName, durationSeconds);
            writeReadme(scenarioName, durationSeconds);
            active = true;
            lastError = null;
            return true;
        } catch (IOException exception) {
            runDirectory = null;
            active = false;
            lastError = "Could not start recording: " + exception.getMessage();
            return false;
        }
    }

    /** Writes predicted/updated track rows and any associated measurement rows. */
    public void recordSamples(List<TrackRecord> records) {
        if (!active || records.isEmpty()) {
            return;
        }
        try {
            for (TrackRecord record : records) {
                writeTrackRecord(record);
                if (record.measurement() != null) {
                    writeMeasurement(record.trackId(), record.timeSeconds(), record.measurement());
                }
            }
            flushWriters();
        } catch (IOException exception) {
            recordingFailed(exception);
        }
    }

    /** Writes dense target truth independently of tracker output. */
    public void recordGroundTruth(List<GroundTruthRecord> records) {
        if (!active || records.isEmpty()) {
            return;
        }
        try {
            for (GroundTruthRecord record : records) {
                writeGroundTruthRecord(record);
            }
        } catch (IOException exception) {
            recordingFailed(exception);
        }
    }

    public void finishRun() {
        IOException closeFailure = closeAll(trackWriters);
        IOException truthFailure = closeAll(groundTruthWriters);
        if (truthFailure != null) {
            closeFailure = truthFailure;
        }
        if (measurementWriter != null) {
            try {
                measurementWriter.close();
            } catch (IOException exception) {
                closeFailure = exception;
            }
            measurementWriter = null;
        }
        active = false;
        if (closeFailure != null) {
            lastError = "Could not close recording files: " + closeFailure.getMessage();
        }
    }

    @Override
    public void close() {
        finishRun();
    }

    private void writeTrackRecord(TrackRecord record) throws IOException {
        BufferedWriter writer = trackWriters.get(record.trackId());
        if (writer == null) {
            Path file = trackDirectory.resolve(safeFileName(record.trackId()) + ".csv");
            writer = newWriter(file);
            writer.write(trackHeader());
            writer.newLine();
            trackWriters.put(record.trackId(), writer);
        }
        writer.write(csv(record.trackId()));
        writer.write(',');
        writer.write(Double.toString(record.timeSeconds()));
        writer.write(',');
        writer.write(Boolean.toString(record.updated()));
        writeVector(writer, record.state());
        double[][] covariance = record.covariance();
        for (int row = 0; row < STATE_SIZE; row++) {
            writeVector(writer, covariance[row]);
        }
        writer.newLine();
    }

    private void writeGroundTruthRecord(GroundTruthRecord record) throws IOException {
        BufferedWriter writer = groundTruthWriters.get(record.targetId());
        if (writer == null) {
            Path file = groundTruthDirectory.resolve(safeFileName(record.targetId()) + ".csv");
            writer = newWriter(file);
            writer.write("target_id,time_s," + String.join(",", STATE_COLUMNS));
            writer.newLine();
            groundTruthWriters.put(record.targetId(), writer);
        }
        writer.write(csv(record.targetId()));
        writer.write(',');
        writer.write(Double.toString(record.timeSeconds()));
        writeVector(writer, record.state());
        writer.newLine();
    }

    private void writeMeasurement(
            String trackId,
            double timeSeconds,
            AssociatedMeasurement measurement)
            throws IOException {
        if (measurementWriter == null) {
            measurementWriter = newWriter(measurementDirectory.resolve(MEASUREMENT_FILE));
            measurementWriter.write(measurementHeader());
            measurementWriter.newLine();
        }
        double[] mean = measurement.mean();
        double[][] covariance = measurement.covariance();
        double positionUncertainty = axisUncertainty(covariance, 0);
        double velocityUncertainty = axisUncertainty(covariance, 3);
        measurementWriter.write(SENSOR_ID);
        measurementWriter.write(',');
        measurementWriter.write(csv(measurement.targetId()));
        measurementWriter.write(',');
        measurementWriter.write(csv(trackId));
        measurementWriter.write(',');
        measurementWriter.write(Double.toString(timeSeconds));
        writeVector(measurementWriter, mean);
        for (int row = 0; row < MEASUREMENT_SIZE; row++) {
            writeVector(measurementWriter, covariance[row]);
        }
        measurementWriter.write(',');
        measurementWriter.write(Double.toString(positionUncertainty));
        measurementWriter.write(',');
        measurementWriter.write(Double.toString(velocityUncertainty));
        measurementWriter.newLine();
    }

    private void flushWriters() throws IOException {
        for (BufferedWriter writer : trackWriters.values()) {
            writer.flush();
        }
        if (measurementWriter != null) {
            measurementWriter.flush();
        }
    }

    private void recordingFailed(IOException exception) {
        lastError = "Recording stopped: " + exception.getMessage();
        finishRun();
    }

    private static IOException closeAll(Map<String, BufferedWriter> writers) {
        IOException failure = null;
        for (BufferedWriter writer : writers.values()) {
            try {
                writer.close();
            } catch (IOException exception) {
                failure = exception;
            }
        }
        writers.clear();
        return failure;
    }

    private Path uniqueRunDirectory(String scenarioName) {
        String baseName = safeScenarioName(scenarioName) + "_"
                + LocalDateTime.now(clock).format(RUN_FOLDER_FORMAT);
        Path candidate = outputParent.resolve(baseName);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = outputParent.resolve(baseName + "_" + suffix);
            suffix++;
        }
        return candidate;
    }

    private void writeMetadata(String scenarioName, double durationSeconds) throws IOException {
        String text = "format_version=4\n"
                + "scenario_name=" + scenarioName.replace("\n", " ").replace("\r", " ") + "\n"
                + "duration_seconds=" + durationSeconds + "\n";
        Files.writeString(
                runDirectory.resolve("scenario_metadata.properties"), text, StandardCharsets.UTF_8);
    }

    private void writeReadme(String scenarioName, double durationSeconds) throws IOException {
        String text = """
                ECEF Target Tracker recording

                Scenario: %s
                Duration: %s seconds

                ground_truth_data/TGT-*.csv
                  Dense 9D ECEF target truth: [x y z vx vy vz ax ay az].

                track_data/TRK-*.csv
                  Integer-second live-track rows plus fractional-time update rows.
                  Track covariance p_00 through p_88 is row-major. updated indicates
                  whether the row is an exact measurement update or a coast prediction.

                measurement_data/measurements.csv
                  Sensor ID, source target ID, associated track ID, time, 6D ECEF
                  mean, complete 6x6 covariance r_00 through r_55, and
                  position/velocity uncertainty.

                MATLAB examples:
                  truth = readtable('ground_truth_data/TGT-001.csv');
                  tracks = readtable('track_data/TRK-001.csv');
                  measurements = readtable('measurement_data/measurements.csv');
                  p = tracks{1, startsWith(tracks.Properties.VariableNames, 'p_')};
                  P = reshape(p, [9, 9]).';
                  r = measurements{1, startsWith(measurements.Properties.VariableNames, 'r_')};
                  R = reshape(r, [6, 6]).';
                """.formatted(scenarioName, Double.toString(durationSeconds));
        Files.writeString(runDirectory.resolve("README.txt"), text, StandardCharsets.UTF_8);
    }

    private static BufferedWriter newWriter(Path file) throws IOException {
        return Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static void writeVector(BufferedWriter writer, double[] values) throws IOException {
        for (double value : values) {
            writer.write(',');
            writer.write(Double.toString(value));
        }
    }

    private static String trackHeader() {
        StringBuilder header = new StringBuilder("track_id,time_s,updated");
        for (String stateColumn : STATE_COLUMNS) {
            header.append(',').append(stateColumn);
        }
        for (int row = 0; row < STATE_SIZE; row++) {
            for (int column = 0; column < STATE_SIZE; column++) {
                header.append(",p_").append(row).append(column);
            }
        }
        return header.toString();
    }

    private static String measurementHeader() {
        StringBuilder header = new StringBuilder(
                "sensor_id,target_id,associated_track_id,time_s,meas_x_m,meas_y_m,meas_z_m,"
                        + "meas_vx_mps,meas_vy_mps,meas_vz_mps");
        for (int row = 0; row < MEASUREMENT_SIZE; row++) {
            for (int column = 0; column < MEASUREMENT_SIZE; column++) {
                header.append(",r_").append(row).append(column);
            }
        }
        return header.append(",position_uncertainty_m,velocity_uncertainty_mps").toString();
    }

    private static double axisUncertainty(double[][] covariance, int offset) {
        return Math.sqrt(Math.max(0.0,
                (covariance[offset][offset]
                        + covariance[offset + 1][offset + 1]
                        + covariance[offset + 2][offset + 2]) / 3.0));
    }

    private static String safeScenarioName(String scenarioName) {
        String safe = scenarioName == null ? "" : scenarioName
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return safe.isBlank() ? "scenario" : safe;
    }

    private static String safeFileName(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
