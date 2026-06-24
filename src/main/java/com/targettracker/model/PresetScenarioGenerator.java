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
            double spreadStep = switch (preset) {
                case DENSITY_AIRPORT_VS_OCEAN -> 0.0;
                case DENSITY_AIRPORT_BIRTHS, DENSITY_COMPETING_BIRTH,
                        DENSITY_TEMPORARY_BURST -> 0.010;
                case AIRPORT_BLACKOUT, MOVE_STOP_BLACKOUT_DEPARTURES -> 0.015;
                default -> 0.04;
            };
            double spreadFactor = targets.size() == 1
                    ? 1.0
                    : 1.0 + (index - (targets.size() - 1) / 2.0) * spreadStep;
            configureVelocity(
                    target.velocityProfile(),
                    plan,
                    parameters.averageSpeedMetersPerSecond() * spreadFactor);
            fillProfile(target.altitudeProfile(), parameters.altitudeMeters());
            double wantedLength = target.velocityProfile().average() * parameters.durationSeconds();
            buildScaledPath(target, parameters.origin(), plan.path(), wantedLength);
        }
        model.setScenarioLengthSeconds((double) parameters.durationSeconds());
        configureBlackoutRegions(model, preset, parameters);
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
            case SINGLE_TARGET_BLACKOUT -> List.of(plan(SpeedShape.CONSTANT,
                    p(-0.64, 0.0), p(0.64, 0.0)));
            case MULTI_TARGET_BLACKOUT -> List.of(
                    plan(SpeedShape.CONSTANT, p(-0.68, -0.26), p(0.68, -0.10)),
                    plan(SpeedShape.CONSTANT, p(-0.68, -0.10), p(0.68, 0.08)),
                    plan(SpeedShape.CONSTANT, p(-0.68, 0.06), p(0.68, 0.25)),
                    plan(SpeedShape.CONSTANT, p(0.68, -0.24), p(-0.68, -0.02)),
                    plan(SpeedShape.CONSTANT, p(0.68, 0.18), p(-0.68, 0.30)));
            case MOVE_STOP_BLACKOUT_DEPARTURES -> moveStopBlackoutDeparturePlans();
            case AIRPORT_BLACKOUT -> airportPlans();
            case DENSITY_OCEAN_BROKEN_MANEUVER -> List.of(plan(SpeedShape.PULSING,
                    p(-0.68, -0.04), p(-0.28, -0.04), p(-0.02, 0.04),
                    p(0.16, 0.16), p(0.68, 0.28)));
            case DENSITY_AIRPORT_BIRTHS -> densityAirportBirthPlans();
            case DENSITY_AIRPORT_VS_OCEAN -> densityAirportVsOceanPlans();
            case DENSITY_COMPETING_BIRTH -> densityCompetingBirthPlans();
            case DENSITY_TEMPORARY_BURST -> densityTemporaryBurstPlans();
            case USER_GENERATED -> throw new IllegalArgumentException(
                    "User-generated mode has no preset paths");
        };
    }

    private static void configureVelocity(
            ScalarProfile profile,
            TargetPlan plan,
            double wantedAverage) {
        double[] raw = new double[profile.sampleCount()];
        double sum = 0.0;
        for (int index = 0; index < raw.length; index++) {
            double normalizedTime = (double) index / (raw.length - 1);
            raw[index] = plan.speedAt(normalizedTime);
            sum += raw[index];
        }
        double rawAverage = sum / raw.length;
        if (rawAverage <= 1.0e-9) {
            throw new IllegalArgumentException("Generated scenario has no moving interval");
        }
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

    private static void configureBlackoutRegions(
            ScenarioModel model,
            ScenarioPreset preset,
            PresetScenarioParameters parameters) {
        double lengthMeters = parameters.averageSpeedMetersPerSecond()
                * parameters.durationSeconds();
        switch (preset) {
            case SINGLE_TARGET_BLACKOUT -> model.addBlackoutRegion(
                    BlackoutRegion.fromLocal(
                            "Central blackout",
                            parameters.origin(),
                            0.0,
                            0.0,
                            Math.max(8_000.0, lengthMeters * 0.52),
                            Math.max(3_000.0, lengthMeters * 0.18)));
            case MULTI_TARGET_BLACKOUT -> model.addBlackoutRegion(
                    BlackoutRegion.fromLocal(
                            "Large crossing blackout",
                            parameters.origin(),
                            0.0,
                            0.03 * lengthMeters,
                            Math.max(10_000.0, lengthMeters * 0.50),
                            Math.max(6_000.0, lengthMeters * 0.34)));
            case MOVE_STOP_BLACKOUT_DEPARTURES -> model.addBlackoutRegion(
                    BlackoutRegion.fromLocal(
                            "Move-stop departure blackout",
                            parameters.origin(),
                            0.0,
                            0.0,
                            Math.max(2_500.0, lengthMeters * 0.055),
                            Math.max(2_500.0, lengthMeters * 0.055)));
            case AIRPORT_BLACKOUT -> {
                model.addBlackoutRegion(BlackoutRegion.fromLocal(
                        "West hangar blackout",
                        parameters.origin(),
                        -0.09 * lengthMeters,
                        0.075 * lengthMeters,
                        Math.max(1_800.0, lengthMeters * 0.055),
                        Math.max(1_200.0, lengthMeters * 0.038)));
                model.addBlackoutRegion(BlackoutRegion.fromLocal(
                        "East hangar blackout",
                        parameters.origin(),
                        0.09 * lengthMeters,
                        -0.075 * lengthMeters,
                        Math.max(1_800.0, lengthMeters * 0.055),
                        Math.max(1_200.0, lengthMeters * 0.038)));
            }
            case DENSITY_OCEAN_BROKEN_MANEUVER -> model.addBlackoutRegion(
                    BlackoutRegion.fromLocal(
                            "Sparse-region stitch gap",
                            parameters.origin(),
                            0.02 * lengthMeters,
                            0.06 * lengthMeters,
                            Math.max(5_000.0, lengthMeters * 0.24),
                            Math.max(3_500.0, lengthMeters * 0.16)));
            case DENSITY_AIRPORT_BIRTHS -> model.addBlackoutRegion(
                    BlackoutRegion.fromLocal(
                            "High-birth airport source",
                            parameters.origin(),
                            0.0,
                            0.02 * lengthMeters,
                            Math.max(3_000.0, lengthMeters * 0.10),
                            Math.max(3_000.0, lengthMeters * 0.09)));
            case DENSITY_AIRPORT_VS_OCEAN -> {
                model.addBlackoutRegion(BlackoutRegion.fromLocal(
                        "Airport high-birth stitch gap",
                        parameters.origin(),
                        0.0,
                        0.08 * lengthMeters,
                        Math.max(3_000.0, lengthMeters * 0.11),
                        Math.max(3_000.0, lengthMeters * 0.10)));
                model.addBlackoutRegion(BlackoutRegion.fromLocal(
                        "Sparse ocean stitch gap",
                        parameters.origin(),
                        0.0,
                        -0.30 * lengthMeters,
                        Math.max(3_000.0, lengthMeters * 0.11),
                        Math.max(3_000.0, lengthMeters * 0.10)));
            }
            case DENSITY_COMPETING_BIRTH -> model.addBlackoutRegion(
                    BlackoutRegion.fromLocal(
                            "Competing-birth airport source",
                            parameters.origin(),
                            0.0,
                            0.0,
                            Math.max(3_000.0, lengthMeters * 0.10),
                            Math.max(3_000.0, lengthMeters * 0.09)));
            case DENSITY_TEMPORARY_BURST -> model.addBlackoutRegion(
                    BlackoutRegion.fromLocal(
                            "Temporary burst source",
                            parameters.origin(),
                            0.0,
                            0.0,
                            Math.max(3_000.0, lengthMeters * 0.11),
                            Math.max(3_000.0, lengthMeters * 0.10)));
            default -> {
                // Maneuver-only presets do not include sensor blackout geometry.
            }
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
        return new TargetPlan(List.of(points), shape, 0.0, 1.0);
    }

    private static TargetPlan planWindow(
            SpeedShape shape,
            double activeStart,
            double activeEnd,
            LocalPoint... points) {
        return new TargetPlan(List.of(points), shape, activeStart, activeEnd);
    }

    private static List<TargetPlan> airportPlans() {
        return List.of(
                plan(SpeedShape.CONSTANT,
                        p(-0.18, -0.02), p(0.04, 0.00), p(0.24, 0.06), p(0.68, 0.24)),
                plan(SpeedShape.CONSTANT,
                        p(0.18, 0.02), p(-0.04, 0.00), p(-0.24, -0.05), p(-0.68, -0.20)),
                planWindow(SpeedShape.MOVE_TO_STOP, 0.0, 0.72,
                        p(0.70, 0.28), p(0.24, 0.12), p(-0.02, 0.07), p(-0.09, 0.075)),
                planWindow(SpeedShape.MOVE_TO_STOP, 0.0, 0.76,
                        p(-0.70, -0.24), p(-0.26, -0.10), p(0.02, -0.07), p(0.09, -0.075)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.08, 1.0,
                        p(-0.10, 0.078), p(-0.02, 0.04), p(0.18, 0.06), p(0.66, 0.30)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.16, 1.0,
                        p(-0.085, 0.070), p(0.00, 0.02), p(0.20, 0.00), p(0.66, -0.10)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.24, 1.0,
                        p(-0.095, 0.082), p(-0.04, 0.00), p(-0.20, 0.03), p(-0.66, 0.18)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.32, 1.0,
                        p(0.10, -0.078), p(0.02, -0.04), p(-0.18, -0.06), p(-0.66, -0.30)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.40, 1.0,
                        p(0.085, -0.070), p(0.00, -0.02), p(-0.20, 0.00), p(-0.66, 0.10)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.48, 1.0,
                        p(0.095, -0.082), p(0.04, 0.00), p(0.20, -0.03), p(0.66, -0.18)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.56, 1.0,
                        p(-0.10, 0.074), p(0.00, 0.05), p(0.24, 0.12), p(0.68, 0.42)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.64, 1.0,
                        p(0.10, -0.074), p(0.00, -0.05), p(-0.24, -0.12), p(-0.68, -0.42)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.68, 1.0,
                        p(-0.09, 0.076), p(0.02, 0.02), p(0.26, -0.03), p(0.70, -0.26)));
    }

    private static List<TargetPlan> moveStopBlackoutDeparturePlans() {
        return List.of(
                plan(SpeedShape.MOVE_TO_STOP,
                        p(-0.68, 0.0), p(-0.18, 0.0), p(0.0, 0.0)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.00, 1.0,
                        p(-0.014, -0.010), p(0.12, -0.08), p(0.62, -0.38)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.03, 1.0,
                        p(-0.010, 0.012), p(0.12, 0.08), p(0.64, 0.32)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.06, 1.0,
                        p(0.012, -0.012), p(-0.12, -0.08), p(-0.64, -0.30)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.09, 1.0,
                        p(0.014, 0.010), p(-0.12, 0.08), p(-0.62, 0.36)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.12, 1.0,
                        p(-0.006, -0.014), p(0.30, -0.16), p(0.68, -0.08)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.15, 1.0,
                        p(0.006, 0.014), p(-0.30, 0.14), p(-0.68, 0.06)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.18, 1.0,
                        p(-0.016, 0.004), p(0.10, 0.20), p(0.48, 0.52)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.22, 1.0,
                        p(0.016, -0.004), p(-0.10, -0.20), p(-0.48, -0.52)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.27, 1.0,
                        p(-0.004, 0.016), p(0.12, 0.02), p(0.68, 0.18)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.30, 1.0,
                        p(0.004, -0.016), p(-0.12, -0.02), p(-0.68, -0.18)));
    }

    private static List<TargetPlan> densityAirportBirthPlans() {
        return List.of(
                plan(SpeedShape.PULSING,
                        p(-0.70, -0.04), p(-0.24, -0.04), p(-0.02, 0.02),
                        p(0.16, 0.10), p(0.70, 0.22)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.06, 1.0,
                        p(-0.012, 0.010), p(0.16, 0.08), p(0.70, 0.36)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.12, 1.0,
                        p(0.012, 0.010), p(0.16, -0.06), p(0.70, -0.24)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.18, 1.0,
                        p(-0.014, -0.012), p(-0.16, -0.08), p(-0.70, -0.34)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.24, 1.0,
                        p(0.014, -0.012), p(-0.16, 0.08), p(-0.70, 0.30)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.30, 1.0,
                        p(-0.006, 0.016), p(0.04, 0.20), p(0.30, 0.70)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.36, 1.0,
                        p(0.006, -0.016), p(-0.04, -0.20), p(-0.30, -0.70)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.42, 1.0,
                        p(-0.018, 0.002), p(0.20, 0.02), p(0.76, 0.08)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.48, 1.0,
                        p(0.018, -0.002), p(-0.20, -0.02), p(-0.76, -0.08)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.54, 1.0,
                        p(-0.010, -0.016), p(0.14, -0.14), p(0.58, -0.52)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.58, 1.0,
                        p(0.010, 0.016), p(-0.14, 0.14), p(-0.58, 0.52)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.62, 1.0,
                        p(-0.016, 0.010), p(0.10, 0.16), p(0.48, 0.62)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.65, 1.0,
                        p(0.016, -0.010), p(-0.10, -0.16), p(-0.48, -0.62)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.68, 1.0,
                        p(0.0, 0.018), p(0.18, 0.00), p(0.76, -0.04)));
    }

    private static List<TargetPlan> densityAirportVsOceanPlans() {
        return List.of(
                plan(SpeedShape.PULSING,
                        p(-0.62, 0.02), p(-0.16, 0.02), p(0.00, 0.08),
                        p(0.18, 0.14), p(0.62, 0.24)),
                plan(SpeedShape.PULSING,
                        p(-0.62, -0.40), p(-0.16, -0.40), p(0.00, -0.34),
                        p(0.18, -0.28), p(0.62, -0.18)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.08, 1.0,
                        p(-0.010, 0.075), p(0.18, 0.16), p(0.70, 0.44)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.14, 1.0,
                        p(0.010, 0.078), p(0.20, 0.00), p(0.72, -0.12)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.20, 1.0,
                        p(-0.014, 0.070), p(-0.18, 0.00), p(-0.72, -0.18)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.26, 1.0,
                        p(0.014, 0.082), p(-0.18, 0.18), p(-0.68, 0.46)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.32, 1.0,
                        p(0.0, 0.065), p(0.06, 0.24), p(0.22, 0.78)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.38, 1.0,
                        p(0.0, 0.095), p(-0.06, -0.10), p(-0.26, -0.58)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.44, 1.0,
                        p(-0.018, 0.085), p(0.22, 0.08), p(0.78, 0.18)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.50, 1.0,
                        p(0.018, 0.075), p(-0.22, 0.08), p(-0.78, 0.18)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.56, 1.0,
                        p(-0.008, 0.092), p(0.14, 0.20), p(0.54, 0.62)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.60, 1.0,
                        p(0.008, 0.068), p(-0.14, -0.06), p(-0.54, -0.40)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.64, 1.0,
                        p(-0.015, 0.078), p(0.12, -0.04), p(0.52, -0.36)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.67, 1.0,
                        p(0.015, 0.082), p(-0.12, 0.22), p(-0.52, 0.68)));
    }

    private static List<TargetPlan> densityCompetingBirthPlans() {
        return List.of(
                planWindow(SpeedShape.MOVE_TO_STOP, 0.0, 0.82,
                        p(-0.70, -0.18), p(-0.26, -0.06), p(-0.02, 0.00), p(0.02, 0.01)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.18, 1.0,
                        p(0.0, 0.0), p(0.22, 0.08), p(0.70, 0.24)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.04, 1.0,
                        p(-0.012, 0.010), p(0.14, 0.16), p(0.58, 0.58)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.10, 1.0,
                        p(0.012, -0.010), p(0.18, -0.06), p(0.68, -0.20)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.16, 1.0,
                        p(-0.016, -0.012), p(-0.18, -0.10), p(-0.70, -0.34)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.22, 1.0,
                        p(0.016, 0.012), p(-0.18, 0.12), p(-0.68, 0.42)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.28, 1.0,
                        p(0.0, 0.018), p(0.02, 0.22), p(0.08, 0.72)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.34, 1.0,
                        p(0.0, -0.018), p(-0.04, -0.22), p(-0.10, -0.72)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.40, 1.0,
                        p(-0.018, 0.0), p(0.22, 0.00), p(0.78, 0.04)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.46, 1.0,
                        p(0.018, 0.0), p(-0.22, 0.00), p(-0.78, -0.04)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.52, 1.0,
                        p(-0.010, -0.016), p(0.12, -0.16), p(0.52, -0.58)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.58, 1.0,
                        p(0.010, 0.016), p(-0.12, 0.16), p(-0.52, 0.58)));
    }

    private static List<TargetPlan> densityTemporaryBurstPlans() {
        return List.of(
                plan(SpeedShape.PULSING,
                        p(-0.70, -0.12), p(-0.30, -0.10), p(-0.02, 0.00),
                        p(0.16, 0.12), p(0.70, 0.28)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.22, 1.0,
                        p(-0.014, 0.012), p(0.14, 0.16), p(0.60, 0.56)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.25, 1.0,
                        p(0.014, -0.012), p(0.16, -0.06), p(0.64, -0.24)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.28, 1.0,
                        p(-0.016, -0.010), p(-0.18, -0.08), p(-0.66, -0.34)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.31, 1.0,
                        p(0.016, 0.010), p(-0.16, 0.10), p(-0.62, 0.40)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.34, 1.0,
                        p(0.0, 0.018), p(0.04, 0.20), p(0.14, 0.70)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.37, 1.0,
                        p(0.0, -0.018), p(-0.04, -0.20), p(-0.14, -0.70)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.40, 1.0,
                        p(-0.018, 0.0), p(0.22, 0.02), p(0.76, 0.10)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.43, 1.0,
                        p(0.018, 0.0), p(-0.22, -0.02), p(-0.76, -0.10)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.46, 1.0,
                        p(-0.010, -0.016), p(0.12, -0.14), p(0.50, -0.56)),
                planWindow(SpeedShape.STOP_TO_MOVE, 0.49, 1.0,
                        p(0.010, 0.016), p(-0.12, 0.14), p(-0.50, 0.56)));
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

    private record TargetPlan(
            List<LocalPoint> path,
            SpeedShape speedShape,
            double activeStart,
            double activeEnd) {
        double speedAt(double normalizedTime) {
            if (normalizedTime < activeStart || normalizedTime > activeEnd) {
                return 0.0;
            }
            double span = Math.max(1.0e-9, activeEnd - activeStart);
            double localTime = (normalizedTime - activeStart) / span;
            return speedShape.valueAt(localTime);
        }
    }

    private record LocalPoint(double east, double north) {
        double distanceTo(LocalPoint other) {
            return Math.hypot(other.east - east, other.north - north);
        }
    }
}
