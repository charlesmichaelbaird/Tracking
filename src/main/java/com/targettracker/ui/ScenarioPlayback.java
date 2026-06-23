package com.targettracker.ui;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.EcefVector;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetMeasurement;
import com.targettracker.model.TargetTrajectory;
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

    private double elapsedSeconds;
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

    /** Computes and optionally records a complete named scenario. */
    boolean precompute(String scenarioName) {
        double duration = model.durationSeconds();
        if (duration <= 0.0 || computing) {
            return false;
        }

        replayTimer.stop();
        running = false;
        paused = false;
        computing = true;
        importedReplay = false;
        importedDurationSeconds = 0.0;
        clearReplayData();
        elapsedSeconds = 0.0;
        recentHistory.clear();
        immTracker.reset();
        measurementEngine.beginScenario();
        if (!recorder.beginRun(scenarioName, duration)) {
            computing = false;
            onUpdate.run();
            return false;
        }
        onUpdate.run();

        try {
            int steps = Math.max(1, (int) Math.ceil(duration / PRECOMPUTE_STEP_SECONDS));
            for (int step = 0; step <= steps; step++) {
                double time = Math.min(duration, step * PRECOMPUTE_STEP_SECONDS);
                recordGroundTruth(time);
                measurementEngine.advanceTo(time);
                immTracker.processMeasurements(measurementEngine.drainNewMeasurements());
                List<TrackRecord> measurementUpdates = immTracker.drainUpdatedRecords();
                recordFractionalMeasurementUpdates(measurementUpdates);
                immTracker.advanceToForReplay(time);
                captureReplayFrame(time);
                recordIntegerSecond(time, measurementUpdates);
                if (time >= duration) {
                    break;
                }
            }
        } finally {
            recorder.finishRun();
            computing = false;
        }

        replayReady = true;
        replayDisplayActive = true;
        seekToInternal(0.0);
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
                            track.uncertaintyRadiusMeters()))
                    .toList();
            replayFrames.add(new ReplayFrame(importedDurationSeconds, endingViews));
        }
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
        if (elapsedSeconds >= durationSeconds() - TIME_EPSILON_SECONDS) {
            seekToInternal(0.0);
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
        seekToInternal(0.0);
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

    boolean canSeek() {
        return replayReady && !computing && !recorder.isActive();
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
        return target.isRunnable() ? target.positionAt(elapsedSeconds) : null;
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

    private void replayTick() {
        long now = System.nanoTime();
        if (paused) {
            lastTickNanos = now;
            return;
        }

        double duration = durationSeconds();
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

    private void recordIntegerSecond(double time, List<TrackRecord> measurementUpdates) {
        long integerSecond = Math.round(time);
        if (Math.abs(time - integerSecond) > TIME_EPSILON_SECONDS || !recorder.isActive()) {
            return;
        }
        Set<String> updatedTrackIds = new HashSet<>();
        for (TrackRecord update : measurementUpdates) {
            if (Math.abs(update.timeSeconds() - integerSecond) <= TIME_EPSILON_SECONDS) {
                updatedTrackIds.add(update.trackId());
            }
        }
        recorder.recordSamples(immTracker.recordsAt(integerSecond, updatedTrackIds));
    }

    private void recordFractionalMeasurementUpdates(List<TrackRecord> measurementUpdates) {
        if (!recorder.isActive() || measurementUpdates.isEmpty()) {
            return;
        }
        List<TrackRecord> fractionalUpdates = measurementUpdates.stream()
                .filter(update -> Math.abs(
                        update.timeSeconds() - Math.rint(update.timeSeconds()))
                        > TIME_EPSILON_SECONDS)
                .toList();
        recorder.recordSamples(fractionalUpdates);
    }

    private void recordGroundTruth(double timeSeconds) {
        if (!recorder.isActive()) {
            return;
        }
        List<GroundTruthRecord> records = new ArrayList<>();
        for (TargetTrajectory target : model.targets()) {
            if (!target.isRunnable()
                    || timeSeconds > target.durationSeconds() + TIME_EPSILON_SECONDS) {
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
            records.add(new GroundTruthRecord(target.id(), timeSeconds, state));
        }
        recorder.recordGroundTruth(records);
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
        elapsedSeconds = Math.max(0.0, Math.min(durationSeconds(), wantedSeconds));
        int frameIndex = closestFrameIndex(elapsedSeconds);
        ReplayFrame selectedFrame = replayFrames.get(frameIndex);

        Map<String, List<EcefPoint>> tailsByTrack = new LinkedHashMap<>();
        for (int index = 0; index <= frameIndex; index++) {
            for (TrackView track : replayFrames.get(index).trackViews()) {
                if (track.dead()) {
                    continue;
                }
                List<EcefPoint> tail = tailsByTrack.computeIfAbsent(
                        track.id(), ignored -> new ArrayList<>());
                EcefPoint last = tail.isEmpty() ? null : tail.get(tail.size() - 1);
                if (last == null || last.distanceTo(track.meanPosition()) >= 1.0) {
                    tail.add(track.meanPosition());
                }
            }
        }

        List<TrackView> views = new ArrayList<>();
        for (TrackView track : selectedFrame.trackViews()) {
            views.add(new TrackView(
                    track.id(),
                    track.meanPosition(),
                    track.positionCovariance(),
                    List.copyOf(tailsByTrack.getOrDefault(track.id(), List.of())),
                    track.color(),
                    track.dead(),
                    track.uncertaintyRadiusMeters()));
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
        int firstFrame = 0;
        for (TargetTrajectory target : model.targets()) {
            if (!target.isRunnable()) {
                continue;
            }
            Deque<EcefPoint> history = new ArrayDeque<>();
            for (int index = firstFrame; index <= frameIndex; index++) {
                EcefPoint position = target.positionAt(replayFrames.get(index).timeSeconds());
                EcefPoint last = history.peekLast();
                if (last == null || last.distanceTo(position) >= 1.0) {
                    history.addLast(position);
                }
            }
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
        for (Map.Entry<String, List<GroundTruthRecord>> entry : importedGroundTruth.entrySet()) {
            List<GroundTruthRecord> records = entry.getValue();
            int currentIndex = floorTruthIndex(records, elapsedSeconds);
            if (currentIndex < 0) {
                continue;
            }
            List<EcefPoint> plannedPath = new ArrayList<>();
            List<EcefPoint> history = new ArrayList<>();
            for (int index = 0; index < records.size(); index++) {
                EcefPoint point = pointFromState(records.get(index).state());
                appendDistinct(plannedPath, point);
                if (index <= currentIndex) {
                    appendDistinct(history, point);
                }
            }
            views.add(new GroundTruthView(
                    entry.getKey(),
                    pointFromState(records.get(currentIndex).state()),
                    List.copyOf(plannedPath),
                    List.copyOf(history),
                    targetColor(entry.getKey())));
        }
        replayGroundTruthViews = List.copyOf(views);
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
                    track.uncertaintyRadiusMeters()));
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
                radius);
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
}
