package com.targettracker.model;

import java.util.List;

/** Creates deterministic, duration-scaled WGS-84 maneuver scenarios. */
public final class PresetScenarioGenerator {
    private PresetScenarioGenerator() {
    }

    public static List<TargetTrajectory> generate(
            ScenarioModel model,
            ScenarioPreset preset,
            PresetScenarioParameters parameters) {
        if (preset == null || preset.isUserGenerated()) {
            throw new IllegalArgumentException("Choose a pre-generated scenario");
        }
        List<TargetPlan> plans = plansFor(preset);
        if (plans.size() != preset.targetCount()) {
            throw new IllegalStateException("Preset target count does not match its definition");
        }

        List<TargetTrajectory> targets = model.replaceTargets(plans.size());
        for (int index = 0; index < targets.size(); index++) {
            TargetTrajectory target = targets.get(index);
            TargetPlan plan = plans.get(index);
            double spreadFactor = targets.size() == 1
                    ? 1.0
                    : 1.0 + (index - (targets.size() - 1) / 2.0) * 0.04;
            configureVelocity(
                    target.velocityProfile(),
                    plan.speedShape(),
                    parameters.averageSpeedMetersPerSecond() * spreadFactor);
            fillProfile(target.altitudeProfile(), parameters.altitudeMeters());
            double wantedLength = target.velocityProfile().average() * parameters.durationSeconds();
            buildScaledPath(target, parameters.origin(), plan.path(), wantedLength);
        }
        return targets;
    }

    private static List<TargetPlan> plansFor(ScenarioPreset preset) {
        return switch (preset) {
            case HARD_LEFT -> List.of(plan(SpeedShape.CONSTANT,
                    p(-0.58, -0.28), p(-0.10, -0.28), p(0.02, -0.24),
                    p(0.10, -0.12), p(0.10, 0.56)));
            case HARD_RIGHT -> List.of(plan(SpeedShape.CONSTANT,
                    p(-0.58, 0.28), p(-0.10, 0.28), p(0.02, 0.24),
                    p(0.10, 0.12), p(0.10, -0.56)));
            case U_TURN -> List.of(plan(SpeedShape.CONSTANT,
                    p(-0.58, -0.20), p(0.05, -0.20), p(0.18, -0.14),
                    p(0.24, 0.00), p(0.19, 0.15), p(0.05, 0.22), p(-0.58, 0.22)));
            case MOVE_TO_STOP -> List.of(plan(SpeedShape.MOVE_TO_STOP,
                    p(-0.55, 0.0), p(0.55, 0.0)));
            case STOP_TO_MOVE -> List.of(plan(SpeedShape.STOP_TO_MOVE,
                    p(-0.55, 0.0), p(0.55, 0.0)));
            case MOVE_STOP_MOVE -> List.of(plan(SpeedShape.MOVE_STOP_MOVE,
                    p(-0.55, 0.0), p(0.0, 0.0), p(0.55, 0.0)));
            case HEAD_ON_U_TURNS -> List.of(
                    plan(SpeedShape.CONSTANT,
                            p(-0.64, 0.04), p(-0.10, 0.04), p(-0.025, 0.02),
                            p(-0.005, 0.08), p(-0.07, 0.15), p(-0.22, 0.17), p(-0.64, 0.17)),
                    plan(SpeedShape.CONSTANT,
                            p(0.64, -0.04), p(0.10, -0.04), p(0.025, -0.02),
                            p(0.005, -0.08), p(0.07, -0.15), p(0.22, -0.17), p(0.64, -0.17)));
            case FIVE_TARGET_CLUSTER -> List.of(
                    plan(SpeedShape.CONSTANT, p(-0.62, -0.16), p(0.62, -0.16)),
                    plan(SpeedShape.CONSTANT, p(-0.62, -0.07), p(-0.05, -0.07),
                            p(0.03, -0.02), p(0.07, 0.10), p(0.07, 0.60)),
                    plan(SpeedShape.CONSTANT, p(-0.62, 0.02), p(0.62, 0.02)),
                    plan(SpeedShape.CONSTANT, p(-0.62, 0.11), p(0.02, 0.11),
                            p(0.14, 0.16), p(0.16, 0.26), p(0.08, 0.34), p(-0.58, 0.34)),
                    plan(SpeedShape.CONSTANT, p(-0.62, 0.20), p(0.62, 0.20)));
            case FOUR_WAY_CROSSING -> List.of(
                    plan(SpeedShape.CONSTANT, p(-0.62, 0.0), p(0.62, 0.0)),
                    plan(SpeedShape.CONSTANT, p(0.62, 0.0), p(-0.62, 0.0)),
                    plan(SpeedShape.CONSTANT, p(0.0, -0.62), p(0.0, 0.62)),
                    plan(SpeedShape.CONSTANT, p(0.0, 0.62), p(0.0, -0.62)));
            case COORDINATED_SWITCHBACK -> List.of(
                    plan(SpeedShape.PULSING, p(-0.62, -0.18), p(-0.38, 0.18),
                            p(-0.12, -0.18), p(0.14, 0.18), p(0.40, -0.18), p(0.62, 0.12)),
                    plan(SpeedShape.PULSING, p(-0.62, 0.0), p(-0.38, -0.24),
                            p(-0.12, 0.22), p(0.14, -0.22), p(0.40, 0.24), p(0.62, 0.0)),
                    plan(SpeedShape.PULSING, p(-0.62, 0.18), p(-0.38, -0.18),
                            p(-0.12, 0.18), p(0.14, -0.18), p(0.40, 0.18), p(0.62, -0.12)));
            case OVERTAKE_AND_SPLIT -> List.of(
                    plan(SpeedShape.PULSING, p(-0.68, -0.07), p(-0.22, -0.04),
                            p(0.05, 0.00), p(0.15, 0.08), p(0.36, 0.40)),
                    plan(SpeedShape.CONSTANT, p(-0.58, 0.0), p(0.62, 0.0)),
                    plan(SpeedShape.PULSING, p(-0.50, 0.08), p(-0.18, 0.05),
                            p(0.05, 0.00), p(0.15, -0.08), p(0.36, -0.40)));
            case MERGE_AND_FAN -> List.of(
                    plan(SpeedShape.CONSTANT, p(-0.64, -0.36), p(-0.20, -0.05),
                            p(0.16, -0.03), p(0.64, -0.42)),
                    plan(SpeedShape.CONSTANT, p(-0.64, -0.16), p(-0.20, -0.02),
                            p(0.16, -0.01), p(0.64, -0.16)),
                    plan(SpeedShape.CONSTANT, p(-0.64, 0.16), p(-0.20, 0.02),
                            p(0.16, 0.01), p(0.64, 0.16)),
                    plan(SpeedShape.CONSTANT, p(-0.64, 0.36), p(-0.20, 0.05),
                            p(0.16, 0.03), p(0.64, 0.42)));
            case USER_GENERATED -> throw new IllegalArgumentException(
                    "User-generated mode has no preset paths");
        };
    }

    private static void configureVelocity(
            ScalarProfile profile,
            SpeedShape shape,
            double wantedAverage) {
        double[] raw = new double[profile.sampleCount()];
        double sum = 0.0;
        for (int index = 0; index < raw.length; index++) {
            double normalizedTime = (double) index / (raw.length - 1);
            raw[index] = shape.valueAt(normalizedTime);
            sum += raw[index];
        }
        double rawAverage = sum / raw.length;
        double scale = wantedAverage / rawAverage;
        for (int index = 0; index < raw.length; index++) {
            double value = raw[index] * scale;
            if (value > profile.maximum() + 1.0e-9) {
                throw new IllegalArgumentException(
                        "Requested average speed makes the moving portion exceed 600 m/s");
            }
            profile.setSample(index, value);
        }
    }

    private static void fillProfile(ScalarProfile profile, double value) {
        for (int index = 0; index < profile.sampleCount(); index++) {
            profile.setSample(index, value);
        }
    }

    private static void buildScaledPath(
            TargetTrajectory target,
            GeodeticPoint origin,
            List<LocalPoint> path,
            double wantedLengthMeters) {
        double sourceLength = 0.0;
        for (int index = 1; index < path.size(); index++) {
            sourceLength += path.get(index - 1).distanceTo(path.get(index));
        }
        double metersPerUnit = wantedLengthMeters / sourceLength;
        for (int iteration = 0; iteration < 3; iteration++) {
            target.clearPath();
            for (LocalPoint point : path) {
                double eastMeters = point.east() * metersPerUnit;
                double northMeters = point.north() * metersPerUnit;
                double distance = Math.hypot(eastMeters, northMeters);
                double bearing = Math.atan2(eastMeters, northMeters);
                GeodeticPoint geodetic = Wgs84Geodesic.direct(
                        origin.withAltitude(0.0), bearing, distance, 0.0);
                target.addPathPoint(Wgs84.toEcef(geodetic));
            }
            double actualLength = target.surfaceLengthMeters();
            if (actualLength <= 0.0) {
                throw new IllegalStateException("Generated preset path has zero length");
            }
            metersPerUnit *= wantedLengthMeters / actualLength;
        }
    }

    private static TargetPlan plan(SpeedShape shape, LocalPoint... points) {
        return new TargetPlan(List.of(points), shape);
    }

    private static LocalPoint p(double east, double north) {
        return new LocalPoint(east, north);
    }

    private enum SpeedShape {
        CONSTANT {
            @Override
            double valueAt(double time) {
                return 1.0;
            }
        },
        MOVE_TO_STOP {
            @Override
            double valueAt(double time) {
                if (time <= 0.55) {
                    return 1.0;
                }
                return time < 0.65 ? (0.65 - time) / 0.10 : 0.0;
            }
        },
        STOP_TO_MOVE {
            @Override
            double valueAt(double time) {
                if (time <= 0.35) {
                    return 0.0;
                }
                return time < 0.45 ? (time - 0.35) / 0.10 : 1.0;
            }
        },
        MOVE_STOP_MOVE {
            @Override
            double valueAt(double time) {
                if (time <= 0.28) {
                    return 1.0;
                }
                if (time < 0.34) {
                    return (0.34 - time) / 0.06;
                }
                if (time <= 0.62) {
                    return 0.0;
                }
                return time < 0.68 ? (time - 0.62) / 0.06 : 1.0;
            }
        },
        PULSING {
            @Override
            double valueAt(double time) {
                return 0.72 + 0.28 * (0.5 + 0.5 * Math.sin(6.0 * Math.PI * time));
            }
        };

        abstract double valueAt(double time);
    }

    private record TargetPlan(List<LocalPoint> path, SpeedShape speedShape) {
    }

    private record LocalPoint(double east, double north) {
        double distanceTo(LocalPoint other) {
            return Math.hypot(other.east - east, other.north - north);
        }
    }
}
