package com.targettracker.model;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScenarioModel {
    private static final Color[] TARGET_COLORS = {
            new Color(30, 136, 229),
            new Color(239, 108, 0),
            new Color(67, 160, 71),
            new Color(142, 36, 170),
            new Color(0, 137, 123),
            new Color(229, 57, 53)
    };

    private final EnuFrame frame;
    private final List<TargetTrajectory> targets = new ArrayList<>();

    public ScenarioModel(EnuFrame frame) {
        this.frame = frame;
    }

    public EnuFrame frame() {
        return frame;
    }

    public List<TargetTrajectory> targets() {
        return Collections.unmodifiableList(targets);
    }

    public TargetTrajectory addTarget() {
        int number = targets.size() + 1;
        TargetTrajectory target = new TargetTrajectory(
                "TGT-%03d".formatted(number),
                TARGET_COLORS[(number - 1) % TARGET_COLORS.length]);
        targets.add(target);
        return target;
    }

    public double durationSeconds() {
        return targets.stream()
                .filter(TargetTrajectory::isRunnable)
                .mapToDouble(TargetTrajectory::durationSeconds)
                .max()
                .orElse(0.0);
    }
}
