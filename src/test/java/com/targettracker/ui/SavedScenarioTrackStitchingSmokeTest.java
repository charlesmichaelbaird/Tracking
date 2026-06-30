package com.targettracker.ui;

import com.targettracker.analysis.TrackStitchingAnalyzer;
import com.targettracker.model.SavedScenarioDefinition;
import com.targettracker.model.SavedScenarioRepository;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.SensorSettings;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.recording.TrackCsvReader;
import com.targettracker.recording.TrackCsvRecorder;
import com.targettracker.tracking.ImmSettings;
import com.targettracker.tracking.ImmTracker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;

/** Regression check for saved blackout scenarios producing stitching candidates. */
public final class SavedScenarioTrackStitchingSmokeTest {
    private SavedScenarioTrackStitchingSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path parent = Files.createTempDirectory(Path.of("."), "saved-stitching-");
        try {
            SavedScenarioRepository repository =
                    new SavedScenarioRepository(Path.of("saved_scenarios"));
            SavedScenarioDefinition savedScenario = repository.list()
                    .stream()
                    .filter(scenario -> scenario.name().equals("2DifferentWithBlackout"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Missing saved scenario 2DifferentWithBlackout"));

            ScenarioModel model = new ScenarioModel();
            repository.loadInto(savedScenario, model);

            SensorSettings sensorSettings = new SensorSettings();
            TrackCsvRecorder recorder = new TrackCsvRecorder();
            recorder.setOutputParent(parent);
            recorder.setArmed(true);
            ScenarioPlayback playback = new ScenarioPlayback(
                    model,
                    () -> {
                    },
                    new MeasurementEngine(model, sensorSettings, new Random(7)),
                    new ImmTracker(new ImmSettings()),
                    recorder);

            if (!playback.precompute(savedScenario.name())) {
                throw new AssertionError("Saved blackout scenario should precompute");
            }
            RecordedScenario recorded = TrackCsvReader.read(recorder.runDirectory());
            TrackStitchingAnalyzer.AnalysisResult result =
                    new TrackStitchingAnalyzer().analyzeDetailed(
                            recorded,
                            new TrackStitchingAnalyzer.Configuration(
                                    0.0, 120.0, 0.0, 120.0, false, 1.0));
            if (result.events().isEmpty()) {
                throw new AssertionError(
                        "2DifferentWithBlackout should produce stitching candidates "
                                + "with 2-minute coasted/new windows");
            }
            System.out.println("SavedScenarioTrackStitchingSmokeTest passed");
        } finally {
            try (var paths = Files.walk(parent)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception exception) {
                        throw new RuntimeException(exception);
                    }
                });
            }
        }
    }
}
