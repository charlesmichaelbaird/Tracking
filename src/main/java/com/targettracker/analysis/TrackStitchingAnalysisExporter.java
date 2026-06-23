package com.targettracker.analysis;

import com.targettracker.recording.RecordedScenario;
import com.targettracker.tracking.TrackRecord;

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

/** Writes a complete Track Stitching Analysis result tree for MATLAB/offline review. */
public final class TrackStitchingAnalysisExporter {
    private static final int STATE_SIZE = 9;
    private static final DateTimeFormatter FOLDER_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    public Path export(
            RecordedScenario scenario,
            TrackStitchingAnalyzer.AnalysisResult result,
            TrackStitchingAnalyzer.Configuration configuration,
            Path parentDirectory) throws IOException {
        if (scenario == null || result == null || configuration == null) {
            throw new IllegalArgumentException("Scenario, result, and configuration are required");
        }
        if (parentDirectory == null) {
            throw new IllegalArgumentException("Output parent directory is required");
        }
        Files.createDirectories(parentDirectory);
        String timestamp = LocalDateTime.now().format(FOLDER_TIME);
        Path runDirectory = uniqueRunDirectory(parentDirectory,
                safeFileName(scenario.scenarioName()) + "_track_stitching_" + timestamp);
        Files.createDirectories(runDirectory);

        Path summaryDirectory = runDirectory.resolve("summary");
        Path bankDirectory = runDirectory.resolve("bank_evaluations");
        Path densityDirectory = runDirectory.resolve("spatial_density");
        Files.createDirectories(summaryDirectory);
        Files.createDirectories(bankDirectory);
        Files.createDirectories(densityDirectory);

        writeReadme(runDirectory, scenario, configuration, timestamp);
        writeConfiguration(
                summaryDirectory.resolve("configuration.csv"),
                scenario,
                configuration,
                timestamp);
        writeEvents(summaryDirectory.resolve("events.csv"), result.events());
        writeSegments(summaryDirectory.resolve("segments.csv"), result.events());
        writePairTimeEstimates(summaryDirectory.resolve("pair_time_estimates.csv"), result.events());
        writeAssignments(summaryDirectory.resolve("optimal_assignments.csv"), result.events());
        writeBankEvaluations(bankDirectory.resolve("bank_evaluations.csv"), result.events());
        writeSpatialDensity(
                densityDirectory.resolve("spatial_density_history.csv"),
                result.spatialDensityHistory());
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
            writeNameValue(writer, "scenario_name", scenario.scenarioName());
            writeNameValue(writer, "export_timestamp", timestamp);
            writeNameValue(writer, "source_folder", scenario.folder().toString());
            writeNameValue(writer, "duration_seconds", scenario.durationSeconds());
            writeNameValue(writer, "coasted_minimum_seconds",
                    configuration.coastedMinimumSeconds());
            writeNameValue(writer, "coasted_maximum_seconds",
                    configuration.coastedMaximumSeconds());
            writeNameValue(writer, "new_minimum_seconds", configuration.newMinimumSeconds());
            writeNameValue(writer, "new_maximum_seconds", configuration.newMaximumSeconds());
            writeNameValue(writer, "allow_dead_tracks", configuration.allowDeadTracks());
            writeNameValue(writer, "time_bank_resolution_seconds",
                    configuration.resolutionSeconds());
            writeNameValue(writer, "false_alarm_rate_per_km3",
                    configuration.falseAlarmRatePerCubicKilometer());
            writeNameValue(writer, "birth_rate_prior_per_km3",
                    configuration.birthRatePerCubicKilometer());
        }
    }

    private static void writeEvents(
            Path path,
            List<TrackStitchingAnalyzer.EventResult> events) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            writer.write(String.join(",",
                    "event_index",
                    "scenario_time_seconds",
                    "all_segment_count",
                    "old_segment_count",
                    "new_segment_count",
                    "pair_count",
                    "learned_birth_density_per_km3"));
            writer.newLine();
            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                TrackStitchingAnalyzer.EventResult event = events.get(eventIndex);
                writeCsvRow(writer,
                        eventIndex,
                        event.timeSeconds(),
                        event.allSegments().size(),
                        event.oldSegments().size(),
                        event.newSegments().size(),
                        event.pairs().size(),
                        event.learnedBirthDensityPerCubicKilometer());
            }
        }
    }

    private static void writeSegments(
            Path path,
            List<TrackStitchingAnalyzer.EventResult> events) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            List<String> header = new ArrayList<>(List.of(
                    "event_index",
                    "scenario_time_seconds",
                    "track_id",
                    "is_old_candidate",
                    "is_new_candidate",
                    "dead_at_event",
                    "live_at_event",
                    "formation_time_seconds",
                    "last_update_time_seconds",
                    "last_observed_time_seconds",
                    "most_future_time_seconds"));
            addRecordHeader(header, "formation");
            addRecordHeader(header, "last_update");
            addRecordHeader(header, "last_observed");
            addRecordHeader(header, "most_future");
            writer.write(String.join(",", header));
            writer.newLine();

            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                TrackStitchingAnalyzer.EventResult event = events.get(eventIndex);
                for (TrackStitchingAnalyzer.Segment segment : event.allSegments()) {
                    List<Object> row = new ArrayList<>();
                    row.add(eventIndex);
                    row.add(event.timeSeconds());
                    row.add(segment.trackId());
                    row.add(event.oldSegments().stream()
                            .anyMatch(old -> old.trackId().equals(segment.trackId())));
                    row.add(event.newSegments().stream()
                            .anyMatch(newSegment -> newSegment.trackId().equals(segment.trackId())));
                    row.add(segment.deadAtEvent());
                    row.add(segment.liveAtEvent());
                    row.add(segment.formationTimeSeconds());
                    row.add(segment.lastUpdateTimeSeconds());
                    row.add(segment.lastObservedTimeSeconds());
                    row.add(segment.mostFutureRecord().timeSeconds());
                    addRecord(row, segment.formationRecord());
                    addRecord(row, segment.lastUpdateRecord());
                    addRecord(row, segment.lastObservedRecord());
                    addRecord(row, segment.mostFutureRecord());
                    writeCsvRow(writer, row.toArray());
                }
            }
        }
    }

    private static void writePairTimeEstimates(
            Path path,
            List<TrackStitchingAnalyzer.EventResult> events) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            writer.write(String.join(",",
                    "event_index",
                    "scenario_time_seconds",
                    "old_track_id",
                    "new_track_id",
                    "truth_target_id",
                    "simple_join_time_seconds",
                    "kinematic_join_time_seconds",
                    "mahalanobis_bank_join_time_seconds",
                    "truth_rms_join_time_seconds",
                    "simple_nll",
                    "kinematic_nll",
                    "mahalanobis_bank_nll",
                    "truth_rms_nll",
                    "simple_mahalanobis",
                    "kinematic_mahalanobis",
                    "mahalanobis_bank_mahalanobis",
                    "truth_rms_mahalanobis",
                    "simple_static_nllr",
                    "kinematic_static_nllr",
                    "mahalanobis_bank_static_nllr",
                    "truth_rms_static_nllr",
                    "simple_learned_nllr",
                    "kinematic_learned_nllr",
                    "mahalanobis_bank_learned_nllr",
                    "truth_rms_learned_nllr"));
            writer.newLine();
            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                TrackStitchingAnalyzer.EventResult event = events.get(eventIndex);
                for (TrackStitchingAnalyzer.PairResult pair : event.pairs()) {
                    writeCsvRow(writer,
                            eventIndex,
                            event.timeSeconds(),
                            pair.oldTrackId(),
                            pair.newTrackId(),
                            pair.truthTargetId(),
                            pair.simpleJoinTimeSeconds(),
                            pair.kinematicJoinTimeSeconds(),
                            pair.statisticalJoinTimeSeconds(),
                            pair.actualJoinTimeSeconds(),
                            pair.simpleNegativeLogLikelihood(),
                            pair.kinematicNegativeLogLikelihood(),
                            pair.statisticalNegativeLogLikelihood(),
                            pair.actualNegativeLogLikelihood(),
                            pair.simpleMahalanobisDistance(),
                            pair.kinematicMahalanobisDistance(),
                            pair.statisticalMahalanobisDistance(),
                            pair.actualMahalanobisDistance(),
                            pair.simpleStaticNegativeLogLikelihoodRatio(),
                            pair.kinematicStaticNegativeLogLikelihoodRatio(),
                            pair.statisticalStaticNegativeLogLikelihoodRatio(),
                            pair.actualStaticNegativeLogLikelihoodRatio(),
                            pair.simpleLearnedNegativeLogLikelihoodRatio(),
                            pair.kinematicLearnedNegativeLogLikelihoodRatio(),
                            pair.statisticalLearnedNegativeLogLikelihoodRatio(),
                            pair.actualLearnedNegativeLogLikelihoodRatio());
                }
            }
        }
    }

    private static void writeAssignments(
            Path path,
            List<TrackStitchingAnalyzer.EventResult> events) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            writer.write(String.join(",",
                    "event_index",
                    "scenario_time_seconds",
                    "metric",
                    "old_track_id",
                    "new_track_id",
                    "variant",
                    "join_time_seconds",
                    "score"));
            writer.newLine();
            for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
                TrackStitchingAnalyzer.EventResult event = events.get(eventIndex);
                writeAssignments(writer, eventIndex, event.timeSeconds(), event.nllAssignments());
                writeAssignments(writer, eventIndex, event.timeSeconds(), event.mahalanobisAssignments());
                writeAssignments(writer, eventIndex, event.timeSeconds(), event.staticNllrAssignments());
                writeAssignments(writer, eventIndex, event.timeSeconds(), event.learnedNllrAssignments());
            }
        }
    }

    private static void writeAssignments(
            BufferedWriter writer,
            int eventIndex,
            double eventTimeSeconds,
            List<TrackStitchingAnalyzer.OptimalAssignment> assignments) throws IOException {
        for (TrackStitchingAnalyzer.OptimalAssignment assignment : assignments) {
            writeCsvRow(writer,
                    eventIndex,
                    eventTimeSeconds,
                    assignment.metric(),
                    assignment.oldTrackId(),
                    assignment.newTrackId(),
                    assignment.variant(),
                    assignment.joinTimeSeconds(),
                    assignment.score());
        }
    }

    private static void writeBankEvaluations(
            Path path,
            List<TrackStitchingAnalyzer.EventResult> events) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            List<String> header = new ArrayList<>(List.of(
                    "event_index",
                    "scenario_time_seconds",
                    "old_track_id",
                    "new_track_id",
                    "bank_time_seconds"));
            addVectorHeader(header, "old_state", STATE_SIZE);
            addMatrixHeader(header, "old_covariance", STATE_SIZE);
            addVectorHeader(header, "new_state", STATE_SIZE);
            addMatrixHeader(header, "new_covariance", STATE_SIZE);
            addVectorHeader(header, "innovation", STATE_SIZE);
            addMatrixHeader(header, "innovation_covariance", STATE_SIZE);
            header.addAll(List.of(
                    "mahalanobis_distance",
                    "innovation_quadratic",
                    "log_det_innovation_covariance",
                    "negative_log_likelihood",
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
                for (TrackStitchingAnalyzer.PairDiagnostics diagnostics : event.diagnostics()) {
                    for (TrackStitchingAnalyzer.BankEvaluation evaluation
                            : diagnostics.bankEvaluations()) {
                        List<Object> row = new ArrayList<>();
                        row.add(eventIndex);
                        row.add(event.timeSeconds());
                        row.add(evaluation.oldTrackId());
                        row.add(evaluation.newTrackId());
                        row.add(evaluation.timeSeconds());
                        addVector(row, evaluation.oldState());
                        addMatrix(row, evaluation.oldCovariance());
                        addVector(row, evaluation.newState());
                        addMatrix(row, evaluation.newCovariance());
                        addVector(row, evaluation.innovation());
                        addMatrix(row, evaluation.innovationCovariance());
                        row.add(evaluation.mahalanobisDistance());
                        row.add(evaluation.innovationQuadratic());
                        row.add(evaluation.logDeterminant());
                        row.add(evaluation.negativeLogLikelihood());
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

    private static void writeSpatialDensity(
            Path path,
            List<TrackStitchingAnalyzer.SpatialDensitySnapshot> snapshots) throws IOException {
        try (BufferedWriter writer = newWriter(path)) {
            writer.write(String.join(",",
                    "scenario_time_seconds",
                    "representative_density_per_km3",
                    "peak_density_per_km3",
                    "mean_density_per_km3",
                    "total_birth_evidence",
                    "total_exposure_scan_km3",
                    "mean_reliability",
                    "prior_density_per_km3",
                    "cell_meters",
                    "x_cells",
                    "y_cells",
                    "z_cells",
                    "min_x_m",
                    "min_y_m",
                    "min_z_m",
                    "max_x_m",
                    "max_y_m",
                    "max_z_m"));
            writer.newLine();
            for (TrackStitchingAnalyzer.SpatialDensitySnapshot snapshot : snapshots) {
                writeCsvRow(writer,
                        snapshot.timeSeconds(),
                        snapshot.representativeDensityPerCubicKilometer(),
                        snapshot.peakDensityPerCubicKilometer(),
                        snapshot.meanDensityPerCubicKilometer(),
                        snapshot.totalBirthEvidence(),
                        snapshot.totalExposureScanCubicKilometers(),
                        snapshot.meanReliability(),
                        snapshot.priorDensityPerCubicKilometer(),
                        snapshot.cellMeters(),
                        snapshot.xCells(),
                        snapshot.yCells(),
                        snapshot.zCells(),
                        snapshot.minX(),
                        snapshot.minY(),
                        snapshot.minZ(),
                        snapshot.maxX(),
                        snapshot.maxY(),
                        snapshot.maxZ());
            }
        }
    }

    private static void writeReadme(
            Path runDirectory,
            RecordedScenario scenario,
            TrackStitchingAnalyzer.Configuration configuration,
            String timestamp) throws IOException {
        Path readme = runDirectory.resolve("README.md");
        try (BufferedWriter writer = newWriter(readme)) {
            writer.write("# Track Stitching Analysis Export\n\n");
            writer.write("Scenario: `" + scenario.scenarioName() + "`\n\n");
            writer.write("Export timestamp: `" + timestamp + "` (`yyyyMMdd_HHmmss`).\n\n");
            writer.write("Source scenario folder: `" + scenario.folder() + "`\n\n");
            writer.write("Configuration: coasted window "
                    + configuration.coastedMinimumSeconds() + "-"
                    + configuration.coastedMaximumSeconds()
                    + " s, new-track window "
                    + configuration.newMinimumSeconds() + "-"
                    + configuration.newMaximumSeconds()
                    + " s, time-bank resolution "
                    + configuration.resolutionSeconds() + " s.\n\n");
            writer.write("## Files\n\n");
            writer.write("- `summary/configuration.csv`: scenario metadata and stitcher settings.\n");
            writer.write("- `summary/events.csv`: one row per candidate stitching timestamp.\n");
            writer.write("- `summary/segments.csv`: all track segments available at each candidate "
                    + "timestamp, including old/new candidate flags and anchor states/covariances.\n");
            writer.write("- `summary/pair_time_estimates.csv`: all join-time estimates and metric values "
                    + "for each old-track/new-track pair.\n");
            writer.write("- `summary/optimal_assignments.csv`: Hungarian-solver assignments for NLL, "
                    + "Mahalanobis, static/uniform NLLR, and learned-spatial NLLR.\n");
            writer.write("- `bank_evaluations/bank_evaluations.csv`: one row per candidate pair per "
                    + "time-bank sample. It includes old predicted state/covariance, new retrodicted "
                    + "state/covariance, innovation, innovation covariance, Mahalanobis distance, "
                    + "NLL, NLLR values, and learned-density query values.\n");
            writer.write("- `spatial_density/spatial_density_history.csv`: learned extraneous-track "
                    + "birth density over the course of the scenario. The learned estimator is an "
                    + "online evidence/exposure grid. Each cell stores birth evidence and "
                    + "observation exposure in scan-km^3 units; only matured first measurements "
                    + "from track starts add birth evidence, while every observable scan adds "
                    + "exposure. Density is evidence divided by exposure, then smoothed, clamped, "
                    + "and queried over the innovation volume.\n\n");
            writer.write("## MATLAB usage\n\n");
            writer.write("Use `readtable` on the CSVs. Example:\n\n");
            writer.write("```matlab\n");
            writer.write("bank = readtable(fullfile(exportRoot, 'bank_evaluations', "
                    + "'bank_evaluations.csv'));\n");
            writer.write("pairs = readtable(fullfile(exportRoot, 'summary', "
                    + "'pair_time_estimates.csv'));\n");
            writer.write("density = readtable(fullfile(exportRoot, 'spatial_density', "
                    + "'spatial_density_history.csv'));\n");
            writer.write("```\n\n");
            writer.write("Vector columns use zero-based suffixes, e.g. `old_state_0` through "
                    + "`old_state_8`. Matrix columns use row/column suffixes, e.g. "
                    + "`innovation_covariance_0_0` through `innovation_covariance_8_8`.\n");
        }
    }

    private static BufferedWriter newWriter(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    private static void writeNameValue(
            BufferedWriter writer,
            String name,
            Object value) throws IOException {
        writeCsvRow(writer, name, value);
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

    private static void addRecordHeader(List<String> header, String prefix) {
        header.add(prefix + "_updated");
        header.add(prefix + "_record_time_seconds");
        addVectorHeader(header, prefix + "_state", STATE_SIZE);
        addMatrixHeader(header, prefix + "_covariance", STATE_SIZE);
    }

    private static void addRecord(List<Object> row, TrackRecord record) {
        row.add(record.updated());
        row.add(record.timeSeconds());
        addVector(row, record.state());
        addMatrix(row, record.covariance());
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

    private static void writeCsvRow(BufferedWriter writer, Object... values) throws IOException {
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
        if (text.contains(",") || text.contains("\"") || text.contains("\n")
                || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static String safeFileName(String text) {
        String safe = text == null ? "" : text
                .replaceAll("[^A-Za-z0-9._-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_+|_+$", "");
        return safe.isBlank() ? "scenario" : safe;
    }
}
