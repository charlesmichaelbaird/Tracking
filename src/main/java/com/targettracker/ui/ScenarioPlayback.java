package com.targettracker.ui;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.recording.TrackCsvRecorder;
import com.targettracker.tracking.ImmTracker;
import com.targettracker.tracking.TrackRecord;
import com.targettracker.tracking.TrackView;

import javax.swing.Timer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fast scenario computation plus a video-style replay of the cached results. */
final class ScenarioPlayback {
    private static final int TIMER_DELAY_MILLIS = 33;
    private static final int RECENT_HISTORY_POINTS = 180;
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

    private double elapsedSeconds;
    private long lastTickNanos;
    private boolean running;
    private boolean paused;
    private boolean computing;
    private boolean replayReady;
    private boolean replayDisplayActive;
    private List<TrackView> replayTrackViews = List.of();

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
        double duration = model.durationSeconds();
        if (duration <= 0.0 || computing) {
            return false;
        }

        replayTimer.stop();
        running = false;
        paused = false;
        computing = true;
        clearReplayData();
        elapsedSeconds = 0.0;
        recentHistory.clear();
        immTracker.reset();
        measurementEngine.beginScenario();
        if (!recorder.beginRun()) {
            computing = false;
            onUpdate.run();
            return false;
        }
        onUpdate.run();

        try {
            int steps = Math.max(1, (int) Math.ceil(duration / PRECOMPUTE_STEP_SECONDS));
            for (int step = 0; step <= steps; step++) {
                double time = Math.min(duration, step * PRECOMPUTE_STEP_SECONDS);
                measurementEngine.advanceTo(time);
                immTracker.processMeasurements(measurementEngine.drainNewMeasurements());
                List<TrackRecord> measurementUpdates = immTracker.drainUpdatedRecords();
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

    /** Starts visual replay only; sensor, tracker, and CSV state are not recomputed. */
    boolean startReplay() {
        if (!replayReady || computing) {
            return false;
        }
        if (elapsedSeconds >= model.durationSeconds() - TIME_EPSILON_SECONDS) {
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

    boolean canSeek() {
        return replayReady && !computing && !recorder.isActive();
    }

    int replayFrameCount() {
        return replayFrames.size();
    }

    List<TrackView> currentTrackViews() {
        return replayDisplayActive ? replayTrackViews : immTracker.currentViews();
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

        double duration = model.durationSeconds();
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
        elapsedSeconds = Math.max(0.0, Math.min(model.durationSeconds(), wantedSeconds));
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
        int firstFrame = Math.max(0, frameIndex - RECENT_HISTORY_POINTS + 1);
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

    private record ReplayFrame(double timeSeconds, List<TrackView> trackViews) {
    }
}
