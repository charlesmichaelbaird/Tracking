# ENU Target Tracker

An initial Java/Swing target-scenario editor. It supports multiple targets, free-hand and segmented trajectories, editable velocity and altitude profiles, and run/pause animation in a generalized East-North-Up (ENU) frame.

## Run in IntelliJ IDEA

1. Open this directory as a project (IntelliJ will detect `pom.xml`).
2. Select a Java 17 or newer SDK.
3. Run `com.targettracker.Main`.

No third-party runtime dependencies are required.

The project also includes a small no-dependency model smoke test in `src/test/java`.

## Controls

- **New target** creates and selects another target.
- **Free-hand**: press and drag on the ENU canvas.
- **Segmented**: click to add vertices; double-click or press **Finish path** to complete.
- Draw directly in the companion profile window to set velocity and altitude over normalized scenario time.
- **Run scenario** starts from time zero. **Pause** freezes or resumes it.

The thin dashed line is the complete planned trajectory. The bold solid line is the target's recent history.
