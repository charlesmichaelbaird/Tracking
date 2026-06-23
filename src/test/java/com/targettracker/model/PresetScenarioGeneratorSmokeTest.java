package com.targettracker.model;

import java.util.List;

/** Checks every built-in scenario's target count, timing, altitude, and stop phases. */
public final class PresetScenarioGeneratorSmokeTest {
    private static final double TOLERANCE_SECONDS = 0.05;

    private PresetScenarioGeneratorSmokeTest() {
    }

    public static void main(String[] args) {
        PresetScenarioParameters parameters = new PresetScenarioParameters(
                40.7, -74.0, 100.0, 1_500.0, 300);
        for (ScenarioPreset preset : ScenarioPreset.values()) {
            if (preset.isUserGenerated()) {
                continue;
            }
            ScenarioModel model = new ScenarioModel();
            List<TargetTrajectory> targets = PresetScenarioGenerator.generate(
                    model, preset, parameters);
            if (targets.size() != preset.targetCount()) {
                throw new AssertionError(preset + " generated the wrong target count");
            }
            int expectedBlackouts = switch (preset) {
                case SINGLE_TARGET_BLACKOUT, MULTI_TARGET_BLACKOUT,
                        MOVE_STOP_BLACKOUT_DEPARTURES,
                        DENSITY_OCEAN_BROKEN_MANEUVER,
                        DENSITY_AIRPORT_BIRTHS,
                        DENSITY_COMPETING_BIRTH,
                        DENSITY_TEMPORARY_BURST -> 1;
                case AIRPORT_BLACKOUT, DENSITY_AIRPORT_VS_OCEAN -> 2;
                default -> 0;
            };
            if (model.blackoutRegions().size() != expectedBlackouts) {
                throw new AssertionError(preset + " generated the wrong blackout count");
            }
            double speedSum = 0.0;
            for (TargetTrajectory target : targets) {
                if (!target.isRunnable()) {
                    throw new AssertionError(preset + " generated a non-runnable target");
                }
                if (Math.abs(target.durationSeconds() - 300.0) > TOLERANCE_SECONDS) {
                    throw new AssertionError(preset + " did not honor the requested duration: "
                            + target.durationSeconds());
                }
                double altitude = Wgs84.toGeodetic(target.positionAt(150.0)).altitudeMeters();
                if (Math.abs(altitude - 1_500.0) > 0.01) {
                    throw new AssertionError(preset + " did not preserve ellipsoidal altitude");
                }
                speedSum += target.velocityProfile().average();
            }
            if (Math.abs(speedSum / targets.size() - 100.0) > 1.0e-9) {
                throw new AssertionError(preset + " speed spread is not centered on the input");
            }
        }

        verifyStopProfiles(parameters);
        verifyBlackoutPresetGeometry(parameters);
        verifyMoveStopBlackoutDepartures();
        verifyDensityDiagnosticPresets(parameters);
        verifyMinimumDuration();
        System.out.println("PresetScenarioGeneratorSmokeTest passed");
    }

    private static void verifyBlackoutPresetGeometry(PresetScenarioParameters parameters) {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory straightBlackout = PresetScenarioGenerator.generate(
                model, ScenarioPreset.SINGLE_TARGET_BLACKOUT, parameters).get(0);
        if (model.blackoutRegions().isEmpty()
                || !model.blackoutRegions().get(0).contains(straightBlackout.positionAt(150.0))) {
            throw new AssertionError("Straight blackout preset should cover the trajectory middle");
        }

        ScenarioModel airportModel = new ScenarioModel();
        PresetScenarioGenerator.generate(airportModel, ScenarioPreset.AIRPORT_BLACKOUT, parameters);
        if (airportModel.blackoutRegions().size() != 2) {
            throw new AssertionError("Airport preset should define two hangar blackouts");
        }
        boolean hasHangarBirth = airportModel.targets().stream()
                .skip(4)
                .anyMatch(target -> airportModel.isInBlackout(target.positionAt(0.0)));
        if (!hasHangarBirth) {
            throw new AssertionError("Airport departures should begin inside a hangar blackout");
        }
    }

    private static void verifyMoveStopBlackoutDepartures() {
        PresetScenarioParameters parameters = new PresetScenarioParameters(
                40.7, -74.0, 100.0, 1_500.0, 15 * 60);
        ScenarioModel model = new ScenarioModel();
        List<TargetTrajectory> targets = PresetScenarioGenerator.generate(
                model, ScenarioPreset.MOVE_STOP_BLACKOUT_DEPARTURES, parameters);
        if (targets.size() != 11 || model.blackoutRegions().size() != 1) {
            throw new AssertionError(
                    "Move-stop blackout departure preset should define 11 targets and 1 blackout");
        }

        TargetTrajectory inboundStopper = targets.get(0);
        double inboundEntryTime = firstInsideTime(model, inboundStopper, parameters.durationSeconds());
        if (!Double.isFinite(inboundEntryTime)) {
            throw new AssertionError("Inbound stopper should enter the blackout region");
        }
        if (!model.isInBlackout(inboundStopper.positionAt(parameters.durationSeconds()))) {
            throw new AssertionError("Inbound stopper should end inside the blackout region");
        }

        int nearInboundDepartures = 0;
        for (int index = 1; index < targets.size(); index++) {
            TargetTrajectory departure = targets.get(index);
            if (!model.isInBlackout(departure.positionAt(0.0))) {
                throw new AssertionError("Departure target " + index
                        + " should begin stopped inside the blackout region");
            }
            double exitTime = firstOutsideTime(model, departure, parameters.durationSeconds());
            if (!Double.isFinite(exitTime)) {
                throw new AssertionError("Departure target " + index
                        + " should exit the blackout region");
            }
            if (exitTime >= inboundEntryTime) {
                throw new AssertionError("Departure target " + index
                        + " exited after the inbound target reached the blackout");
            }
            if (Math.abs(inboundEntryTime - exitTime) <= 90.0) {
                nearInboundDepartures++;
            }
        }
        if (nearInboundDepartures < 2) {
            throw new AssertionError(
                    "At least two departures should be close in time to the inbound blackout entry");
        }
    }

    private static void verifyDensityDiagnosticPresets(PresetScenarioParameters parameters) {
        ScenarioModel oceanModel = new ScenarioModel();
        TargetTrajectory oceanTarget = PresetScenarioGenerator.generate(
                oceanModel, ScenarioPreset.DENSITY_OCEAN_BROKEN_MANEUVER, parameters).get(0);
        if (!crossesBlackout(oceanModel, oceanTarget, parameters.durationSeconds())) {
            throw new AssertionError("Ocean diagnostic should force one sparse-region track break");
        }

        ScenarioModel airportModel = new ScenarioModel();
        List<TargetTrajectory> airportTargets = PresetScenarioGenerator.generate(
                airportModel, ScenarioPreset.DENSITY_AIRPORT_BIRTHS, parameters);
        if (airportTargets.stream().skip(1)
                .noneMatch(target -> airportModel.isInBlackout(target.positionAt(0.0)))) {
            throw new AssertionError("Airport density diagnostic should seed delayed births in blackout");
        }

        ScenarioModel duplicateModel = new ScenarioModel();
        List<TargetTrajectory> duplicateTargets = PresetScenarioGenerator.generate(
                duplicateModel, ScenarioPreset.DENSITY_AIRPORT_VS_OCEAN, parameters);
        if (!crossesBlackout(duplicateModel, duplicateTargets.get(0), parameters.durationSeconds())
                || !crossesBlackout(duplicateModel, duplicateTargets.get(1), parameters.durationSeconds())) {
            throw new AssertionError(
                    "Airport-vs-ocean diagnostic should create two comparable stitch gaps");
        }
        if (duplicateTargets.stream().skip(2)
                .noneMatch(target -> duplicateModel.isInBlackout(target.positionAt(0.0)))) {
            throw new AssertionError(
                    "Airport-vs-ocean diagnostic should seed births near the airport gap");
        }

        ScenarioModel competingModel = new ScenarioModel();
        List<TargetTrajectory> competingTargets = PresetScenarioGenerator.generate(
                competingModel, ScenarioPreset.DENSITY_COMPETING_BIRTH, parameters);
        if (!competingModel.isInBlackout(competingTargets.get(1).positionAt(0.0))
                || !Double.isFinite(firstInsideTime(
                        competingModel,
                        competingTargets.get(0),
                        parameters.durationSeconds()))) {
            throw new AssertionError(
                    "Competing-birth diagnostic should pair a disappearing old target with a birth");
        }

        ScenarioModel burstModel = new ScenarioModel();
        List<TargetTrajectory> burstTargets = PresetScenarioGenerator.generate(
                burstModel, ScenarioPreset.DENSITY_TEMPORARY_BURST, parameters);
        long burstBirths = burstTargets.stream().skip(1)
                .filter(target -> burstModel.isInBlackout(target.positionAt(0.0)))
                .count();
        if (burstBirths < 8) {
            throw new AssertionError("Temporary burst diagnostic should seed many delayed births");
        }
    }

    private static void verifyStopProfiles(PresetScenarioParameters parameters) {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory moveToStop = PresetScenarioGenerator.generate(
                model, ScenarioPreset.MOVE_TO_STOP, parameters).get(0);
        if (moveToStop.velocityAt(20.0) <= 0.0 || moveToStop.velocityAt(270.0) != 0.0) {
            throw new AssertionError("Move-to-stop profile should finish stationary");
        }

        TargetTrajectory stopToMove = PresetScenarioGenerator.generate(
                model, ScenarioPreset.STOP_TO_MOVE, parameters).get(0);
        if (stopToMove.velocityAt(20.0) != 0.0 || stopToMove.velocityAt(270.0) <= 0.0) {
            throw new AssertionError("Stop-to-move profile should begin stationary");
        }

        TargetTrajectory moveStopMove = PresetScenarioGenerator.generate(
                model, ScenarioPreset.MOVE_STOP_MOVE, parameters).get(0);
        if (moveStopMove.velocityAt(30.0) <= 0.0
                || moveStopMove.velocityAt(150.0) != 0.0
                || moveStopMove.velocityAt(270.0) <= 0.0) {
            throw new AssertionError("Move-stop-move profile should contain a middle dwell");
        }
    }

    private static void verifyMinimumDuration() {
        try {
            new PresetScenarioParameters(0.0, 0.0, 100.0, 1_000.0, 299);
            throw new AssertionError("Preset duration below five minutes should be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected validation path.
        }
    }

    private static double firstInsideTime(
            ScenarioModel model,
            TargetTrajectory target,
            double durationSeconds) {
        for (double time = 0.0; time <= durationSeconds; time += 5.0) {
            if (model.isInBlackout(target.positionAt(time))) {
                return time;
            }
        }
        return Double.NaN;
    }

    private static double firstOutsideTime(
            ScenarioModel model,
            TargetTrajectory target,
            double durationSeconds) {
        for (double time = 0.0; time <= durationSeconds; time += 5.0) {
            if (!model.isInBlackout(target.positionAt(time))) {
                return time;
            }
        }
        return Double.NaN;
    }

    private static boolean crossesBlackout(
            ScenarioModel model,
            TargetTrajectory target,
            double durationSeconds) {
        boolean wasOutside = !model.isInBlackout(target.positionAt(0.0));
        boolean entered = false;
        boolean exited = false;
        for (double time = 0.0; time <= durationSeconds; time += 5.0) {
            boolean inside = model.isInBlackout(target.positionAt(time));
            if (wasOutside && inside) {
                entered = true;
            }
            if (entered && !inside) {
                exited = true;
            }
        }
        return wasOutside && entered && exited;
    }
}
