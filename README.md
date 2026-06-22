# ECEF Target Tracker

A Java/Swing target-scenario editor built around WGS-84 Earth-Centered, Earth-Fixed (ECEF) coordinates. It supports multiple targets, free-hand and segmented trajectories on a Plate Carrée Earth map, editable velocity and altitude profiles, zoom/pan, and run/pause animation.

Trajectory control points and animated positions are stored in ECEF meters. Between control points, motion follows a WGS-84 ellipsoidal geodesic rather than a straight Cartesian chord. At every playback step the requested profile altitude is applied as WGS-84 ellipsoidal height before conversion to ECEF, so a constant-altitude path follows the Earth instead of cutting through it.

## Run in IntelliJ IDEA

1. Open this directory as a project (IntelliJ will detect `pom.xml`).
2. Select a Java 17 or newer SDK.
3. Run `com.targettracker.Main`.

No third-party runtime dependencies are required.

The project also includes a small no-dependency model smoke test in `src/test/java`.

## Controls

- **New target** creates and selects another target.
- **Free-hand**: press and drag on the Earth map.
- **Segmented**: click to add vertices; double-click or press **Finish path** to complete.
- Use the mouse wheel or **+ / −** buttons to zoom from a global view down to a roughly one-square-kilometer view. Latitude/longitude axes continue into decimal degrees, while a metric scale bar and viewport dimensions provide local distance context. Right-drag to pan; **World view** resets the map view.
- Draw directly in the companion profile window to set velocity and altitude over normalized scenario time.
- **Run scenario** starts from time zero. **Pause** freezes or resumes it.
- **Reset** rewinds the scenario to `t = 0` and leaves it paused; press **Resume** to continue from the start.

The thin dashed line is the complete planned trajectory. The bold solid line is the target's recent history.

## God sensor measurements

The separate **Sensor…** window opens at startup and can be reopened from either
the toolbar or telemetry sidebar. It configures an omniscient sensor with a look
interval, first-look offset, 3D ECEF position and velocity standard deviations,
probability of detection, and displayed measurement history. Every text box is
editable; focusing one selects its current value for easy replacement. Valid
changes apply immediately, including safe rescheduling of the next look during
playback.

At each time `offset + n × interval`, every target receives an independent Pd
coin flip. A successful detection adds independent zero-mean Gaussian noise to
the three ECEF position axes and three ECEF velocity axes. The resulting
covariances are isotropic diagonal matrices, `position σ² I` and `velocity σ² I`.
The map shows successful detections as white X markers and retains the requested
number of recent measurements per target (0–10).

## IMM tracking

The separate **IMM…** window opens at startup and can be reopened from the
toolbar or telemetry sidebar. CV and CA are enabled by default and can be
toggled independently (at least one must remain active). CV uses discretized continuous white-noise acceleration with
per-axis process covariance
`q [[dt³/3, dt²/2], [dt²/2, dt]]`. CA uses the analogous continuous
white-jerk discretization over position, velocity, and acceleration.

The window also configures the Mahalanobis association gate, no-detection
timeout, maximum one-sigma 3D position-covariance radius, and a dynamically
square row-stochastic model transition matrix. Association globally sorts all
gated track/measurement pairs by Mahalanobis distance, then greedily assigns
the lowest-cost non-conflicting pairs.

Tracks are filtered only at measurement times. Between measurements the UI
predicts directly from each track's last updated state and time; it never feeds
coast predictions back into the filter. Active tracks show a colored square,
tail, and one-sigma East/North covariance ellipse. Tracks exceeding either
break threshold leave association and remain visible in grey as dead tracks.

## Map imagery

The offline Plate Carrée background is NASA Earth Observatory's **Blue Marble:
Land Surface, Shallow Water, and Shaded Topography**. The exact source and usage
information are recorded in `src/main/resources/maps/ATTRIBUTION.md`.

At the deepest zoom levels, latitude/longitude placement, trajectories, and the
metric scale remain precise, but the bundled 2048-pixel raster naturally becomes
coarse; it is global context rather than street-level imagery.
