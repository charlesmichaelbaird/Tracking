package com.targettracker.model;

import java.util.List;

/** Deterministic checks for scenario-level target and blackout bookkeeping. */
public final class ScenarioModelSmokeTest {
    private ScenarioModelSmokeTest() {
    }

    public static void main(String[] args) {
        verifyTargetIdsAreReused();
        verifyTargetCopiesAreIndependent();
        verifyLongestTargetDefinesScenarioExtrapolation();
        verifyLoopTargetsRepeatDuringExtrapolation();
        verifyBlackoutIdsAreReused();
        System.out.println("ScenarioModelSmokeTest passed");
    }

    private static void verifyTargetIdsAreReused() {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory first = model.addTarget();
        TargetTrajectory second = model.addTarget();
        TargetTrajectory third = model.addTarget();
        if (!"TGT-001".equals(first.id())
                || !"TGT-002".equals(second.id())
                || !"TGT-003".equals(third.id())) {
            throw new AssertionError("Initial target IDs should be sequential");
        }
        model.removeTarget(second);
        if (!"TGT-002".equals(model.addTarget().id())) {
            throw new AssertionError("Removed target IDs should be reused");
        }
        for (TargetTrajectory target : model.targets().toArray(TargetTrajectory[]::new)) {
            model.removeTarget(target);
        }
        if (!"TGT-001".equals(model.addTarget().id())) {
            throw new AssertionError("Target IDs should reset after all targets are removed");
        }
    }

    private static void verifyBlackoutIdsAreReused() {
        ScenarioModel model = new ScenarioModel();
        BlackoutRegion first = userBlackout(model, 0.0);
        BlackoutRegion second = userBlackout(model, 1.0);
        BlackoutRegion third = userBlackout(model, 2.0);
        if (!"BLK-001".equals(first.name())
                || !"BLK-002".equals(second.name())
                || !"BLK-003".equals(third.name())) {
            throw new AssertionError("Initial blackout IDs should be sequential");
        }
        model.removeBlackoutRegion(second);
        if (!"BLK-002".equals(userBlackout(model, 3.0).name())) {
            throw new AssertionError("Removed blackout IDs should be reused");
        }
        model.clearBlackoutRegions();
        if (!"BLK-001".equals(userBlackout(model, 4.0).name())) {
            throw new AssertionError("Blackout IDs should reset after all regions are removed");
        }
    }

    private static void verifyTargetCopiesAreIndependent() {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory source = model.addTarget();
        source.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.00, -74.00, 0.0)));
        source.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.01, -73.99, 0.0)));
        source.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.00, -73.98, 0.0)));
        source.velocityProfile().setSample(0, 321.0);
        source.altitudeProfile().setSample(100, 4_567.0);

        TargetTrajectory copy = model.copyTarget(source);
        if (!"TGT-002".equals(copy.id()) || copy.color().equals(source.color())) {
            throw new AssertionError("Copied targets should receive a new ID and color");
        }
        if (!copy.path().equals(source.path())
                || copy.velocityProfile().sample(0) != 321.0
                || copy.altitudeProfile().sample(100) != 4_567.0) {
            throw new AssertionError("Copied targets should preserve path and profile state");
        }

        source.velocityProfile().setSample(0, 123.0);
        source.movePathPoint(1,
                Wgs84.toEcef(new GeodeticPoint(40.02, -73.99, 0.0)));
        if (copy.velocityProfile().sample(0) != 321.0 || copy.path().equals(source.path())) {
            throw new AssertionError("Copied target state should be independent of its source");
        }

        ScenarioModel wrappedPaletteModel = new ScenarioModel();
        TargetTrajectory firstColor = wrappedPaletteModel.addTarget();
        for (int index = 0; index < 5; index++) {
            wrappedPaletteModel.addTarget();
        }
        TargetTrajectory wrappedCopy = wrappedPaletteModel.copyTarget(firstColor);
        if (!"TGT-007".equals(wrappedCopy.id())
                || wrappedCopy.color().equals(firstColor.color())) {
            throw new AssertionError("Copied targets should not reuse the source palette color");
        }
    }

    private static void verifyLongestTargetDefinesScenarioExtrapolation() {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory longTarget = model.addTarget();
        longTarget.addPathPoint(Wgs84.toEcef(new GeodeticPoint(0.0, 0.0, 0.0)));
        longTarget.addPathPoint(Wgs84.toEcef(new GeodeticPoint(0.0, 0.04, 0.0)));

        TargetTrajectory shortTarget = model.addTarget();
        shortTarget.addPathPoint(Wgs84.toEcef(new GeodeticPoint(1.0, 0.0, 0.0)));
        shortTarget.addPathPoint(Wgs84.toEcef(new GeodeticPoint(1.0, 0.01, 0.0)));

        double shortBaseDuration = shortTarget.durationSeconds();
        double longDuration = longTarget.durationSeconds();
        if (Math.abs(model.durationSeconds() - longDuration) > 1.0e-6) {
            throw new AssertionError("The longest runnable target should define scenario duration");
        }
        if (!model.canExtrapolateTargetsToScenarioDuration()) {
            throw new AssertionError("A shorter target should be eligible for extrapolation");
        }
        if (model.extrapolateTargetsToScenarioDuration() != 1) {
            throw new AssertionError("Only the shorter target should be extrapolated");
        }
        if (Math.abs(shortTarget.durationSeconds() - longDuration) > 1.0e-3
                || shortTarget.ecefVelocityAt(longDuration).magnitude() <= 1.0) {
            throw new AssertionError("The shorter target should have ECEF state through scenario end");
        }

        model.setScenarioLengthSeconds(longDuration / 2.0);
        if (Math.abs(model.durationSeconds() - longDuration) > 1.0e-6) {
            throw new AssertionError("Manual length should not cut off a longer target trajectory");
        }
        model.setScenarioLengthSeconds(null);
        model.removeTarget(longTarget);
        if (Math.abs(model.durationSeconds() - shortBaseDuration) > 1.0e-6) {
            throw new AssertionError("Extrapolated path length should not define the base scenario");
        }
    }

    private static void verifyLoopTargetsRepeatDuringExtrapolation() {
        ScenarioModel model = new ScenarioModel();
        TargetTrajectory loopTarget = model.addTarget();
        loopTarget.replacePath(List.of(
                Wgs84.toEcef(new GeodeticPoint(0.0, 0.0, 0.0)),
                Wgs84.toEcef(new GeodeticPoint(0.0, 0.01, 0.0)),
                Wgs84.toEcef(new GeodeticPoint(0.01, 0.01, 0.0)),
                Wgs84.toEcef(new GeodeticPoint(0.01, 0.0, 0.0)),
                Wgs84.toEcef(new GeodeticPoint(0.0, 0.0, 0.0))
        ), TargetTrajectory.ExtrapolationMode.REPEAT_LOOP);
        double loopDuration = loopTarget.durationSeconds();
        double loopLength = loopTarget.surfaceLengthMeters();

        TargetTrajectory longTarget = model.addTarget();
        GeodeticPoint start = new GeodeticPoint(2.0, 0.0, 0.0);
        longTarget.addPathPoint(Wgs84.toEcef(start));
        longTarget.addPathPoint(Wgs84.toEcef(Wgs84Geodesic.direct(
                start,
                Math.PI / 2.0,
                loopLength * 2.25,
                0.0)));

        if (model.extrapolateTargetsToScenarioDuration() != 1) {
            throw new AssertionError("The shorter loop target should be extrapolated");
        }
        if (loopTarget.extrapolationMode() != TargetTrajectory.ExtrapolationMode.REPEAT_LOOP) {
            throw new AssertionError("Loop targets should keep their repeat extrapolation mode");
        }
        if (Math.abs(loopTarget.durationSeconds() - longTarget.durationSeconds()) > 1.0e-3) {
            throw new AssertionError("Loop extrapolation should reach the scenario duration");
        }
        if (loopTarget.positionAt(loopDuration).distanceTo(loopTarget.positionAt(0.0)) > 5.0) {
            throw new AssertionError("Loop extrapolation should repeat the maneuver after one lap");
        }
        if (!loopTarget.removeExtrapolation()) {
            throw new AssertionError("Loop extrapolation should be removable");
        }
        if (Math.abs(loopTarget.surfaceLengthMeters() - loopLength) > 1.0e-6) {
            throw new AssertionError("Removing loop extrapolation should restore the original loop");
        }
    }

    private static BlackoutRegion userBlackout(ScenarioModel model, double offsetDegrees) {
        return model.addUserBlackoutRegion(
                new GeodeticPoint(offsetDegrees, offsetDegrees, 0.0),
                new GeodeticPoint(offsetDegrees + 0.01, offsetDegrees + 0.01, 0.0));
    }
}
