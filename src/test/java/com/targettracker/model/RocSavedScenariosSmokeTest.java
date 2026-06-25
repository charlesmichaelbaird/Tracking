package com.targettracker.model;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Checks that the saved ROC test scenarios load with their intended stress geometry. */
public final class RocSavedScenariosSmokeTest {
    private RocSavedScenariosSmokeTest() {
    }

    public static void main(String[] args) throws Exception {
        SavedScenarioRepository repository =
                new SavedScenarioRepository(Path.of("saved_scenarios"));
        Map<String, SavedScenarioDefinition> scenarios = repository.list().stream()
                .collect(Collectors.toMap(
                        SavedScenarioDefinition::name,
                        Function.identity()));
        assertScenario(repository, scenarios,
                "ROC Close Overlap Blackouts", 6, 2);
        assertScenario(repository, scenarios,
                "ROC Far Overlap Blackouts", 6, 3);
        assertScenario(repository, scenarios,
                "ROC Mixed Overlap Blackouts", 8, 3);
        System.out.println("RocSavedScenariosSmokeTest passed");
    }

    private static void assertScenario(
            SavedScenarioRepository repository,
            Map<String, SavedScenarioDefinition> scenarios,
            String name,
            int expectedTargets,
            int expectedBlackouts) {
        SavedScenarioDefinition scenario = scenarios.get(name);
        if (scenario == null) {
            throw new AssertionError("Missing saved ROC scenario: " + name);
        }
        ScenarioModel model = new ScenarioModel();
        repository.loadInto(scenario, model);
        if (model.targets().size() != expectedTargets
                || model.blackoutRegions().size() != expectedBlackouts
                || !model.hasScenarioLength()) {
            throw new AssertionError("Saved ROC scenario did not restore expected geometry: "
                    + name);
        }
    }
}
