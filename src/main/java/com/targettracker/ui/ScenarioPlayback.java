package com.targettracker.ui;

import com.targettracker.model.EcefPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;
import com.targettracker.recording.TrackCsvRecorder;
import com.targettracker.tracking.ImmTracker;

import javax.swing.Timer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

final class ScenarioPlayback {
    private static final int TIMER_DELAY_MILLIS = 33;
    private static final int RECENT_HISTORY_POINTS = 180;

    private final ScenarioModel model;
    private final Runnable onUpdate;
    private final MeasurementEngine measurementEngine;
    private final ImmTracker immTracker;
    private final TrackCsvRecorder recorder;
    private final Timer timer;
    private final Map<TargetTrajectory, Deque<EcefPoint>> recentHistory = new LinkedHashMap<>();

    private double elapsedSeconds;
    private long lastTickNanos;
    private boolean running;
    private boolean paused;

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
        this.timer = new Timer(TIMER_DELAY_MILLIS, event -> tick());
        this.timer.setCoalesce(true);
    }

    boolean start() {
        if (model.durationSeconds() <= 0.0) {
            return false;
        }

        elapsedSeconds = 0.0;
        running = true;
        paused = false;
        recorder.beginRun();
        initializeHistory();
        immTracker.reset();
        measurementEngine.beginScenario();
        measurementEngine.advanceTo(0.0);
        processNewMeasurements();
        immTracker.advanceTo(0.0);
        lastTickNanos = System.nanoTime();
        timer.start();
        onUpdate.run();
        return true;
    }

    boolean rewindPaused() {
        if (model.durationSeconds() <= 0.0) {
            return false;
        }

        elapsedSeconds = 0.0;
        running = true;
        paused = true;
        recorder.beginRun();
        initializeHistory();
        immTracker.reset();
        measurementEngine.beginScenario();
        measurementEngine.advanceTo(0.0);
        processNewMeasurements();
        immTracker.advanceTo(0.0);
        lastTickNanos = System.nanoTime();
        timer.start();
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
        timer.stop();
        running = false;
        paused = false;
        recorder.finishRun();
        onUpdate.run();
    }

    void reset() {
        timer.stop();
        running = false;
        paused = false;
        elapsedSeconds = 0.0;
        recentHistory.clear();
        measurementEngine.reset();
        immTracker.reset();
        recorder.finishRun();
        onUpdate.run();
    }

    boolean isRunning() {
        return running;
    }

    boolean isPaused() {
        return paused;
    }

    double elapsedSeconds() {
        return elapsedSeconds;
    }

    EcefPoint currentPosition(TargetTrajectory target) {
        return target.isRunnable() ? target.positionAt(elapsedSeconds) : null;
    }

    Map<TargetTrajectory, Deque<EcefPoint>> recentHistory() {
        return Collections.unmodifiableMap(recentHistory);
    }

    private void initializeHistory() {
        recentHistory.clear();
        for (TargetTrajectory target : model.targets()) {
            if (target.isRunnable()) {
                Deque<EcefPoint> history = new ArrayDeque<>();
                history.add(target.positionAt(0.0));
                recentHistory.put(target, history);
            }
        }
    }

    private void tick() {
        long now = System.nanoTime();
        if (paused) {
            lastTickNanos = now;
            return;
        }

        elapsedSeconds += (now - lastTickNanos) / 1_000_000_000.0;
        lastTickNanos = now;
        double scenarioDuration = model.durationSeconds();
        elapsedSeconds = Math.min(elapsedSeconds, scenarioDuration);
        measurementEngine.advanceTo(elapsedSeconds);
        processNewMeasurements();
        immTracker.advanceTo(elapsedSeconds);

        for (TargetTrajectory target : model.targets()) {
            if (!target.isRunnable()) {
                continue;
            }
            Deque<EcefPoint> history = recentHistory.computeIfAbsent(target, ignored -> new ArrayDeque<>());
            EcefPoint position = target.positionAt(elapsedSeconds);
            EcefPoint last = history.peekLast();
            if (last == null || last.distanceTo(position) >= 1.0) {
                history.addLast(position);
            }
            while (history.size() > RECENT_HISTORY_POINTS) {
                history.removeFirst();
            }
        }

        if (elapsedSeconds >= scenarioDuration) {
            timer.stop();
            running = false;
            paused = false;
            recorder.finishRun();
        }
        onUpdate.run();
    }

    private void processNewMeasurements() {
        immTracker.processMeasurements(measurementEngine.drainNewMeasurements());
        recorder.recordUpdates(immTracker.drainUpdatedRecords());
    }
}
