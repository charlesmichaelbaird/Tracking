package com.targettracker.recording;

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
import java.util.Map;

/** Writes one fixed-schema, MATLAB-friendly CSV per track and scenario run. */
public final class TrackCsvRecorder implements AutoCloseable {
    private static final int STATE_SIZE = 9;
    private static final DateTimeFormatter RUN_FOLDER_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS");
    private static final String[] STATE_COLUMNS = {
            "x_m", "y_m", "z_m",
            "vx_mps", "vy_mps", "vz_mps",
            "ax_mps2", "ay_mps2", "az_mps2"
    };

    private final Clock clock;
    private final Map<String, BufferedWriter> writers = new LinkedHashMap<>();
    private Path outputParent = Path.of("recordings").toAbsolutePath().normalize();
    private Path runDirectory;
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

    /** Starts a fresh timestamped scenario folder when recording is armed. */
    public boolean beginRun() {
        finishRun();
        if (!armed) {
            return true;
        }
        try {
            Files.createDirectories(outputParent);
            runDirectory = uniqueRunDirectory();
            Files.createDirectory(runDirectory);
            writeReadme();
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

    /** Writes one predicted or updated sample for each supplied live track. */
    public void recordSamples(List<TrackRecord> records) {
        if (!active || records.isEmpty()) {
            return;
        }
        try {
            for (TrackRecord record : records) {
                writeRecord(record);
            }
            for (BufferedWriter writer : writers.values()) {
                writer.flush();
            }
        } catch (IOException exception) {
            lastError = "Recording stopped: " + exception.getMessage();
            finishRun();
        }
    }

    public void finishRun() {
        IOException closeFailure = null;
        for (BufferedWriter writer : writers.values()) {
            try {
                writer.close();
            } catch (IOException exception) {
                closeFailure = exception;
            }
        }
        writers.clear();
        active = false;
        if (closeFailure != null) {
            lastError = "Could not close recording files: " + closeFailure.getMessage();
        }
    }

    @Override
    public void close() {
        finishRun();
    }

    private Path uniqueRunDirectory() throws IOException {
        String baseName = "scenario_" + LocalDateTime.now(clock).format(RUN_FOLDER_FORMAT);
        Path candidate = outputParent.resolve(baseName);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = outputParent.resolve(baseName + "_" + suffix);
            suffix++;
        }
        return candidate;
    }

    private void writeReadme() throws IOException {
        String text = """
                ECEF Target Tracker recording

                Each TRK-*.csv file contains one row per integer scenario second while the track is live.
                State order: [x, y, z, vx, vy, vz, ax, ay, az] in SI units and ECEF axes.
                Covariance columns p_00 through p_88 are written in row-major order.
                updated=true means a measurement updated the track at that exact second.
                updated=false means the row is the track prediction/coast at that second.

                MATLAB example:
                  T = readtable('TRK-001.csv');
                  x = T{:, {'x_m','y_m','z_m','vx_mps','vy_mps','vz_mps', ...
                            'ax_mps2','ay_mps2','az_mps2'}};
                  p = T{1, startsWith(T.Properties.VariableNames, 'p_')};
                  P = reshape(p, [9, 9]).';
                """;
        Files.writeString(runDirectory.resolve("README.txt"), text, StandardCharsets.UTF_8);
    }

    private void writeRecord(TrackRecord record) throws IOException {
        BufferedWriter writer = writers.get(record.trackId());
        if (writer == null) {
            Path file = runDirectory.resolve(safeFileName(record.trackId()) + ".csv");
            writer = Files.newBufferedWriter(
                    file,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            writer.write(header());
            writer.newLine();
            writers.put(record.trackId(), writer);
        }

        writer.write(csv(record.trackId()));
        writer.write(',');
        writer.write(Double.toString(record.timeSeconds()));
        writer.write(',');
        writer.write(Boolean.toString(record.updated()));
        double[] state = record.state();
        for (double value : state) {
            writer.write(',');
            writer.write(Double.toString(value));
        }
        double[][] covariance = record.covariance();
        for (int row = 0; row < STATE_SIZE; row++) {
            for (int column = 0; column < STATE_SIZE; column++) {
                writer.write(',');
                writer.write(Double.toString(covariance[row][column]));
            }
        }
        writer.newLine();
    }

    private static String header() {
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

    private static String safeFileName(String trackId) {
        return trackId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0
                && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
