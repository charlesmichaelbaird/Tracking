package com.targettracker.model;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TargetTrajectory {
    private final String id;
    private final Color color;
    private final List<EnuPoint> path = new ArrayList<>();
    private final ScalarProfile velocityProfile = new ScalarProfile(0.0, 600.0, 200.0);
    private final ScalarProfile altitudeProfile = new ScalarProfile(0.0, 20_000.0, 1_000.0);

    public TargetTrajectory(String id, Color color) {
        this.id = id;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public Color color() {
        return color;
    }

    public List<EnuPoint> path() {
        return Collections.unmodifiableList(path);
    }

    public void clearPath() {
        path.clear();
    }

    public void addPathPoint(EnuPoint point) {
        if (path.isEmpty() || path.get(path.size() - 1).horizontalDistanceTo(point) > 1.0) {
            path.add(point.withUp(0.0));
        }
    }

    public ScalarProfile velocityProfile() {
        return velocityProfile;
    }

    public ScalarProfile altitudeProfile() {
        return altitudeProfile;
    }

    public boolean isRunnable() {
        return path.size() >= 2 && horizontalLengthMeters() > 0.0 && velocityProfile.average() > 0.0;
    }

    public double horizontalLengthMeters() {
        double distance = 0.0;
        for (int i = 1; i < path.size(); i++) {
            distance += path.get(i - 1).horizontalDistanceTo(path.get(i));
        }
        return distance;
    }

    public double durationSeconds() {
        double averageSpeed = velocityProfile.average();
        return averageSpeed <= 1.0e-9 ? 0.0 : horizontalLengthMeters() / averageSpeed;
    }

    public double normalizedTimeAt(double elapsedSeconds) {
        double duration = durationSeconds();
        return duration <= 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, elapsedSeconds / duration));
    }

    public double velocityAt(double elapsedSeconds) {
        return velocityProfile.valueAt(normalizedTimeAt(elapsedSeconds));
    }

    public double altitudeAt(double elapsedSeconds) {
        return altitudeProfile.valueAt(normalizedTimeAt(elapsedSeconds));
    }

    public EnuPoint positionAt(double elapsedSeconds) {
        if (path.isEmpty()) {
            return null;
        }
        if (path.size() == 1) {
            return path.get(0).withUp(altitudeAt(elapsedSeconds));
        }

        double normalizedTime = normalizedTimeAt(elapsedSeconds);
        double wantedDistance = horizontalLengthMeters()
                * velocityProfile.normalizedIntegralAt(normalizedTime);
        double traversed = 0.0;

        for (int i = 1; i < path.size(); i++) {
            EnuPoint start = path.get(i - 1);
            EnuPoint end = path.get(i);
            double segmentLength = start.horizontalDistanceTo(end);
            if (traversed + segmentLength >= wantedDistance || i == path.size() - 1) {
                double fraction = segmentLength <= 1.0e-9
                        ? 0.0
                        : (wantedDistance - traversed) / segmentLength;
                return EnuPoint.interpolate(start, end, fraction).withUp(altitudeAt(elapsedSeconds));
            }
            traversed += segmentLength;
        }
        return path.get(path.size() - 1).withUp(altitudeAt(elapsedSeconds));
    }

    @Override
    public String toString() {
        return id;
    }
}
