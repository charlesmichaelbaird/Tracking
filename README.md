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
- Use the right-side control buttons to swap the embedded section between
  **IMM**, **Sensor Parameters**, **Motion Profiles + Target Telemetry**,
  **Targets**, and **Scenario**. Motion profiles can be drawn directly on their
  charts over normalized scenario time.
- **Pre-compute scenario** runs the complete sensor and tracker calculation as
  fast as possible, without waiting for scenario wall-clock time.
- **Replay scenario** visually plays the cached result. **Pause** freezes or
  resumes replay, and **Reset** rewinds it to `t = 0` in a paused state.
- The ruler below the world map shows replay progress and can be dragged backward
  or forward as soon as pre-computation finishes, including during playback.
- The top mode controls switch between **Scenario Generation** and **Analysis
  Mode**. Generation exposes recording and pre-computation; Analysis replaces
  the recording strip with the saved-run folder loader.

The thin dashed line is the complete planned trajectory. The bold solid line is the target's recent history.

## Pre-computation and timeline replay

**Pre-compute scenario** advances the sensor and IMM at 0.1-second steps, then
rewinds to `00:00` with the replay ruler enabled. Seeking restores ground-truth targets, recent truth history,
time-filtered measurements, track means/covariances/dead state, and track tails
at the selected time.

Visual replay only reads these cached frames; it never reruns the tracker or
appends recording data. Seeking is temporarily disabled only while the fast
pre-computation is actively writing files.

## Pre-generated maneuver scenarios

The embedded **Scenario** section can replace the editable scenario with
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

The embedded **Sensor Parameters** section configures an omniscient sensor with a look
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

The embedded **IMM** section provides the tracker controls. CV and CA are enabled by default and can be
toggled independently (at least one must remain active). CV uses discretized continuous white-noise acceleration with
per-axis process covariance
`q [[dt³/3, dt²/2], [dt²/2, dt]]`. CA uses the analogous continuous
white-jerk discretization over position, velocity, and acceleration.

The section also configures the Mahalanobis association gate, no-detection
timeout, maximum one-sigma 3D position-covariance radius, and a dynamically
square row-stochastic model transition matrix. Association globally sorts all
gated track/measurement pairs by Mahalanobis distance, then greedily assigns
the lowest-cost non-conflicting pairs.

Tracks are filtered only at measurement times. Between measurements the UI
predicts directly from each track's last updated state and time; it never feeds
coast predictions back into the filter. Active tracks show a colored square,
full-lifetime untruncated tail, and one-sigma East/North covariance ellipse. Tracks exceeding either
break threshold leave association and remain visible in grey as dead tracks.

## MATLAB track recording

The **Track recording** strip in Scenario Generation mode accepts a parent output folder.
Arm **Record** before pressing **Pre-compute scenario**; its dot glows bright red
while data is being written. Every recorded computation creates its own unique
`<scenario-name>_yyyy-MM-dd_HH-mm-ss_SSS` subfolder under that parent. Preset
names are sanitized for the filesystem; manual runs use `user_generated`.

Each run contains three data subdirectories:

- `ground_truth_data`: one `TGT-*.csv` per target, sampled every 0.1 seconds,
  containing target ID, time, and the ECEF 9D truth state
  `[x y z vx vy vz ax ay az]`.
- `track_data`: one `TRK-*.csv` per track with track ID, time, `updated`, the
  fused ECEF 9D state, and the complete 9x9 covariance in row-major columns
  `p_00` through `p_88`. Integer-second samples are always retained, with
  fractional-time update rows added when needed.
- `measurement_data`: `measurements.csv` containing sensor ID, source target ID,
  associated track ID, time, 6D ECEF mean, complete 6x6 covariance in `r_00`
  through `r_55`, and position/velocity uncertainty. The association field lets
  offline smoothing apply each measurement to the same track that used it.

Track files no longer duplicate measurement values. Files are UTF-8 CSV with
decimal points and can be loaded directly with MATLAB `readtable`. Each run also
contains `README.txt` import examples and `scenario_metadata.properties` with
the scenario name, duration, and format version.

## Analysis mode and saved replay

Select **Analysis Mode** to disable generation-time **Pre-compute** and **Replay**
buttons. Enter or browse to the top-level recording directory, choose one of its
recorded scenario subfolders, and press **Load scenario**. The loader supports
the three-directory format as well as both earlier flat recording formats.

Loading rebuilds truth, track frames, full track tails, covariance ellipses,
scenario duration, and measurement markers without rerunning the sensor or IMM.
The map automatically focuses on the full recorded scenario extent using the
same bounding logic as generated presets. The timeline is immediately seekable.
The replay is loaded at `t = 0` in a paused state, so **Resume**, **Pause**,
**Reset**, and timeline scrubbing operate on it like a fresh pre-computation.

The common **World-view layers** row is available in both modes. The map grid,
ground truth, and measurements can each be toggled independently. The truth and
measurement sliders select the newest fraction of history to draw: all the way
left shows no history and all the way right shows the complete history through
the selected time.

## Track stitching analysis

After an Analysis Mode run loads successfully, **Track Stitching Analysis**
switches the main display into an embedded stitching view. Timestamp tabs are
created only at sensor measurement times that contain at least one eligible
coasting track and one recently formed distinct track. Selecting a tab seeks
the normal replay timeline to that scenario time without changing the current
map pan or zoom. Bright red timeline markers show all candidate stitch times,
and the view control can show all tracks, muted non-candidates, or show only the
candidate tracks/targets. Dead candidate tracks remain grey and labeled in the
map. The configuration section supplies minimum and
maximum coast/new-track windows, optional dead-track eligibility, and the
prediction/retrodiction time-bank resolution. Pressing **Run stitching
analysis** also computes separate Hungarian assignments for NLL, Mahalanobis,
static/uniform NLLR, and learned-spatial NLLR costs.

For every old/new segment pair, the analysis reports simple and kinematic
midpoints, a position Mahalanobis time-bank estimate, and a truth-reference
time found by minimizing the RMS position error of both propagated segments.
Each bank sample is evaluated independently from fixed measurement-updated
anchors: the old state is predicted forward from its last updated record, and
the new state is retrodicted backward from its latest updated record available
at the candidate event. Stitch-gap propagation uses the tracker's observable
constant-velocity/DCWNA transition with the signed interval to the requested
join time, so latent acceleration covariance is not projected into position
uncertainty. Coast/display predictions and intermediate associated measurements
are not reused as smoothing updates inside the stitching bank.

At each candidate time, the 3D position innovation is `x_old - x_new` and its
covariance is `P_old + P_new` for the position block. The time bank uses the
canonical Mahalanobis distance, while each timing variant receives both the
canonical multivariate Gaussian negative log likelihood and the corresponding
Mahalanobis distance.
Alternative Hypothesis mode compares the same Gaussian likelihood against a
static extraneous spatial density and a learned target-birth spatial density.
The scenario summary remains above a combined metrics table with one row per
old/new pair and timing estimate. Optimal assignments appear with their
costs on the right. Each timestamp tab provides a locally focused Plate Carrée
view driven by the main map and replay timeline.

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
