package com.targettracker.model;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TargetTrajectory {
    private final String id;
    private final Color color;
    private final List<EcefPoint> path = new ArrayList<>();
    private final List<Double> segmentLengthsMeters = new ArrayList<>();
    private final ScalarProfile velocityProfile = new ScalarProfile(0.0, 600.0, 200.0);
    private final ScalarProfile altitudeProfile = new ScalarProfile(0.0, 20_000.0, 1_000.0);
    private double surfaceLengthMeters;

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

    public List<EcefPoint> path() {
        return Collections.unmodifiableList(path);
    }

    public void clearPath() {
        path.clear();
        segmentLengthsMeters.clear();
        surfaceLengthMeters = 0.0;
    }

    public void addPathPoint(EcefPoint point) {
        GeodeticPoint surfacePoint = Wgs84.toGeodetic(point).withAltitude(0.0);
        EcefPoint normalizedPoint = Wgs84.toEcef(surfacePoint);
        if (path.isEmpty()) {
            path.add(normalizedPoint);
            return;
        }
        double segmentLength = surfaceDistance(path.get(path.size() - 1), normalizedPoint);
        if (segmentLength > 1.0) {
            path.add(normalizedPoint);
            segmentLengthsMeters.add(segmentLength);
            surfaceLengthMeters += segmentLength;
        }
    }

    public ScalarProfile velocityProfile() {
        return velocityProfile;
    }

    public ScalarProfile altitudeProfile() {
        return altitudeProfile;
    }

    public boolean isRunnable() {
        return path.size() >= 2 && surfaceLengthMeters() > 0.0 && velocityProfile.average() > 0.0;
    }

    public double surfaceLengthMeters() {
        return surfaceLengthMeters;
    }

    public double durationSeconds() {
        double averageSpeed = velocityProfile.average();
        return averageSpeed <= 1.0e-9 ? 0.0 : surfaceLengthMeters() / averageSpeed;
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

    public EcefPoint positionAt(double elapsedSeconds) {
        if (path.isEmpty()) {
            return null;
        }
        if (path.size() == 1) {
            GeodeticPoint point = Wgs84.toGeodetic(path.get(0));
            return Wgs84.toEcef(point.withAltitude(altitudeAt(elapsedSeconds)));
        }

        double normalizedTime = normalizedTimeAt(elapsedSeconds);
        double wantedDistance = surfaceLengthMeters()
                * velocityProfile.normalizedIntegralAt(normalizedTime);
        double traversed = 0.0;

        for (int i = 1; i < path.size(); i++) {
            EcefPoint start = path.get(i - 1);
            EcefPoint end = path.get(i);
            double segmentLength = segmentLengthsMeters.get(i - 1);
            if (traversed + segmentLength >= wantedDistance || i == path.size() - 1) {
                double fraction = segmentLength <= 1.0e-9
                        ? 0.0
                        : (wantedDistance - traversed) / segmentLength;
                GeodeticPoint surfacePosition = Wgs84Geodesic.interpolate(
                        Wgs84.toGeodetic(start),
                        Wgs84.toGeodetic(end),
                        fraction,
                        altitudeAt(elapsedSeconds));
                return Wgs84.toEcef(surfacePosition);
            }
            traversed += segmentLength;
        }
        GeodeticPoint end = Wgs84.toGeodetic(path.get(path.size() - 1));
        return Wgs84.toEcef(end.withAltitude(altitudeAt(elapsedSeconds)));
    }

    /** Numerically differentiates the full ECEF trajectory, including altitude changes. */
    public EcefVector ecefVelocityAt(double elapsedSeconds) {
        double duration = durationSeconds();
        if (duration <= 0.0 || elapsedSeconds >= duration) {
            return EcefVector.ZERO;
        }

        double time = Math.max(0.0, elapsedSeconds);
        double differenceStep = Math.max(0.001, Math.min(0.05, duration / 10_000.0));
        double firstTime = Math.max(0.0, time - differenceStep);
        double secondTime = Math.min(duration, time + differenceStep);
        if (secondTime <= firstTime) {
            return EcefVector.ZERO;
        }

        EcefPoint first = positionAt(firstTime);
        EcefPoint second = positionAt(secondTime);
        double elapsed = secondTime - firstTime;
        return new EcefVector(
                (second.x() - first.x()) / elapsed,
                (second.y() - first.y()) / elapsed,
                (second.z() - first.z()) / elapsed);
    }

    private static double surfaceDistance(EcefPoint start, EcefPoint end) {
        return Wgs84Geodesic.inverse(
                Wgs84.toGeodetic(start),
                Wgs84.toGeodetic(end)).distanceMeters();
    }

    @Override
    public String toString() {
        return id;
    }
}
