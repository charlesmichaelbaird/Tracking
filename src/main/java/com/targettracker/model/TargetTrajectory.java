package com.targettracker.model;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class TargetTrajectory {
    private static final double TIME_EPSILON_SECONDS = 1.0e-6;

    public enum ExtrapolationMode {
        LINEAR,
        REPEAT_LOOP
    }

    private final String id;
    private final Color color;
    private final List<EcefPoint> path = new ArrayList<>();
    private final List<Double> segmentLengthsMeters = new ArrayList<>();
    private final List<EcefPoint> smoothingUndoPath = new ArrayList<>();
    private final List<EcefPoint> extrapolationBasePath = new ArrayList<>();
    private final ScalarProfile velocityProfile = new ScalarProfile(0.0, 600.0, 200.0);
    private final ScalarProfile altitudeProfile = new ScalarProfile(0.0, 20_000.0, 1_000.0);
    private double surfaceLengthMeters;
    private boolean extrapolatedToScenarioLength;
    private ExtrapolationMode extrapolationMode = ExtrapolationMode.LINEAR;

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
        smoothingUndoPath.clear();
        clearExtrapolationState();
        extrapolationMode = ExtrapolationMode.LINEAR;
        surfaceLengthMeters = 0.0;
    }

    public void addPathPoint(EcefPoint point) {
        clearExtrapolationState();
        extrapolationMode = ExtrapolationMode.LINEAR;
        addNormalizedPathPoint(point);
    }

    private void addNormalizedPathPoint(EcefPoint point) {
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

    public void replacePath(List<EcefPoint> points) {
        replacePath(points, ExtrapolationMode.LINEAR);
    }

    public void replacePath(List<EcefPoint> points, ExtrapolationMode extrapolationMode) {
        this.extrapolationMode = Objects.requireNonNull(extrapolationMode, "extrapolationMode");
        replacePath(points, true);
    }

    /** Copies all editable trajectory state while preserving this target's ID and color. */
    public void copyStateFrom(TargetTrajectory source) {
        Objects.requireNonNull(source, "source");
        if (source == this) {
            return;
        }
        path.clear();
        path.addAll(source.path);
        rebuildSegmentLengths();
        smoothingUndoPath.clear();
        smoothingUndoPath.addAll(source.smoothingUndoPath);
        extrapolationBasePath.clear();
        extrapolationBasePath.addAll(source.extrapolationBasePath);
        extrapolatedToScenarioLength = source.extrapolatedToScenarioLength;
        extrapolationMode = source.extrapolationMode;
        copyProfile(source.velocityProfile, velocityProfile);
        copyProfile(source.altitudeProfile, altitudeProfile);
    }

    /** Moves one control point without allowing either adjacent segment to collapse. */
    public boolean movePathPoint(int index, EcefPoint point) {
        if (index < 0 || index >= path.size() || point == null) {
            return false;
        }
        EcefPoint normalizedPoint = Wgs84.toEcef(
                Wgs84.toGeodetic(point).withAltitude(0.0));
        if (surfaceDistance(path.get(index), normalizedPoint) <= 1.0e-6) {
            return false;
        }
        if ((index > 0 && surfaceDistance(path.get(index - 1), normalizedPoint) <= 1.0)
                || (index + 1 < path.size()
                && surfaceDistance(normalizedPoint, path.get(index + 1)) <= 1.0)) {
            return false;
        }
        path.set(index, normalizedPoint);
        smoothingUndoPath.clear();
        clearExtrapolationState();
        rebuildSegmentLengths();
        return true;
    }

    public boolean translatePath(GeodeticPoint dragStart, GeodeticPoint dragEnd) {
        if (path.isEmpty()) {
            return false;
        }
        Wgs84Geodesic.GeodesicData offset = Wgs84Geodesic.inverse(
                dragStart.withAltitude(0.0),
                dragEnd.withAltitude(0.0));
        if (offset.distanceMeters() <= 1.0e-6) {
            return false;
        }
        List<EcefPoint> translated = new ArrayList<>(path.size());
        for (EcefPoint point : path) {
            GeodeticPoint geodetic = Wgs84.toGeodetic(point);
            translated.add(Wgs84.toEcef(Wgs84Geodesic.direct(
                    geodetic,
                    offset.initialBearingRadians(),
                    offset.distanceMeters(),
                    0.0)));
        }
        replacePath(translated, true);
        return true;
    }

    public boolean extrapolatedToScenarioLength() {
        return extrapolatedToScenarioLength;
    }

    public ExtrapolationMode extrapolationMode() {
        return extrapolationMode;
    }

    public boolean canExtrapolateTo(double scenarioLengthSeconds) {
        return path.size() >= 2
                && Double.isFinite(scenarioLengthSeconds)
                && scenarioLengthSeconds > durationSeconds() + 1.0e-6
                && velocityProfile.average() > 1.0e-9;
    }

    public boolean extrapolateToDuration(double scenarioLengthSeconds) {
        if (!Double.isFinite(scenarioLengthSeconds) || scenarioLengthSeconds <= 0.0) {
            return false;
        }
        List<EcefPoint> basePath = extrapolatedToScenarioLength
                ? List.copyOf(extrapolationBasePath)
                : List.copyOf(path);
        replacePathInternal(basePath, false, false);
        double wantedLength = velocityProfile.average() * scenarioLengthSeconds;
        double extraLength = wantedLength - surfaceLengthMeters();
        if (path.size() < 2 || extraLength <= 1.0) {
            clearExtrapolationState();
            return false;
        }

        GeodeticPoint previous = Wgs84.toGeodetic(path.get(path.size() - 2));
        GeodeticPoint last = Wgs84.toGeodetic(path.get(path.size() - 1));
        Wgs84Geodesic.GeodesicData finalLeg = Wgs84Geodesic.inverse(previous, last);
        if (finalLeg.distanceMeters() <= 1.0) {
            clearExtrapolationState();
            return false;
        }

        List<EcefPoint> extended = switch (extrapolationMode) {
            case REPEAT_LOOP -> loopExtrapolatedPath(path, extraLength);
            case LINEAR -> linearExtrapolatedPath(path, last, finalLeg.initialBearingRadians(),
                    extraLength);
        };
        if (extended.size() <= path.size()) {
            clearExtrapolationState();
            return false;
        }
        replacePathInternal(extended, false, false);
        extrapolationBasePath.clear();
        extrapolationBasePath.addAll(basePath);
        extrapolatedToScenarioLength = true;
        return true;
    }

    public boolean removeExtrapolation() {
        if (!extrapolatedToScenarioLength) {
            return false;
        }
        List<EcefPoint> restored = List.copyOf(extrapolationBasePath);
        replacePathInternal(restored, false, false);
        clearExtrapolationState();
        return true;
    }

    public boolean smoothPath() {
        if (path.size() < 3) {
            return false;
        }
        smoothingUndoPath.clear();
        smoothingUndoPath.addAll(path);
        List<EcefPoint> smoothed = new ArrayList<>((path.size() - 1) * 2 + 1);
        smoothed.add(path.get(0));
        for (int index = 1; index < path.size(); index++) {
            GeodeticPoint start = Wgs84.toGeodetic(path.get(index - 1));
            GeodeticPoint end = Wgs84.toGeodetic(path.get(index));
            smoothed.add(Wgs84.toEcef(Wgs84Geodesic.interpolate(start, end, 0.25, 0.0)));
            smoothed.add(Wgs84.toEcef(Wgs84Geodesic.interpolate(start, end, 0.75, 0.0)));
        }
        smoothed.add(path.get(path.size() - 1));
        replacePath(smoothed, false);
        return true;
    }

    public boolean undoSmoothing() {
        if (smoothingUndoPath.isEmpty()) {
            return false;
        }
        List<EcefPoint> restored = List.copyOf(smoothingUndoPath);
        smoothingUndoPath.clear();
        replacePath(restored, false);
        return true;
    }

    public boolean canUndoSmoothing() {
        return !smoothingUndoPath.isEmpty();
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
        return durationSecondsForLength(surfaceLengthMeters());
    }

    double unextrapolatedDurationSeconds() {
        return durationSecondsForLength(unextrapolatedSurfaceLengthMeters());
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
        if (duration <= 0.0 || elapsedSeconds - duration > TIME_EPSILON_SECONDS) {
            return EcefVector.ZERO;
        }

        double time = Math.max(0.0, Math.min(duration, elapsedSeconds));
        double differenceStep = Math.max(0.001, Math.min(0.05, duration / 10_000.0));
        double firstTime;
        double secondTime;
        if (time <= differenceStep) {
            firstTime = 0.0;
            secondTime = Math.min(duration, differenceStep);
        } else if (time >= duration - differenceStep) {
            firstTime = Math.max(0.0, duration - differenceStep);
            secondTime = duration;
        } else {
            firstTime = time - differenceStep;
            secondTime = time + differenceStep;
        }
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

    private static double pathLengthMeters(List<EcefPoint> points) {
        double length = 0.0;
        for (int index = 1; index < points.size(); index++) {
            length += surfaceDistance(points.get(index - 1), points.get(index));
        }
        return length;
    }

    private static List<EcefPoint> linearExtrapolatedPath(
            List<EcefPoint> basePath,
            GeodeticPoint last,
            double bearingRadians,
            double extraLength) {
        List<EcefPoint> extended = new ArrayList<>(basePath);
        int samples = Math.max(1, Math.min(96, (int) Math.ceil(extraLength / 20_000.0)));
        for (int sample = 1; sample <= samples; sample++) {
            double distance = extraLength * sample / samples;
            extended.add(Wgs84.toEcef(Wgs84Geodesic.direct(
                    last,
                    bearingRadians,
                    distance,
                    0.0)));
        }
        return extended;
    }

    private static List<EcefPoint> loopExtrapolatedPath(
            List<EcefPoint> basePath,
            double extraLength) {
        List<EcefPoint> cycle = repeatCyclePath(basePath);
        if (cycle.size() < 3 || pathLengthMeters(cycle) <= 1.0) {
            return List.copyOf(basePath);
        }
        List<EcefPoint> extended = new ArrayList<>(basePath);
        double remaining = extraLength;
        while (remaining > 1.0) {
            double beforeCycle = remaining;
            for (int index = 1; index < cycle.size() && remaining > 1.0; index++) {
                EcefPoint start = cycle.get(index - 1);
                EcefPoint end = cycle.get(index);
                double segmentLength = surfaceDistance(start, end);
                if (segmentLength <= 1.0) {
                    continue;
                }
                if (remaining >= segmentLength - 1.0e-6) {
                    extended.add(end);
                    remaining -= segmentLength;
                    continue;
                }
                extended.add(Wgs84.toEcef(Wgs84Geodesic.interpolate(
                        Wgs84.toGeodetic(start),
                        Wgs84.toGeodetic(end),
                        remaining / segmentLength,
                        0.0)));
                remaining = 0.0;
            }
            if (remaining >= beforeCycle - 1.0e-6) {
                break;
            }
        }
        return extended;
    }

    private static List<EcefPoint> repeatCyclePath(List<EcefPoint> points) {
        if (points.size() < 2) {
            return List.copyOf(points);
        }
        List<EcefPoint> cycle = new ArrayList<>(points.size() + 1);
        EcefPoint first = points.get(0);
        EcefPoint last = points.get(points.size() - 1);
        cycle.add(last);
        if (surfaceDistance(last, first) > 1.0) {
            cycle.add(first);
        }
        for (int index = 1; index < points.size(); index++) {
            cycle.add(points.get(index));
        }
        return cycle;
    }

    private static void copyProfile(ScalarProfile source, ScalarProfile destination) {
        for (int index = 0; index < source.sampleCount(); index++) {
            destination.setSample(index, source.sample(index));
        }
    }

    private void rebuildSegmentLengths() {
        segmentLengthsMeters.clear();
        surfaceLengthMeters = pathLengthMeters(path);
        for (int index = 1; index < path.size(); index++) {
            double segmentLength = surfaceDistance(path.get(index - 1), path.get(index));
            segmentLengthsMeters.add(segmentLength);
        }
    }

    private double unextrapolatedSurfaceLengthMeters() {
        return extrapolatedToScenarioLength && !extrapolationBasePath.isEmpty()
                ? pathLengthMeters(extrapolationBasePath)
                : surfaceLengthMeters();
    }

    private double durationSecondsForLength(double lengthMeters) {
        double averageSpeed = velocityProfile.average();
        return averageSpeed <= 1.0e-9 ? 0.0 : lengthMeters / averageSpeed;
    }

    private void replacePath(List<EcefPoint> points, boolean clearSmoothingUndo) {
        replacePathInternal(points, clearSmoothingUndo, true);
    }

    private void replacePathInternal(
            List<EcefPoint> points,
            boolean clearSmoothingUndo,
            boolean clearExtrapolation) {
        path.clear();
        segmentLengthsMeters.clear();
        surfaceLengthMeters = 0.0;
        if (clearSmoothingUndo) {
            smoothingUndoPath.clear();
        }
        if (clearExtrapolation) {
            clearExtrapolationState();
        }
        for (EcefPoint point : points) {
            addNormalizedPathPoint(point);
        }
    }

    private void clearExtrapolationState() {
        extrapolationBasePath.clear();
        extrapolatedToScenarioLength = false;
    }

    @Override
    public String toString() {
        return id;
    }
}
