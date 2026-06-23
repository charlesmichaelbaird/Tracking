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

    private final List<TargetTrajectory> targets = new ArrayList<>();
    private final List<BlackoutRegion> blackoutRegions = new ArrayList<>();
    private int nextTargetNumber = 1;

    public ScenarioModel() {
    }

    public List<TargetTrajectory> targets() {
        return Collections.unmodifiableList(targets);
    }

    public List<BlackoutRegion> blackoutRegions() {
        return Collections.unmodifiableList(blackoutRegions);
    }

    public void addBlackoutRegion(BlackoutRegion region) {
        blackoutRegions.add(region);
    }

    public BlackoutRegion addUserBlackoutRegion(
            GeodeticPoint firstCorner,
            GeodeticPoint secondCorner) {
        BlackoutRegion region = BlackoutRegion.fromOppositeCorners(
                "BLK-%03d".formatted(blackoutRegions.size() + 1),
                firstCorner,
                secondCorner);
        addBlackoutRegion(region);
        return region;
    }

    public void clearBlackoutRegions() {
        blackoutRegions.clear();
    }

    public boolean isInBlackout(EcefPoint point) {
        return blackoutRegions.stream().anyMatch(region -> region.contains(point));
    }

    public TargetTrajectory addTarget() {
        int number = nextTargetNumber++;
        TargetTrajectory target = new TargetTrajectory(
                "TGT-%03d".formatted(number),
                TARGET_COLORS[(number - 1) % TARGET_COLORS.length]);
        targets.add(target);
        return target;
    }

    public boolean removeTarget(TargetTrajectory target) {
        return targets.remove(target);
    }

    /** Replaces the scenario target set and returns the newly numbered targets. */
    public List<TargetTrajectory> replaceTargets(int targetCount) {
        if (targetCount < 0) {
            throw new IllegalArgumentException("Target count cannot be negative");
        }
        targets.clear();
        blackoutRegions.clear();
        nextTargetNumber = 1;
        for (int index = 0; index < targetCount; index++) {
            addTarget();
        }
        return targets();
    }

    public double durationSeconds() {
        return targets.stream()
                .filter(TargetTrajectory::isRunnable)
                .mapToDouble(TargetTrajectory::durationSeconds)
                .max()
                .orElse(0.0);
    }
}
