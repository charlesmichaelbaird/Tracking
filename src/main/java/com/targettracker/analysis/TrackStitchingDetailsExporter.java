package com.targettracker.analysis;

import com.targettracker.recording.RecordedScenario;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Exports the standalone Track Stitching Analysis pop-out values. */
public final class TrackStitchingDetailsExporter {
    private static final int STATE_SIZE = 9;
    private static final int POSITION_SIZE = 3;
    private static final DateTimeFormatter FOLDER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public Path export(
            RecordedScenario scenario,
            List<TrackStitchingAnalyzer.EventResult> events,
            TrackStitchingAnalyzer.Configuration configuration,
            Path parentDirectory) throws IOException {
        if (scenario == null || events == null || configuration == null) {
            throw new IllegalArgumentException(
                    "Scenario, events, and configuration are required");
        }
        if (parentDirectory == null) {
            throw new IllegalArgumentException("Output parent directory is required");
        }
        Files.createDirectories(parentDirectory);
        String timestamp = LocalDateTime.now().format(FOLDER_TIME);
        Path runDirectory = uniqueRunDirectory(parentDirectory,
                safeFileName(scenario.scenarioName())
                        + "_track_stitching_values_" + timestamp);
        Files.createDirectories(runDirectory);

        writeReadme(runDirectory, scenario, timestamp);
        writeConfiguration(
                runDirectory.resolve("configuration.csv"),
                scenario,
                configuration,
                timestamp);
        writeEventTimes(runDirectory.resolve("event_times.csv"), events);
        writeSegments(runDirectory.resolve("segments.csv"), events);
        writeTrackBankValues(runDirectory.resolve("track_bank_values.csv"), events);
        writePairBankValues(runDirectory.resolve("pair_bank_values.csv"), events);
        return runDirectory;
    }

    private static Path uniqueRunDirectory(Path parentDirectory, String baseName) {
        Path candidate = parentDirectory.resolve(baseName);
        int suffix = 2;
        while (Files.exists(candidate)) {
            candidate = parentDirectory.resolve(baseName + "_" + suffix++);
        }
        return candidate;
    }

    private static void writeConfiguration(
            Path path,
            RecordedScenario scenario,
            TrackStitchingAnalyzer.Configuration configuration,
            String timestamp) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            writer.write("name,value\n");
            writeCsvRow(writer, "scenario_name", scenario.scenarioName());
            writeCsvRow(writer, "export_timestamp", timestamp);
            writeCsvRow(writer, "source_folder", scenario.folder().toString());
            writeCsvRow(writer, "duration_seconds", scenario.durationSeconds());
            writeCsvRow(writer, "coasted_minimum_seconds",
                    configuration.coastedMinimumSeconds());
            writeCsvRow(writer, "coasted_maximum_seconds",
                    configuration.coastedMaximumSeconds());
            writeCsvRow(writer, "new_minimum_seconds", configuration.newMinimumSeconds());
            writeCsvRow(writer, "new_maximum_seconds", configuration.newMaximumSeconds());
            writeCsvRow(writer, "allow_dead_tracks", configuration.allowDeadTracks());
            writeCsvRow(writer, "time_bank_resolution_seconds",
                    configuration.resolutionSeconds());
            writeCsvRow(writer, "physics_aware_alpha", configuration.physicsAwareAlpha());
            writeCsvRow(writer, "physics_aware_covariance_scale",
                    configuration.physicsAwareInnovationCovarianceScale());
            writeCsvRow(writer, "physics_aware_p_floor_std_meters",
                    configuration.physicsAwarePositionFloorStdMeters());
        }
    }

    private static void writeEventTimes(
            Path path,
            List<TrackStitchingAnalyzer.EventResult> events) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            writer.write(String.join(",",
                    "event_index",
                    "scenario_time_seconds",
                    "all_segment_count",
                    "old_segment_count",
                    "new_segment_count",
                    "pair_count"));
            writer.newLine();
            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                TrackStitchingAnalyzer.EventResult event = events.get(eventIndex);
                writeCsvRow(
                        writer,
                        eventIndex,
                        event.timeSeconds(),
                        event.allSegments().size(),
                        event.oldSegments().size(),
                        event.newSegments().size(),
                        event.pairs().size());
            }
        }
    }

    private static void writeSegments(
            Path path,
            List<TrackStitchingAnalyzer.EventResult> events) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            writer.write(String.join(",",
                    "event_index",
                    "scenario_time_seconds",
                    "track_id",
                    "formation_time_seconds",
                    "last_update_time_seconds",
                    "last_observed_time_seconds",
                    "live_at_event",
                    "dead_at_event",
                    "history_count"));
            writer.newLine();
            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                TrackStitchingAnalyzer.EventResult event = events.get(eventIndex);
                for (TrackStitchingAnalyzer.Segment segment : event.allSegments()) {
                    writeCsvRow(
                            writer,
                            eventIndex,
                            event.timeSeconds(),
                            segment.trackId(),
                            segment.formationTimeSeconds(),
                            segment.lastUpdateTimeSeconds(),
                            segment.lastObservedTimeSeconds(),
                            segment.liveAtEvent(),
                            segment.deadAtEvent(),
                            segment.history().size());
                }
            }
        }
    }

    private static void writeTrackBankValues(
            Path path,
            List<TrackStitchingAnalyzer.EventResult> events) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            List<String> header = new ArrayList<>(List.of(
                    "event_index",
                    "scenario_time_seconds",
                    "old_track_id",
                    "new_track_id",
                    "pair_label",
                    "track_id",
                    "role",
                    "bank_time_seconds"));
            addVectorHeader(header, "state", STATE_SIZE);
            addMatrixHeader(header, "covariance", STATE_SIZE);
            writer.write(String.join(",", header));
            writer.newLine();

            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                TrackStitchingAnalyzer.EventResult event = events.get(eventIndex);
                for (TrackStitchingAnalyzer.PairDiagnostics diagnostics
                        : event.diagnostics()) {
                    for (TrackStitchingAnalyzer.BankEvaluation evaluation
                            : diagnostics.bankEvaluations()) {
                        writeTrackBankRow(
                                writer,
                                eventIndex,
                                event.timeSeconds(),
                                evaluation.oldTrackId(),
                                evaluation.newTrackId(),
                                pairLabel(evaluation.oldTrackId(), evaluation.newTrackId()),
                                evaluation.oldTrackId(),
                                "old_coasted",
                                evaluation.timeSeconds(),
                                evaluation.oldState(),
                                evaluation.oldCovariance());
                        writeTrackBankRow(
                                writer,
                                eventIndex,
                                event.timeSeconds(),
                                evaluation.oldTrackId(),
                                evaluation.newTrackId(),
                                pairLabel(evaluation.oldTrackId(), evaluation.newTrackId()),
                                evaluation.newTrackId(),
                                "new_retrodicted",
                                evaluation.timeSeconds(),
                                evaluation.newState(),
                                evaluation.newCovariance());
                    }
                }
            }
        }
    }

    private static void writeTrackBankRow(
            BufferedWriter writer,
            int eventIndex,
            double scenarioTimeSeconds,
            String oldTrackId,
            String newTrackId,
            String pairLabel,
            String trackId,
            String role,
            double bankTimeSeconds,
            double[] state,
            double[][] covariance) throws IOException {
        List<Object> row = new ArrayList<>();
        row.add(eventIndex);
        row.add(scenarioTimeSeconds);
        row.add(oldTrackId);
        row.add(newTrackId);
        row.add(pairLabel);
        row.add(trackId);
        row.add(role);
        row.add(bankTimeSeconds);
        addVector(row, state);
        addMatrix(row, covariance);
        writeCsvRow(writer, row.toArray());
    }

    private static void writePairBankValues(
            Path path,
            List<TrackStitchingAnalyzer.EventResult> events) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            List<String> header = new ArrayList<>(List.of(
                    "event_index",
                    "scenario_time_seconds",
                    "old_track_id",
                    "new_track_id",
                    "pair_label",
                    "bank_time_seconds"));
            addVectorHeader(header, "old_state", STATE_SIZE);
            addMatrixHeader(header, "old_covariance", STATE_SIZE);
            addVectorHeader(header, "new_state", STATE_SIZE);
            addMatrixHeader(header, "new_covariance", STATE_SIZE);
            addVectorHeader(header, "position_innovation", POSITION_SIZE);
            addMatrixHeader(header, "position_innovation_covariance", POSITION_SIZE);
            addMatrixHeader(header, "physics_aware_innovation_covariance", POSITION_SIZE);
            header.addAll(List.of(
                    "mahalanobis_distance",
                    "squared_mahalanobis_distance",
                    "log_det_innovation_covariance",
                    "negative_log_likelihood",
                    "physics_aware_negative_log_likelihood",
                    "physics_aware_gap_seconds",
                    "physics_aware_bridge_geometry_log_det",
                    "physics_aware_volume",
                    "physics_aware_log_volume",
                    "physics_aware_opportunity_cost",
                    "physics_aware_cost",
                    "bridge_endpoint_rms_acceleration_mps2",
                    "bridge_endpoint_peak_acceleration_mps2",
                    "bridge_endpoint_admissible",
                    "bridge_admissibility_quadratic",
                    "bridge_admissible_volume_m3",
                    "bridge_admissible_volume_km3",
                    "bridge_bank_admissible",
                    "bridge_different_target_nll",
                    "bridge_nllr",
                    "user_nllr_volume_km3",
                    "user_volume_nllr",
                    "innovation_volume_km3",
                    "static_lambda_ex",
                    "static_negative_log_likelihood_ratio",
                    "learned_birth_density_per_km3",
                    "learned_expected_births",
                    "learned_exposure_scan_km3",
                    "learned_reliability",
                    "learned_query_sigma_meters",
                    "learned_negative_log_likelihood_ratio"));
            writer.write(String.join(",", header));
            writer.newLine();

            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                TrackStitchingAnalyzer.EventResult event = events.get(eventIndex);
                for (TrackStitchingAnalyzer.PairDiagnostics diagnostics
                        : event.diagnostics()) {
                    for (TrackStitchingAnalyzer.BankEvaluation evaluation
                            : diagnostics.bankEvaluations()) {
                        List<Object> row = new ArrayList<>();
                        row.add(eventIndex);
                        row.add(event.timeSeconds());
                        row.add(evaluation.oldTrackId());
                        row.add(evaluation.newTrackId());
                        row.add(pairLabel(evaluation.oldTrackId(), evaluation.newTrackId()));
                        row.add(evaluation.timeSeconds());
                        addVector(row, evaluation.oldState());
                        addMatrix(row, evaluation.oldCovariance());
                        addVector(row, evaluation.newState());
                        addMatrix(row, evaluation.newCovariance());
                        addVector(row, evaluation.innovation());
                        addMatrix(row, evaluation.innovationCovariance());
                        addMatrix(row, evaluation.physicsAwareInnovationCovariance());
                        row.add(evaluation.mahalanobisDistance());
                        row.add(evaluation.innovationQuadratic());
                        row.add(evaluation.logDeterminant());
                        row.add(evaluation.negativeLogLikelihood());
                        row.add(evaluation.physicsAwareNegativeLogLikelihood());
                        row.add(evaluation.physicsAwareGapSeconds());
                        row.add(evaluation.physicsAwareBridgeGeometryLogDeterminant());
                        row.add(evaluation.physicsAwareVolume());
                        row.add(evaluation.physicsAwareLogVolume());
                        row.add(evaluation.physicsAwareOpportunityCost());
                        row.add(evaluation.physicsAwareCost());
                        row.add(evaluation.bridgeEndpointRmsAccelerationMetersPerSecondSquared());
                        row.add(evaluation.bridgeEndpointPeakAccelerationMetersPerSecondSquared());
                        row.add(evaluation.bridgeEndpointAdmissible());
                        row.add(evaluation.bridgeAdmissibilityQuadratic());
                        row.add(evaluation.bridgeAdmissibleVolumeCubicMeters());
                        row.add(evaluation.bridgeAdmissibleVolumeCubicKilometers());
                        row.add(evaluation.bridgeAdmissible());
                        row.add(evaluation.bridgeDifferentTargetNegativeLogLikelihood());
                        row.add(evaluation.bridgeNegativeLogLikelihoodRatio());
                        row.add(evaluation.userNllrVolumeCubicKilometers());
                        row.add(evaluation.userVolumeNegativeLogLikelihoodRatio());
                        row.add(evaluation.innovationVolumeCubicKilometers());
                        row.add(evaluation.staticLambdaEx());
                        row.add(evaluation.staticNegativeLogLikelihoodRatio());
                        row.add(evaluation.learnedBirthDensityPerCubicKilometer());
                        row.add(evaluation.learnedExpectedBirths());
                        row.add(evaluation.learnedExposureScanCubicKilometers());
                        row.add(evaluation.learnedReliability());
                        row.add(evaluation.learnedQuerySigmaMeters());
                        row.add(evaluation.learnedNegativeLogLikelihoodRatio());
                        writeCsvRow(writer, row.toArray());
                    }
                }
            }
        }
    }

    private static void writeReadme(
            Path runDirectory,
            RecordedScenario scenario,
            String timestamp) throws IOException {
        try (BufferedWriter writer = newWriter(runDirectory.resolve("README.md"))) {
            writer.write("Track Stitching Analysis pop-out value export\n");
            writer.write("Scenario: " + scenario.scenarioName() + "\n");
            writer.write("Export timestamp: " + timestamp + "\n\n");
            writer.write("Files:\n");
            writer.write("- configuration.csv: analysis configuration used for this export.\n");
            writer.write("- event_times.csv: candidate event-time tabs shown in the world view.\n");
            writer.write("- segments.csv: track-segment timing metadata for each event tab.\n");
            writer.write("- track_bank_values.csv: per track/pair/role bank-time 9x1 states "
                    + "and 9x9 covariances shown in the pop-out track tabs.\n");
            writer.write("- pair_bank_values.csv: per old/new pair bank-time states, "
                    + "innovations, covariances, squared Mahalanobis distance, NLL, "
                    + "Gramian log determinant, opportunity term, and full Physics-Aware cost.\n\n");
            writer.write("MATLAB:\n");
            writer.write("Run scripts/plot_track_stitching_detail_export.m from the repo, "
                    + "then select this folder when prompted. The script creates local "
                    + "tables named eventTimes, segments, trackBank, pairBank, and config.\n");
        }
    }

    private static BufferedWriter newWriter(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    private static void addVectorHeader(List<String> header, String prefix, int size) {
        for (int index = 0; index < size; index++) {
            header.add(prefix + "_" + index);
        }
    }

    private static void addMatrixHeader(List<String> header, String prefix, int size) {
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                header.add(prefix + "_" + row + "_" + column);
            }
        }
    }

    private static void addVector(List<Object> row, double[] values) {
        for (double value : values) {
            row.add(value);
        }
    }

    private static void addMatrix(List<Object> row, double[][] values) {
        for (double[] matrixRow : values) {
            for (double value : matrixRow) {
                row.add(value);
            }
        }
    }

    private static void writeCsvRow(BufferedWriter writer, Object... values)
            throws IOException {
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                writer.write(',');
            }
            writer.write(csv(values[index]));
        }
        writer.newLine();
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number number) {
            double doubleValue = number.doubleValue();
            if (Double.isNaN(doubleValue)) {
                return "NaN";
            }
            if (Double.isInfinite(doubleValue)) {
                return doubleValue > 0.0 ? "Inf" : "-Inf";
            }
            return String.format(Locale.ROOT, "%.17g", doubleValue);
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        String text = value.toString();
        boolean quote = text.contains(",")
                || text.contains("\"")
                || text.contains("\n")
                || text.contains("\r");
        String escaped = text.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    private static String pairLabel(String oldTrackId, String newTrackId) {
        return oldTrackId + " -> " + newTrackId;
    }

    private static String safeFileName(String value) {
        String cleaned = value == null || value.isBlank() ? "scenario" : value.trim();
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]+", "_");
        return cleaned.isBlank() ? "scenario" : cleaned;
    }
}
