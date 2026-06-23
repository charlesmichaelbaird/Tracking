package com.targettracker.analysis;

import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.tracking.TrackRecord;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Verifies the full Track Stitching Analysis export tree is created and readable. */
public final class TrackStitchingAnalysisExporterSmokeTest {
    private TrackStitchingAnalysisExporterSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        RecordedScenario scenario = scenario();
        TrackStitchingAnalyzer analyzer = new TrackStitchingAnalyzer();
        TrackStitchingAnalyzer.Configuration configuration =
                new TrackStitchingAnalyzer.Configuration(1.0, 10.0, 0.0, 1.0,
                        false, 0.5, 1.0e-6, 1.0e-6);
        TrackStitchingAnalyzer.AnalysisResult result =
                analyzer.analyzeDetailed(scenario, configuration);
        if (result.events().isEmpty()
                || result.events().get(0).diagnostics().isEmpty()
                || result.events().get(0).diagnostics().get(0).bankEvaluations().isEmpty()
                || result.spatialDensityHistory().isEmpty()) {
            throw new AssertionError("Detailed analysis should include diagnostics and density history");
        }

        Path parent = Files.createTempDirectory("track_stitching_export_smoke");
        Path exportRoot = new TrackStitchingAnalysisExporter().export(
                scenario, result, configuration, parent);
        requireFile(exportRoot.resolve("README.md"));
        requireFile(exportRoot.resolve("summary").resolve("configuration.csv"));
        requireFile(exportRoot.resolve("summary").resolve("events.csv"));
        requireFile(exportRoot.resolve("summary").resolve("segments.csv"));
        requireFile(exportRoot.resolve("summary").resolve("pair_time_estimates.csv"));
        requireFile(exportRoot.resolve("summary").resolve("optimal_assignments.csv"));
        Path bank = exportRoot.resolve("bank_evaluations").resolve("bank_evaluations.csv");
        requireFile(bank);
        requireFile(exportRoot.resolve("spatial_density")
                .resolve("spatial_density_history.csv"));

        String bankCsv = Files.readString(bank);
        if (!bankCsv.contains("old_state_0")
                || !bankCsv.contains("innovation_covariance_8_8")
                || !bankCsv.contains("learned_negative_log_likelihood_ratio")) {
            throw new AssertionError("Bank export should include state, covariance, and NLLR columns");
        }
        System.out.println("TrackStitchingAnalysisExporterSmokeTest passed");
    }

    private static void requireFile(Path path) throws Exception {
        if (!Files.isRegularFile(path) || Files.size(path) == 0L) {
            throw new AssertionError("Missing or empty export file: " + path);
        }
    }

    private static RecordedScenario scenario() {
        List<TrackRecord> tracks = new ArrayList<>();
        tracks.add(track("TRK-001", 0.0, 0.0, 8.0, true));
        for (int second = 1; second <= 5; second++) {
            tracks.add(track("TRK-001", second, 8.0 * second, 8.0, false));
        }
        tracks.add(track("TRK-002", 5.0, 50.0, 12.0, true));
        List<GroundTruthRecord> truth = new ArrayList<>();
        for (int second = 0; second <= 5; second++) {
            truth.add(new GroundTruthRecord(
                    "TGT-001", second,
                    new double[]{6_378_137.0, second * 10.0, 0, 0, 10, 0, 0, 0, 0}));
        }
        double[][] covariance = new double[6][6];
        for (int index = 0; index < 6; index++) {
            covariance[index][index] = 1.0;
        }
        RecordedMeasurement measurement = new RecordedMeasurement(
                "GOD-SENSOR-001", "TGT-001", "TRK-002", 5.0,
                new double[]{6_378_137.0, 50, 0, 0, 10, 0},
                covariance, 1.0, 1.0);
        return new RecordedScenario(
                Path.of("export_stitching"), "Export stitching", 5.0,
                tracks, truth, List.of(measurement));
    }

    private static TrackRecord track(
            String id, double time, double alongTrack, double speed, boolean updated) {
        double[] state = {6_378_137.0, alongTrack, 0, 0, speed, 0, 0, 0, 0};
        double[][] covariance = new double[9][9];
        for (int index = 0; index < 9; index++) {
            covariance[index][index] = 4.0;
        }
        return new TrackRecord(id, time, state, covariance, updated);
    }
}
