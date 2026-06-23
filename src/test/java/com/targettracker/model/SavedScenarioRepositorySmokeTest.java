package com.targettracker.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Checks user-generated scenario save/list/load round-tripping. */
public final class SavedScenarioRepositorySmokeTest {
    private SavedScenarioRepositorySmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("target-tracker-scenarios");
        SavedScenarioRepository repository = new SavedScenarioRepository(directory);

        ScenarioModel model = new ScenarioModel();
        TargetTrajectory target = model.addTarget();
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.0, -75.0, 0.0)));
        target.addPathPoint(Wgs84.toEcef(new GeodeticPoint(40.1, -74.9, 0.0)));
        target.velocityProfile().setSample(20, 123.0);
        target.altitudeProfile().setSample(20, 4_321.0);
        model.addBlackoutRegion(new BlackoutRegion(
                "BLK-001",
                new GeodeticPoint(40.05, -74.95, 0.0),
                2_000.0,
                1_000.0));

        SavedScenarioDefinition saved = repository.save("Round Trip", model);
        List<SavedScenarioDefinition> listed = repository.list();
        if (listed.size() != 1 || !"Round Trip".equals(saved.name())) {
            throw new AssertionError("Saved scenario should be listed by display name");
        }

        ScenarioModel restored = new ScenarioModel();
        repository.loadInto(listed.get(0), restored);
        if (restored.targets().size() != 1
                || restored.targets().get(0).path().size() != 2
                || restored.blackoutRegions().size() != 1) {
            throw new AssertionError("Saved scenario assets were not restored");
        }
        if (Math.abs(restored.targets().get(0).velocityProfile().sample(20) - 123.0) > 1.0e-9
                || Math.abs(restored.targets().get(0).altitudeProfile().sample(20) - 4_321.0)
                > 1.0e-9) {
            throw new AssertionError("Saved motion profiles were not restored");
        }
        System.out.println("SavedScenarioRepositorySmokeTest passed");
    }
}
