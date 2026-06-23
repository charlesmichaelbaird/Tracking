package com.targettracker.model;

/** Built-in maneuver scenarios. */
public enum ScenarioPreset {
    USER_GENERATED("User generated", 0),
    HARD_LEFT("1 target — hard left turn", 1),
    HARD_RIGHT("1 target — hard right turn", 1),
    U_TURN("1 target — U-turn", 1),
    MOVE_TO_STOP("1 target — move to stop", 1),
    STOP_TO_MOVE("1 target — stop to move", 1),
    MOVE_STOP_MOVE("1 target — move to stop to move", 1),
    HEAD_ON_U_TURNS("2 targets — head-on, then U-turn away", 2),
    FIVE_TARGET_CLUSTER("5 targets — clustered with hard maneuvers", 5),
    FOUR_WAY_CROSSING("4 targets — simultaneous crossing", 4),
    COORDINATED_SWITCHBACK("3 targets — coordinated switchbacks", 3),
    OVERTAKE_AND_SPLIT("3 targets — overtake and hard split", 3),
    MERGE_AND_FAN("4 targets — merge, cluster, and fan out", 4),
    SINGLE_TARGET_BLACKOUT("1 target — straight through blackout", 1, 15 * 60),
    MULTI_TARGET_BLACKOUT("5 targets — staggered blackout crossing", 5, 15 * 60),
    MOVE_STOP_BLACKOUT_DEPARTURES("11 targets — move-stop blackout departures", 11, 15 * 60),
    AIRPORT_BLACKOUT("13 targets — airport hangar blackouts", 13, 20 * 60);

    private final String displayName;
    private final int targetCount;
    private final int defaultDurationSeconds;

    ScenarioPreset(String displayName, int targetCount) {
        this(displayName, targetCount, PresetScenarioParameters.MINIMUM_DURATION_SECONDS);
    }

    ScenarioPreset(String displayName, int targetCount, int defaultDurationSeconds) {
        this.displayName = displayName;
        this.targetCount = targetCount;
        this.defaultDurationSeconds = defaultDurationSeconds;
    }

    public int targetCount() {
        return targetCount;
    }

    public boolean isUserGenerated() {
        return this == USER_GENERATED;
    }

    public int defaultDurationSeconds() {
        return defaultDurationSeconds;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
