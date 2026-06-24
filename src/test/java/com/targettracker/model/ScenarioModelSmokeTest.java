package com.targettracker.model;

/** Deterministic checks for scenario-level target and blackout bookkeeping. */
public final class ScenarioModelSmokeTest {
    private ScenarioModelSmokeTest() {
    }

    public static void main(String[] args) {
        verifyTargetIdsAreReused();
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

    private static BlackoutRegion userBlackout(ScenarioModel model, double offsetDegrees) {
        return model.addUserBlackoutRegion(
                new GeodeticPoint(offsetDegrees, offsetDegrees, 0.0),
                new GeodeticPoint(offsetDegrees + 0.01, offsetDegrees + 0.01, 0.0));
    }
}
