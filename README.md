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
- **Clear path** erases the trajectory belonging to the target currently selected
  in **Target telemetry**. If a scenario is running or paused, playback is reset
  first so the edit applies immediately.
- Use the mouse wheel or **+ / −** buttons to zoom from a global view down to a roughly one-square-kilometer view. Latitude/longitude axes continue into decimal degrees, while a metric scale bar and viewport dimensions provide local distance context. Right-drag to pan; **World view** resets the map view.
- **Geography detail** overlays bundled Natural Earth 1:10m coastlines, land
  borders, and rivers once the map is zoomed in.
- Draw directly in the companion profile window to set velocity and altitude over normalized scenario time.
- **Run scenario** starts from time zero. **Pause** freezes or resumes it.
- **Reset** rewinds the scenario to `t = 0` and leaves it paused; press **Resume** to continue from the start.

The thin dashed line is the complete planned trajectory. The bold solid line is the target's recent history.

## Pre-generated maneuver scenarios

The **Scenario** strip in the main window can replace the editable scenario with
a duration-scaled preset. Inputs set the latitude/longitude center, ensemble
average speed, WGS-84 ellipsoidal altitude, and runtime in `minutes:seconds`.
Preset runtime must be at least `05:00`; multi-target speeds receive a small
deterministic spread centered on the entered average.

Included presets cover hard left/right turns, a U-turn, move-to-stop,
stop-to-move, move-stop-move, a head-on pair that U-turns apart, a clustered
five-target encounter, a simultaneous four-way crossing, coordinated
switchbacks, overtake-and-split, and merge/cluster/fan-out. Paths are generated
on WGS-84 surface geometry and scaled so every target shares the requested
scenario duration.

While a preset is selected, adding targets, clearing paths, map drawing, and
profile editing are locked so preset targets cannot be removed or structurally
changed. Select **User generated** to discard the preset, create one blank target,
and restore normal editing.

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

## MATLAB track recording

The **Track recording** strip in the main window accepts a parent output folder.
Arm **Record** before starting a scenario; its dot glows bright red while data is
being written. Every recorded run creates its own unique
`scenario_yyyy-MM-dd_HH-mm-ss_SSS` subfolder under that parent.

Each track is stored as one `TRK-*.csv` table with a fixed 93-column schema:
track ID, scenario time, an `updated` Boolean, the fused ECEF 9D state
`[x y z vx vy vz ax ay az]`, and the complete 9x9 covariance in row-major
columns `p_00` through `p_88`. Only measurement initialization/update events are
written, so every exported row has `updated=true`; coast-only UI predictions are
not recorded. Files are UTF-8 CSV with decimal points and can be loaded directly
with MATLAB `readtable`. Each run folder also contains a `README.txt` with a
state/covariance import example.

## Map imagery

The offline Plate Carrée background is NASA Earth Observatory's **Blue Marble:
Land Surface, Shallow Water, and Shaded Topography**. Zoomed views add bundled
Natural Earth 1:10m vector coastlines, borders, and rivers, which remain sharp
independently of the raster resolution. The detail layer loads locally on first
use and can be toggled from the toolbar.

No map service or internet connection is used at runtime. The vector resources
add about 20 MB to the project, rather than requiring a global street-level tile
archive. They provide geographic outlines rather than street/satellite imagery.
Exact source and usage information is recorded in
`src/main/resources/maps/ATTRIBUTION.md`.
