# Orbit — user guide

A task-oriented walkthrough of the Orbit Inter-Satellite RPO platform for
mission planners, flight-dynamics engineers, GN&C analysts, and operators.
Sections map to the [use cases](./use-cases.md) (UC-n) and the SRS clauses they
implement. Domain terms are defined in the [glossary](./glossary.md); an
abridged glossary lives in-app behind the **?** button.

Contents: [Getting started](#1-getting-started) · [The interface](#2-the-interface)
· [Browse the catalog](#3-browse--inspect-the-catalog) · [Build a scenario](#4-build-a-scenario)
· [Playback](#5-playback--the-shared-clock) · [Relative motion](#6-relative-motion--readouts)
· [Maneuvers](#7-maneuver-planning) · [Sensors & link budget](#8-sensors-fov--link-budget)
· [Environment & events](#9-environment--events) · [Monte Carlo](#10-monte-carlo-dispersion)
· [Measured data](#11-measured-flight-data) · [Fidelity & reproducibility](#12-fidelity-validation--reproducibility)
· [Export & sharing](#13-export--sharing) · [Performance & troubleshooting](#14-performance--troubleshooting)

---

## 1. Getting started

**Run the stack** (Docker Compose, from the repository root):

```bash
docker compose up -d --build          # backend + Postgres + frontend (stub auth)
# or, with a real OIDC identity provider (Keycloak):
docker compose -f docker-compose.yml -f docker-compose.oidc.yml up --build
```

Open **http://localhost:5174**. In stub mode (the default) you are a fixed dev
user — no sign-in. With the OIDC overlay you get a sign-in screen; the dev
realm ships three users (`maya/maya`, `frank/frank`, `gita/gita`).

**First run.** Every user is seeded with five **demo scenarios** (SRS §5.6.1) —
open the **Scenarios** panel on the left and load one:

| Demo | Shows |
|---|---|
| close formation (NMC) | a bounded sub-km circumnavigation — the signature proximity-view shape |
| rendezvous (co-orbit approach) | a 120 km chaser; run the rendezvous template on it (§7) |
| inspection & link budget (sensors) | a chief imager acquiring/losing the deputy + an SNR band (§8) |
| eclipse & lighting (day-night) | umbra/penumbra timeline bands + Sun-consistent dimming (§9) |
| V-bar station (hold & glideslope start) | a deputy parked 2 km behind the chief — the close-range template launchpad (§7) |

A one-time hint points here; press **?** (top right) any time for the quick
tour, controls reference, and mini-glossary (§5.6.2).

## 2. The interface

- **Global view** (left, Cesium): the Earth with the full live catalog
  (~15,000 satellites) and, when a scenario is loaded, its chief + deputies
  with orbit trails.
- **Proximity view** (right, appears with a scenario): the chief-centered
  relative frame (LVLH). Axes: **R** radial-out (red/x), **I** in-track
  (green/y), **C** cross-track (blue/z); 1 scene unit = 1 m. Toggle it with
  *⊞ Proximity view*; drag the divider to resize.
- **Timeline** (bottom): the scenario window with event annotations — maneuver
  epochs, eclipse bands, sensor AOS/LOS windows, conjunction ticks, constraint
  violations, SNR band. Hover for details; drag to scrub.
- **Panels**: Scenarios and Filters are fixed on the left; Maneuvers, Sensors,
  Environment, Monte Carlo, Audit, and Export are draggable cards (drag the
  header, ▾ collapses, edges resize; positions persist).
- **Top bar**: search (name or NORAD id, Enter to fly there), your identity
  (OIDC mode), **?** help, backend status chip.

## 3. Browse & inspect the catalog

(UC-1 steps 1–3; §3.8)

- The catalog stream is shared and live. **Single-click** a dot to *inspect*
  it — the info panel shows name, NORAD id, position, altitude, period,
  inclination — and to toggle its **orbit path** (a pulsing marker keeps it
  findable). **Double-click** (or search + Enter) to *focus*: the camera
  recenters and tracks it, zoom preserved. *⌂ Reset view* releases tracking.
- **Filters** show/hide constellations (Starlink, OneWeb, GPS, Galileo,
  BeiDou, Iridium); state persists.
- **Time-travel the live sky**: without a scenario, the clock follows real
  time (● LIVE). Step/scrub up to ±12 h — the globe fetches a snapshot at that
  instant; play from there up to 100×. ● LIVE returns to now.

## 4. Build a scenario

(UC-1; §3.10)

1. Inspect a satellite → **Set as chief** (the relative-frame origin).
2. Inspect more → **Add as deputy** (up to 10 craft total).
3. Set the **time range** and **fidelity** in the composer (see §12), then
   **Save** (first save names it).
4. Every later edit — time range, maneuvers, sensors, constraints — creates a
   new immutable **version** with an **audit** entry (who/when/what — see the
   Audit panel).

To add a deputy mid-scenario, use *◎ Show catalog* (positions are snapshot at
the scenario time), click a satellite, add it, and save. Deleting is soft —
history and audit survive.

## 5. Playback & the shared clock

(UC-1/UC-8; §3.3, §3.11)

Both views run in lockstep off one clock. Controls: play/pause, step, reverse,
reset, and a logarithmic rate slider **0.01× – 10 000×**. Drag the timeline to
scrub (≤200 ms to rendered frame, §5.1.3). The scenario window bounds playback;
everything is precomputed on load, so scrubbing is instant and offline.

## 6. Relative motion & readouts

(UC-1 step 6; §3.4)

With a scenario loaded, the **relative readout** shows each deputy's live
distance, range-rate, and R/I/C state, plus its **closest approach** (TCA +
distance, also ticked on the timeline). The **Graph** tab plots
distance-vs-time over the window (span toggle, pan, per-deputy filter, log
axis). In the proximity view, ribbons trace each deputy's past (solid) and
predicted (dashed) path; **Fit** re-frames the camera; camera modes ride the
chief/deputy body frames or a sensor boresight.

## 7. Maneuver planning

(UC-1 step 7, UC-2; §3.5)

All maneuvers apply to **deputies** (the chief is the reference) and go through
the audited path — each insert is a new version, re-propagated automatically.
ΔV totals per deputy (Σ|ΔV|) sit at the top of the Maneuvers panel; glyphs mark
burns in the proximity view near their epochs.

- **Add Δv** — an impulsive burn: epoch + R/I/C components (m/s). Toggle
  **finite burn** to model a real thrust arc (thrust N + Isp s); the burn is
  centred on the epoch and depletes mass by the rocket equation.
- **Hohmann** — two burns to an *absolute* target circular altitude (km).
- **Rendezvous** — two-impulse transfer arriving at the chief at a chosen
  epoch. By default it is **differentially corrected** against the real
  propagators, so the transfer genuinely converges (try it on the rendezvous
  demo; the search endpoint maps ΔV vs arrival × revolutions). A cumulative
  |ΔV| ≥ 5 km/s warning flags an infeasible plan (usually a bad arrival time).
- **Phasing** — close an along-track gap over N revolutions.
- **Close range (CW)**: **NMC** (enter a bounded circumnavigation), **Hold**
  (park at a V-bar/R-bar point), **Glideslope** (constant-rate approach in
  legs + park), **Station-keep** (closed-loop corrective burns). The V-bar
  demo is a ready starting geometry. Templates plan from the scenario start
  (see decisions.md on composability).

## 8. Sensors, FOV & link budget

(UC-4; §3.6, §3.9.4)

In the **Sensors** panel, add a sensor to any craft: type, cone or rectangular
FOV, range band, and a body-fixed boresight (presets: narrow imager, wide
imager, rendezvous lidar). The translucent FOV volume rides the craft's
**attitude** (modeled LVLH by default, a fixed quaternion, or *measured* — see
§11); it lights green while a target is acquired. AOS/LOS windows appear on
the timeline (in-FOV ∧ in-range ∧ Earth line-of-sight). The camera's *sensor*
mode looks along the boresight. Add a **link budget** (EIRP, G/T, frequency,
bandwidth, threshold) to draw the SNR band — red below threshold. The
inspection demo has all of this pre-wired. Body-axis triads (*Body axes* On)
make orientation legible.

## 9. Environment & events

(UC-5, UC-7; §3.7, §3.12)

- **Lighting** is physical: the Sun direction drives the terminator and craft
  illumination; craft dim in **penumbra/umbra** (bands on the timeline — load
  the eclipse demo).
- **Conjunctions** (intra-scenario): pairwise closest approaches below the
  **miss-distance threshold** (Environment panel) tick the timeline.
- **Constraints**: *sun-keep-out* (Sun within a limit of a sensor boresight)
  and *approach corridor* (target outside a cone about the host's ram axis
  within range) — violations mark the timeline.
- **Catalog screening** (UC-7): screen the whole scenario against the full
  live catalog at a km threshold → a sorted table (click a row to scrub to the
  TCA; CSV export). It is a snapshot of the current TLE set, not a
  reproducible artifact.

## 10. Monte Carlo dispersion

(UC-6; §3.12.4–5)

In the **Monte Carlo** panel pick a deputy, set 1-σ initial-state uncertainty
(position/velocity), maneuver execution error (ΔV fraction + pointing), sample
count (≤500 — each sample is a full numerical propagation) and an explicit
**seed**. Run → the proximity view overlays the trajectory cloud and 3σ
covariance ellipsoids. The same seed reproduces the run bit-identically
(§5.4.1).

## 11. Measured flight data

(Decision 26; US-IO-03/04)

Import real telemetry (WOD CSV with GNSS ECI states + ADCS quaternions) from a
server-side path in the Scenarios panel — the imported craft becomes a
**read-only measured chief** flying its recorded trajectory *and attitude*
(legend: "measured"). Add catalog deputies around it to study real RPO
geometry (illustrative — their TLEs are current-epoch). The import root is
constrained server-side (`orbit.import.allowed-root`).

## 12. Fidelity, validation & reproducibility

(UC-3; §3.1, §5.2, §5.4)

Per-scenario **fidelity**: `sgp4` (fast, TLE-analytic), `numerical`
(DP8(7), 16×16 gravity, NRLMSISE-00 drag, SRP, Sun/Moon — the validation
engine), `cw` (Clohessy-Wiltshire close-range; the UI warns beyond its
envelope). Maneuvered deputies always propagate numerically. Propagation is
**deterministic**: the same version produces byte-identical results — the
reproducibility Frank signs off on (see
[validation-conformance.md](./validation-conformance.md)). Every change is
versioned + audited; the Audit panel shows the trail, version history, and
per-version diffs.

## 13. Export & sharing

(UC-3 step 8, UC-8; SRS §4.2)

The **Export** panel:

- **PNG snapshot** (§4.2.3): global, proximity, or both side-by-side, stamped
  with scenario + sim time.
- **MP4 video** (§4.2.3): choose viewport(s), range, speed (sim-s per
  video-s), fps. Rendered **offline frame-by-frame** — smooth output no matter
  the live frame rate, with a sim-time chip. Needs WebCodecs H.264 (Chromium
  recommended); capped at 1800 frames.
- **Events JSON/CSV** (§4.2.2): every event class over the window — sensor
  AOS/LOS, eclipse ingress/egress, conjunctions, constraint violations,
  closest approaches — with epochs, ids, names, and values.
- **CCSDS OEM** (§4.2.1): the propagated ephemeris of every craft (EME2000,
  UTC, one segment per craft; maneuvered deputies carry the real post-burn
  trajectory) — the standard handoff to downstream flight-dynamics tools.
  Exports are recorded in the audit trail (`EXPORT_OEM`) and are byte-identical
  when re-exported from the same version.

Scenario handoff: share the scenario (it's server-persisted and versioned) +
the OEM file; access is per-owner.

## 14. Performance & troubleshooting

- The **⏱** button (stats overlay) opens the performance HUD: live FPS per
  view, scrub latency, last scenario-load time — against the §5.1 targets
  (proximity ≥60 fps, globe ≥30 fps, scrub ≤200 ms, 24 h load ≤5 s). `?perf=1`
  in the URL forces it on.
- *"This scenario can't be streamed"* — a craft leaves the propagation model's
  valid domain (decay, or a maneuver below the surface). Shorten the window or
  revise the maneuver.
- Very long **numerical** windows can exceed the 5 s load target (each load is
  a full propagation) — prefer `sgp4` for multi-week sketches.
- MP4 export button disabled — the browser lacks WebCodecs H.264; use
  Chrome/Edge.
- After changing frontend dependencies, rebuild the image **and** drop the
  anonymous volume (see frontend/README.md).
