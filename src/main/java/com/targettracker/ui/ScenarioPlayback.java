package com.targettracker.ui;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.EcefVector;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetMeasurement;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.model.Wgs84;
import com.targettracker.recording.GroundTruthRecord;
import com.targettracker.recording.RecordedScenario;
import com.targettracker.recording.RecordedMeasurement;
import com.targettracker.recording.TrackCsvRecorder;
import com.targettracker.tracking.ImmTracker;
import com.targettracker.tracking.TrackRecord;
import com.targettracker.tracking.TrackView;

import javax.swing.Timer;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Fast scenario computation plus a video-style replay of the cached results. */
final class ScenarioPlayback {
    private static final int TIMER_DELAY_MILLIS = 33;
    private static final double PRECOMPUTE_STEP_SECONDS = 0.1;
    private static final double TIME_EPSILON_SECONDS = 1.0e-7;

    private final ScenarioModel model;
    private final Runnable onUpdate;
    private final MeasurementEngine measurementEngine;
    private final ImmTracker immTracker;
    private final TrackCsvRecorder recorder;
    private final Timer replayTimer;
    private final Map<TargetTrajectory, Deque<EcefPoint>> recentHistory = new LinkedHashMap<>();
    private final List<ReplayFrame> replayFrames = new ArrayList<>();
    private final Map<String, List<GroundTruthRecord>> importedGroundTruth = new LinkedHashMap<>();
    private final Map<String, List<EcefPoint>> replayTrackTailPoints = new LinkedHashMap<>();
    private final List<Map<String, Integer>> replayTrackTailCounts = new ArrayList<>();
    private final Map<TargetTrajectory, List<EcefPoint>> generatedTargetHistoryPoints =
            new LinkedHashMap<>();
    private final List<Map<TargetTrajectory, Integer>> generatedTargetHistoryCounts =
            new ArrayList<>();
    private final Map<String, GroundTruthCache> importedGroundTruthCaches = new LinkedHashMap<>();
    private final List<TrackRecord> exportTrackRecords = new ArrayList<>();
    private final List<GroundTruthRecord> exportGroundTruthRecords = new ArrayList<>();

    private double elapsedSeconds;
    private double runStartSeconds;
    private double runStopSeconds;
    private long lastTickNanos;
    private boolean running;
    private boolean paused;
    private boolean computing;
    private boolean replayReady;
    private boolean replayDisplayActive;
    private boolean importedReplay;
    private double importedDurationSeconds;
    private List<TrackView> replayTrackViews = List.of();
    private List<GroundTruthView> replayGroundTruthViews = List.of();
    private List<EcefPoint> scenarioExtentPoints = List.of();

    ScenarioPlayback(
            ScenarioModel model,
            Runnable onUpdate,
            MeasurementEngine measurementEngine,
            ImmTracker immTracker) {
        this(model, onUpdate, measurementEngine, immTracker, new TrackCsvRecorder());
    }

    ScenarioPlayback(
            ScenarioModel model,
            Runnable onUpdate,
            MeasurementEngine measurementEngine,
            ImmTracker immTracker,
            TrackCsvRecorder recorder) {
        this.model = model;
        this.onUpdate = onUpdate;
        this.measurementEngine = measurementEngine;
        this.immTracker = immTracker;
        this.recorder = recorder;
        replayTimer = new Timer(TIMER_DELAY_MILLIS, event -> replayTick());
        replayTimer.setCoalesce(true);
    }

    /** Computes the complete sensor/tracker history without wall-clock waiting. */
    boolean precompute() {
        return precompute("scenario");
    }

    /** Computes and caches a complete named scenario for replay and optional export. */
    boolean precompute(String scenarioName) {
        double duration = model.durationSeconds();
        double startSeconds = model.runStartSeconds();
        double stopSeconds = model.runStopSeconds();
        double runDuration = stopSeconds - startSeconds;
        if (duration <= 0.0
                || runDuration <= 0.0
                || !hasRunnableTargetAt(startSeconds)
                || computing) {
            return false;
        }

        replayTimer.stop();
        running = false;
        paused = false;
        computing = true;
        importedReplay = false;
        importedDurationSeconds = 0.0;
        clearReplayData();
        exportTrackRecords.clear();
        exportGroundTruthRecords.clear();
        runStartSeconds = startSeconds;
        runStopSeconds = stopSeconds;
        elapsedSeconds = runStartSeconds;
        recentHistory.clear();
        immTracker.reset();
        measurementEngine.beginScenario(runStartSeconds);
        onUpdate.run();

        try {
            double time = runStartSeconds;
            while (true) {
                exportGroundTruthRecords.addAll(groundTruthRecordsAt(time));
                measurementEngine.advanceTo(time);
                immTracker.processMeasurements(measurementEngine.drainNewMeasurements());
                List<TrackRecord> measurementUpdates = immTracker.drainUpdatedRecords();
                cacheFractionalMeasurementUpdates(measurementUpdates);
                immTracker.advanceToForReplay(time);
                captureReplayFrame(time);
                cacheIntegerSecond(time, measurementUpdates);
                if (time >= runStopSeconds - TIME_EPSILON_SECONDS) {
                    break;
                }
                time = nextPrecomputeTime(time);
            }
        } finally {
            recorder.finishRun();
            computing = false;
        }

        rebuildReplayCaches();
        rebuildGeneratedTargetHistoryCache();
        replayReady = true;
        replayDisplayActive = true;
        seekToInternal(runStartSeconds);
        onUpdate.run();
        return true;
    }

    /** Loads track and measurement snapshots without invoking the sensor or tracker. */
    void loadRecordedScenario(RecordedScenario scenario) {
        replayTimer.stop();
        recorder.finishRun();
        running = false;
        paused = false;
        computing = false;
        elapsedSeconds = 0.0;
        recentHistory.clear();
        immTracker.reset();
        clearReplayData();
        importedReplay = true;
        importedDurationSeconds = scenario.durationSeconds();
        runStartSeconds = 0.0;
        runStopSeconds = importedDurationSeconds;

        TreeMap<Double, List<TrackRecord>> recordsByTime = new TreeMap<>();
        for (TrackRecord record : scenario.records()) {
            recordsByTime.computeIfAbsent(record.timeSeconds(), ignored -> new ArrayList<>())
                    .add(record);
        }
        List<TargetMeasurement> measurements = scenario.measurements().stream()
                .map(ScenarioPlayback::toTargetMeasurement)
                .toList();
        measurementEngine.loadRecordedMeasurements(measurements);
        for (GroundTruthRecord truth : scenario.groundTruth()) {
            importedGroundTruth.computeIfAbsent(truth.targetId(), ignored -> new ArrayList<>())
                    .add(truth);
        }
        importedGroundTruth.values().forEach(history -> history.sort(
                java.util.Comparator.comparingDouble(GroundTruthRecord::timeSeconds)));
        List<EcefPoint> extent = new ArrayList<>();
        if (!scenario.groundTruth().isEmpty()) {
            scenario.groundTruth().forEach(
                    record -> extent.add(pointFromState(record.state())));
        } else if (!scenario.records().isEmpty()) {
            scenario.records().forEach(record -> extent.add(pointFromState(record.state())));
        } else {
            scenario.measurements().forEach(
                    measurement -> extent.add(pointFromMean(measurement.mean())));
        }
        scenario.blackoutRegions().forEach(region ->
                region.corners().forEach(corner -> extent.add(Wgs84.toEcef(corner))));
        scenarioExtentPoints = List.copyOf(extent);
        if (recordsByTime.isEmpty()) {
            replayFrames.add(new ReplayFrame(0.0, List.of()));
        } else {
            if (recordsByTime.firstKey() > 0.0) {
                replayFrames.add(new ReplayFrame(0.0, List.of()));
            }
            Map<String, TrackRecord> latestRecords = new LinkedHashMap<>();
            recordsByTime.forEach((time, records) -> {
                records.forEach(record -> latestRecords.put(record.trackId(), record));
                List<TrackView> views = latestRecords.values().stream()
                        .map(record -> trackViewFromRecord(record, time))
                        .toList();
                replayFrames.add(new ReplayFrame(time, List.copyOf(views)));
            });
        }
        if (replayFrames.get(replayFrames.size() - 1).timeSeconds()
                < importedDurationSeconds - TIME_EPSILON_SECONDS) {
            ReplayFrame lastFrame = replayFrames.get(replayFrames.size() - 1);
            List<TrackView> endingViews = lastFrame.trackViews().stream()
                    .map(track -> new TrackView(
                            track.id(),
                            track.meanPosition(),
                            track.positionCovariance(),
                            List.of(),
                            track.color(),
                            true,
                            track.uncertaintyRadiusMeters(),
                            track.deadReason().isBlank() ? "timeout" : track.deadReason()))
                    .toList();
            replayFrames.add(new ReplayFrame(importedDurationSeconds, endingViews));
        }
        rebuildReplayCaches();
        rebuildImportedGroundTruthCaches();
        replayReady = true;
        replayDisplayActive = true;
        seekToInternal(0.0);
        onUpdate.run();
    }

    /** Starts visual replay only; sensor, tracker, and CSV state are not recomputed. */
    boolean startReplay() {
        if (!replayReady || computing) {
            return false;
        }
        if (elapsedSeconds >= runStopSeconds() - TIME_EPSILON_SECONDS
                || elapsedSeconds < runStartSeconds() - TIME_EPSILON_SECONDS) {
            seekToInternal(runStartSeconds());
        }
        running = true;
        paused = false;
        replayDisplayActive = true;
        lastTickNanos = System.nanoTime();
        replayTimer.start();
        onUpdate.run();
        return true;
    }

    boolean rewindReplayPaused() {
        if (!replayReady || computing) {
            return false;
        }
        seekToInternal(runStartSeconds());
        running = true;
        paused = true;
        replayDisplayActive = true;
        lastTickNanos = System.nanoTime();
        replayTimer.start();
        onUpdate.run();
        return true;
    }

    void togglePause() {
        if (!running) {
            return;
        }
        paused = !paused;
        lastTickNanos = System.nanoTime();
        onUpdate.run();
    }

    void stop() {
        replayTimer.stop();
        running = false;
        paused = false;
        onUpdate.run();
    }

    void reset() {
        replayTimer.stop();
        running = false;
        paused = false;
        computing = false;
        elapsedSeconds = 0.0;
        recentHistory.clear();
        measurementEngine.reset();
        immTracker.reset();
        clearReplayData();
        importedReplay = false;
        importedDurationSeconds = 0.0;
        runStartSeconds = 0.0;
        runStopSeconds = 0.0;
        exportTrackRecords.clear();
        exportGroundTruthRecords.clear();
        recorder.finishRun();
        onUpdate.run();
    }

    boolean isRunning() {
        return running;
    }

    boolean isPaused() {
        return paused;
    }

    boolean isComputing() {
        return computing;
    }

    double elapsedSeconds() {
        return elapsedSeconds;
    }

    boolean isReplayReady() {
        return replayReady;
    }

    boolean isReplayDisplayActive() {
        return replayDisplayActive;
    }

    double durationSeconds() {
        return importedReplay ? importedDurationSeconds : model.durationSeconds();
    }

    double runStartSeconds() {
        return importedReplay ? 0.0 : runStartSeconds;
    }

    double runStopSeconds() {
        return importedReplay
                ? importedDurationSeconds
                : Math.max(runStartSeconds, runStopSeconds);
    }

    boolean canExportRecording() {
        return replayReady
                && !importedReplay
                && !computing
                && !recorder.isActive()
                && (!exportTrackRecords.isEmpty() || !exportGroundTruthRecords.isEmpty());
    }

    boolean canSeek() {
        return replayReady && !computing && !recorder.isActive();
    }

    boolean canSaveTargetTrajectories() {
        return canGenerateTargetTrajectoryRecords();
    }

    int replayFrameCount() {
        return replayFrames.size();
    }

    List<TrackView> currentTrackViews() {
        return replayDisplayActive ? replayTrackViews : immTracker.currentViews();
    }

    List<GroundTruthView> currentGroundTruthViews() {
        return replayDisplayActive && importedReplay ? replayGroundTruthViews : List.of();
    }

    List<EcefPoint> scenarioExtentPoints() {
        return scenarioExtentPoints;
    }

    boolean isImportedReplay() {
        return importedReplay;
    }

    EcefPoint currentPosition(TargetTrajectory target) {
        if (!target.isRunnable()
                || elapsedSeconds > target.durationSeconds() + TIME_EPSILON_SECONDS) {
            return null;
        }
        return target.positionAt(elapsedSeconds);
    }

    Map<TargetTrajectory, Deque<EcefPoint>> recentHistory() {
        return Collections.unmodifiableMap(recentHistory);
    }

    boolean seekTo(double wantedSeconds) {
        if (!canSeek()) {
            return false;
        }
        seekToInternal(wantedSeconds);
        lastTickNanos = System.nanoTime();
        onUpdate.run();
        return true;
    }

    boolean saveRecording(String scenarioName) {
        return saveRecording(scenarioName, null);
    }

    boolean saveRecording(String scenarioName, String folderName) {
        if (!canExportRecording()) {
            return false;
        }
        if (!recorder.beginExportRun(
                scenarioName,
                folderName,
                runStopSeconds(),
                model.blackoutRegions())) {
            onUpdate.run();
            return false;
        }
        try {
            recorder.recordGroundTruth(exportGroundTruthRecords);
            recorder.recordSamples(exportTrackRecords);
        } finally {
            recorder.finishRun();
        }
        onUpdate.run();
        return recorder.lastError() == null;
    }

    boolean saveTargetTrajectories(String scenarioName) {
        return saveTargetTrajectories(scenarioName, null);
    }

    boolean saveTargetTrajectories(String scenarioName, String folderName) {
        if (!canSaveTargetTrajectories()) {
            return false;
        }
        double startSeconds = model.runStartSeconds();
        double stopSeconds = model.runStopSeconds();
        double sampleStopSeconds = targetTrajectorySampleStopSeconds(startSeconds, stopSeconds);
        if (!recorder.beginExportRun(
                scenarioName,
                folderName,
                stopSeconds,
                model.blackoutRegions())) {
            onUpdate.run();
            return false;
        }
        try {
            double time = startSeconds;
            while (true) {
                recorder.recordGroundTruth(groundTruthRecordsAt(time));
                if (time >= sampleStopSeconds - TIME_EPSILON_SECONDS) {
                    break;
                }
                time = nextPrecomputeTime(time, sampleStopSeconds);
            }
        } finally {
            recorder.finishRun();
        }
        onUpdate.run();
        return recorder.lastError() == null;
    }

    private void replayTick() {
        long now = System.nanoTime();
        if (paused) {
            lastTickNanos = now;
            return;
        }

        double duration = runStopSeconds();
        double wantedTime = elapsedSeconds + (now - lastTickNanos) / 1_000_000_000.0;
        lastTickNanos = now;
        seekToInternal(Math.min(wantedTime, duration));
        if (elapsedSeconds >= duration - TIME_EPSILON_SECONDS) {
            replayTimer.stop();
            running = false;
            paused = false;
        }
        onUpdate.run();
    }

    private double nextPrecomputeTime(double currentTime) {
        return nextPrecomputeTime(currentTime, runStopSeconds);
    }

    private static double nextPrecomputeTime(double currentTime, double stopSeconds) {
        double nextGrid = Math.ceil(
                (currentTime + TIME_EPSILON_SECONDS) / PRECOMPUTE_STEP_SECONDS)
                * PRECOMPUTE_STEP_SECONDS;
        if (nextGrid <= currentTime + TIME_EPSILON_SECONDS) {
            nextGrid = currentTime + PRECOMPUTE_STEP_SECONDS;
        }
        return Math.min(stopSeconds, nextGrid);
    }

    private boolean canGenerateTargetTrajectoryRecords() {
        double duration = model.durationSeconds();
        double startSeconds = model.runStartSeconds();
        double stopSeconds = model.runStopSeconds();
        return !importedReplay
                && !computing
                && !recorder.isActive()
                && duration > 0.0
                && stopSeconds - startSeconds > TIME_EPSILON_SECONDS
                && hasRunnableTargetAt(startSeconds);
    }

    private boolean hasRunnableTargetAt(double startSeconds) {
        return model.targets().stream()
                .anyMatch(target -> target.isRunnable()
                        && target.durationSeconds() >= startSeconds - TIME_EPSILON_SECONDS);
    }

    private double targetTrajectorySampleStopSeconds(
            double startSeconds,
            double stopSeconds) {
        double latestSampleSeconds = startSeconds;
        for (TargetTrajectory target : model.targets()) {
            if (!target.isRunnable()) {
                continue;
            }
            double targetEndSeconds = groundTruthEndTime(target.durationSeconds());
            if (targetEndSeconds < startSeconds - TIME_EPSILON_SECONDS) {
                continue;
            }
            latestSampleSeconds = Math.max(
                    latestSampleSeconds,
                    Math.min(stopSeconds, targetEndSeconds));
        }
        return Math.min(stopSeconds, latestSampleSeconds);
    }

    private void cacheIntegerSecond(double time, List<TrackRecord> measurementUpdates) {
        long integerSecond = Math.round(time);
        if (Math.abs(time - integerSecond) > TIME_EPSILON_SECONDS
                || integerSecond < runStartSeconds() - TIME_EPSILON_SECONDS
                || integerSecond > runStopSeconds() + TIME_EPSILON_SECONDS) {
            return;
        }
        Set<String> updatedTrackIds = new HashSet<>();
        for (TrackRecord update : measurementUpdates) {
            if (Math.abs(update.timeSeconds() - integerSecond) <= TIME_EPSILON_SECONDS) {
                updatedTrackIds.add(update.trackId());
            }
        }
        exportTrackRecords.addAll(immTracker.recordsAt(integerSecond, updatedTrackIds));
    }

    private void cacheFractionalMeasurementUpdates(List<TrackRecord> measurementUpdates) {
        if (measurementUpdates.isEmpty()) {
            return;
        }
        List<TrackRecord> fractionalUpdates = measurementUpdates.stream()
                .filter(update -> Math.abs(
                        update.timeSeconds() - Math.rint(update.timeSeconds()))
                        > TIME_EPSILON_SECONDS)
                .filter(update -> update.timeSeconds() >= runStartSeconds() - TIME_EPSILON_SECONDS
                        && update.timeSeconds() <= runStopSeconds() + TIME_EPSILON_SECONDS)
                .toList();
        exportTrackRecords.addAll(fractionalUpdates);
    }

    private List<GroundTruthRecord> groundTruthRecordsAt(double timeSeconds) {
        List<GroundTruthRecord> records = new ArrayList<>();
        for (TargetTrajectory target : model.targets()) {
            if (!target.isRunnable()) {
                continue;
            }
            double truthEndTime = groundTruthEndTime(target.durationSeconds());
            if (timeSeconds > truthEndTime + TIME_EPSILON_SECONDS) {
                continue;
            }
            EcefPoint position = target.positionAt(timeSeconds);
            EcefVector velocity = target.ecefVelocityAt(timeSeconds);
            double beforeTime = Math.max(0.0, timeSeconds - PRECOMPUTE_STEP_SECONDS / 2.0);
            double afterTime = Math.min(
                    target.durationSeconds(), timeSeconds + PRECOMPUTE_STEP_SECONDS / 2.0);
            EcefVector beforeVelocity = target.ecefVelocityAt(beforeTime);
            EcefVector afterVelocity = target.ecefVelocityAt(afterTime);
            double interval = Math.max(1.0e-9, afterTime - beforeTime);
            double[] state = {
                    position.x(), position.y(), position.z(),
                    velocity.x(), velocity.y(), velocity.z(),
                    (afterVelocity.x() - beforeVelocity.x()) / interval,
                    (afterVelocity.y() - beforeVelocity.y()) / interval,
                    (afterVelocity.z() - beforeVelocity.z()) / interval
            };
            records.add(new GroundTruthRecord(
                    target.id(),
                    timeSeconds,
                    state,
                    model.isInBlackout(position)));
        }
        return List.copyOf(records);
    }

    private static double groundTruthEndTime(double targetDurationSeconds) {
        if (!Double.isFinite(targetDurationSeconds) || targetDurationSeconds <= 0.0) {
            return 0.0;
        }
        long stepIndex = (long) Math.floor(
                targetDurationSeconds / PRECOMPUTE_STEP_SECONDS + TIME_EPSILON_SECONDS);
        return Math.max(0.0, stepIndex * PRECOMPUTE_STEP_SECONDS);
    }

    private void captureReplayFrame(double timeSeconds) {
        ReplayFrame frame = new ReplayFrame(timeSeconds, compactViews(immTracker.currentViews()));
        if (!replayFrames.isEmpty()
                && Math.abs(replayFrames.get(replayFrames.size() - 1).timeSeconds() - timeSeconds)
                < 1.0e-9) {
            replayFrames.set(replayFrames.size() - 1, frame);
        } else {
            replayFrames.add(frame);
        }
    }

    private void seekToInternal(double wantedSeconds) {
        if (replayFrames.isEmpty()) {
            elapsedSeconds = 0.0;
            replayTrackViews = List.of();
            recentHistory.clear();
            return;
        }
        double lower = replayReady ? runStartSeconds() : 0.0;
        double upper = replayReady ? runStopSeconds() : durationSeconds();
        elapsedSeconds = Math.max(lower, Math.min(upper, wantedSeconds));
        int frameIndex = closestFrameIndex(elapsedSeconds);
        ReplayFrame selectedFrame = replayFrames.get(frameIndex);
        Map<String, Integer> tailCounts = frameIndex < replayTrackTailCounts.size()
                ? replayTrackTailCounts.get(frameIndex)
                : Map.of();

        List<TrackView> views = new ArrayList<>();
        for (TrackView track : selectedFrame.trackViews()) {
            List<EcefPoint> fullTail = replayTrackTailPoints.getOrDefault(track.id(), List.of());
            int count = Math.min(fullTail.size(), tailCounts.getOrDefault(track.id(), 0));
            views.add(new TrackView(
                    track.id(),
                    track.meanPosition(),
                    track.positionCovariance(),
                    fullTail.subList(0, count),
                    track.color(),
                    track.dead(),
                    track.uncertaintyRadiusMeters(),
                    track.deadReason()));
        }
        replayTrackViews = List.copyOf(views);
        rebuildTargetHistory(frameIndex);
    }

    private void rebuildTargetHistory(int frameIndex) {
        recentHistory.clear();
        if (importedReplay) {
            rebuildImportedGroundTruth();
            return;
        }
        Map<TargetTrajectory, Integer> counts = frameIndex < generatedTargetHistoryCounts.size()
                ? generatedTargetHistoryCounts.get(frameIndex)
                : Map.of();
        for (TargetTrajectory target : model.targets()) {
            if (!target.isRunnable()) {
                continue;
            }
            List<EcefPoint> fullHistory =
                    generatedTargetHistoryPoints.getOrDefault(target, List.of());
            int count = Math.min(fullHistory.size(), counts.getOrDefault(target, 0));
            Deque<EcefPoint> history = new ArrayDeque<>(fullHistory.subList(0, count));
            EcefPoint exactPosition = target.positionAt(elapsedSeconds);
            EcefPoint last = history.peekLast();
            if (last == null || last.distanceTo(exactPosition) >= 1.0) {
                history.addLast(exactPosition);
            }
            recentHistory.put(target, history);
        }
    }

    private void rebuildImportedGroundTruth() {
        List<GroundTruthView> views = new ArrayList<>();
        for (Map.Entry<String, GroundTruthCache> entry : importedGroundTruthCaches.entrySet()) {
            GroundTruthCache cache = entry.getValue();
            List<GroundTruthRecord> records = cache.records();
            int currentIndex = floorTruthIndex(records, elapsedSeconds);
            if (currentIndex < 0) {
                continue;
            }
            int historyCount = Math.min(
                    cache.historyPoints().size(),
                    cache.historyCountsByRecordIndex().get(currentIndex));
            views.add(new GroundTruthView(
                    entry.getKey(),
                    pointFromState(records.get(currentIndex).state()),
                    cache.plannedPath(),
                    cache.historyPoints().subList(0, historyCount),
                    targetColor(entry.getKey())));
        }
        replayGroundTruthViews = List.copyOf(views);
    }

    private void rebuildReplayCaches() {
        replayTrackTailPoints.clear();
        replayTrackTailCounts.clear();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ReplayFrame frame : replayFrames) {
            for (TrackView track : frame.trackViews()) {
                if (track.dead()) {
                    continue;
                }
                List<EcefPoint> tail = replayTrackTailPoints.computeIfAbsent(
                        track.id(), ignored -> new ArrayList<>());
                appendDistinct(tail, track.meanPosition());
                counts.put(track.id(), tail.size());
            }
            replayTrackTailCounts.add(Map.copyOf(counts));
        }
    }

    private void rebuildGeneratedTargetHistoryCache() {
        generatedTargetHistoryPoints.clear();
        generatedTargetHistoryCounts.clear();
        Map<TargetTrajectory, Integer> counts = new LinkedHashMap<>();
        for (ReplayFrame frame : replayFrames) {
            for (TargetTrajectory target : model.targets()) {
                if (!target.isRunnable()) {
                    continue;
                }
                List<EcefPoint> history = generatedTargetHistoryPoints.computeIfAbsent(
                        target, ignored -> new ArrayList<>());
                appendDistinct(history, target.positionAt(frame.timeSeconds()));
                counts.put(target, history.size());
            }
            generatedTargetHistoryCounts.add(Map.copyOf(counts));
        }
    }

    private void rebuildImportedGroundTruthCaches() {
        importedGroundTruthCaches.clear();
        for (Map.Entry<String, List<GroundTruthRecord>> entry : importedGroundTruth.entrySet()) {
            List<GroundTruthRecord> records = entry.getValue();
            List<EcefPoint> plannedPath = new ArrayList<>();
            List<EcefPoint> historyPoints = new ArrayList<>();
            List<Integer> historyCounts = new ArrayList<>(records.size());
            for (GroundTruthRecord record : records) {
                EcefPoint point = pointFromState(record.state());
                appendDistinct(plannedPath, point);
                appendDistinct(historyPoints, point);
                historyCounts.add(historyPoints.size());
            }
            importedGroundTruthCaches.put(entry.getKey(), new GroundTruthCache(
                    records,
                    List.copyOf(plannedPath),
                    List.copyOf(historyPoints),
                    List.copyOf(historyCounts)));
        }
    }

    private static int floorTruthIndex(List<GroundTruthRecord> records, double timeSeconds) {
        int low = 0;
        int high = records.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (records.get(middle).timeSeconds() <= timeSeconds + TIME_EPSILON_SECONDS) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return high;
    }

    private static void appendDistinct(List<EcefPoint> points, EcefPoint point) {
        EcefPoint last = points.isEmpty() ? null : points.get(points.size() - 1);
        if (last == null || last.distanceTo(point) >= 1.0) {
            points.add(point);
        }
    }

    private int closestFrameIndex(double timeSeconds) {
        int low = 0;
        int high = replayFrames.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            double middleTime = replayFrames.get(middle).timeSeconds();
            if (middleTime < timeSeconds) {
                low = middle + 1;
            } else if (middleTime > timeSeconds) {
                high = middle - 1;
            } else {
                return middle;
            }
        }
        if (low >= replayFrames.size()) {
            return replayFrames.size() - 1;
        }
        if (high < 0) {
            return 0;
        }
        return timeSeconds - replayFrames.get(high).timeSeconds()
                <= replayFrames.get(low).timeSeconds() - timeSeconds ? high : low;
    }

    private void clearReplayData() {
        replayFrames.clear();
        replayTrackViews = List.of();
        replayGroundTruthViews = List.of();
        scenarioExtentPoints = List.of();
        importedGroundTruth.clear();
        replayTrackTailPoints.clear();
        replayTrackTailCounts.clear();
        generatedTargetHistoryPoints.clear();
        generatedTargetHistoryCounts.clear();
        importedGroundTruthCaches.clear();
        exportTrackRecords.clear();
        exportGroundTruthRecords.clear();
        replayReady = false;
        replayDisplayActive = false;
    }

    private static List<TrackView> compactViews(List<TrackView> views) {
        List<TrackView> compact = new ArrayList<>(views.size());
        for (TrackView track : views) {
            compact.add(new TrackView(
                    track.id(),
                    track.meanPosition(),
                    track.positionCovariance(),
                    List.of(),
                    track.color(),
                    track.dead(),
                    track.uncertaintyRadiusMeters(),
                    track.deadReason()));
        }
        return List.copyOf(compact);
    }

    private static TargetMeasurement toTargetMeasurement(RecordedMeasurement measurement) {
        double[] mean = measurement.mean();
        double[][] covariance = measurement.covariance();
        return new TargetMeasurement(
                measurement.targetId().isBlank() ? measurement.sensorId() : measurement.targetId(),
                measurement.timeSeconds(),
                new EcefPoint(mean[0], mean[1], mean[2]),
                new EcefVector(mean[3], mean[4], mean[5]),
                Math.max(0.0, covariance[0][0]),
                Math.max(0.0, covariance[3][3]));
    }

    private static EcefPoint pointFromState(double[] state) {
        return new EcefPoint(state[0], state[1], state[2]);
    }

    private static EcefPoint pointFromMean(double[] mean) {
        return new EcefPoint(mean[0], mean[1], mean[2]);
    }

    private static TrackView trackViewFromRecord(TrackRecord record, double frameTimeSeconds) {
        double[] state = record.state();
        double[][] covariance = record.covariance();
        double[][] positionCovariance = new double[3][3];
        for (int row = 0; row < 3; row++) {
            System.arraycopy(covariance[row], 0, positionCovariance[row], 0, 3);
        }
        double radius = Math.sqrt(Math.max(0.0, Math.max(
                positionCovariance[0][0],
                Math.max(positionCovariance[1][1], positionCovariance[2][2]))));
        return new TrackView(
                record.trackId(),
                new EcefPoint(state[0], state[1], state[2]),
                positionCovariance,
                List.of(),
                trackColor(record.trackId()),
                frameTimeSeconds - record.timeSeconds() > 1.01,
                radius,
                frameTimeSeconds - record.timeSeconds() > 1.01 ? "timeout" : "");
    }

    private static Color trackColor(String trackId) {
        Color[] colors = {
                new Color(255, 214, 10),
                new Color(255, 92, 166),
                new Color(72, 232, 255),
                new Color(188, 255, 92),
                new Color(255, 154, 61),
                new Color(190, 132, 255)
        };
        int number = 1;
        int dash = trackId.lastIndexOf('-');
        if (dash >= 0) {
            try {
                number = Integer.parseInt(trackId.substring(dash + 1));
            } catch (NumberFormatException ignored) {
                number = Math.abs(trackId.hashCode());
            }
        }
        return colors[Math.floorMod(number - 1, colors.length)];
    }

    private static Color targetColor(String targetId) {
        Color[] colors = {
                new Color(30, 136, 229),
                new Color(239, 108, 0),
                new Color(67, 160, 71),
                new Color(142, 36, 170),
                new Color(0, 137, 123),
                new Color(229, 57, 53)
        };
        int number = 1;
        int dash = targetId.lastIndexOf('-');
        if (dash >= 0) {
            try {
                number = Integer.parseInt(targetId.substring(dash + 1));
            } catch (NumberFormatException ignored) {
                number = Math.abs(targetId.hashCode());
            }
        }
        return colors[Math.floorMod(number - 1, colors.length)];
    }

    private record ReplayFrame(double timeSeconds, List<TrackView> trackViews) {
    }

    private record GroundTruthCache(
            List<GroundTruthRecord> records,
            List<EcefPoint> plannedPath,
            List<EcefPoint> historyPoints,
            List<Integer> historyCountsByRecordIndex) {
    }
}
