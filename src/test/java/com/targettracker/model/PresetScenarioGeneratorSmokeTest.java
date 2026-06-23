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
                case SINGLE_TARGET_BLACKOUT, MULTI_TARGET_BLACKOUT -> 1;
                case AIRPORT_BLACKOUT -> 2;
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
}
