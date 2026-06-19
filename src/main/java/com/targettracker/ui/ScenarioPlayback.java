package com.targettracker.ui;

import com.targettracker.model.EnuPoint;
import com.targettracker.model.ScenarioModel;
import com.targettracker.model.TargetTrajectory;

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
    private final Timer timer;
    private final Map<TargetTrajectory, Deque<EnuPoint>> recentHistory = new LinkedHashMap<>();

    private double elapsedSeconds;
    private long lastTickNanos;
    private boolean running;
    private boolean paused;

    ScenarioPlayback(ScenarioModel model, Runnable onUpdate) {
        this.model = model;
        this.onUpdate = onUpdate;
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
        recentHistory.clear();
        for (TargetTrajectory target : model.targets()) {
            if (target.isRunnable()) {
                Deque<EnuPoint> history = new ArrayDeque<>();
                history.add(target.positionAt(0.0));
                recentHistory.put(target, history);
            }
        }
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
        onUpdate.run();
    }

    void reset() {
        timer.stop();
        running = false;
        paused = false;
        elapsedSeconds = 0.0;
        recentHistory.clear();
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

    EnuPoint currentPosition(TargetTrajectory target) {
        return target.isRunnable() ? target.positionAt(elapsedSeconds) : null;
    }

    Map<TargetTrajectory, Deque<EnuPoint>> recentHistory() {
        return Collections.unmodifiableMap(recentHistory);
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

        for (TargetTrajectory target : model.targets()) {
            if (!target.isRunnable()) {
                continue;
            }
            Deque<EnuPoint> history = recentHistory.computeIfAbsent(target, ignored -> new ArrayDeque<>());
            EnuPoint position = target.positionAt(elapsedSeconds);
            EnuPoint last = history.peekLast();
            if (last == null || last.horizontalDistanceTo(position) >= 1.0) {
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
        }
        onUpdate.run();
    }
}
