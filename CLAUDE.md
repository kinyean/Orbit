# Orbit-project — Claude context

An Inter-Satellite Remote Proximity Operations (RPO) visualization and
simulation platform.

@./docs/Software Requirements Specification.md
@./docs/architecture-and-roadmap.md
@./docs/decisions.md
@./docs/personas.md
@./docs/use-cases.md
@./docs/user-stories.md
@./docs/glossary.md
@./docs/risks.md
@./docs/acceptance-criteria.md

Doc map: SRS is the authoritative **WHAT**. `architecture-and-roadmap.md` is
the **HOW + WHEN**. `decisions.md` is the **WHY**. `personas.md` is
**WHO**. `use-cases.md` is **HOW THEY USE IT** (workflows + UX patterns).
`user-stories.md` is the **BACKLOG** (story-by-story, phase-by-phase).
`glossary.md` is the **DOMAIN VOCABULARY**. `risks.md` is **WHAT COULD GO
WRONG**. `acceptance-criteria.md` is **WHAT "DONE" LOOKS LIKE** per phase.
The previous public-tracker product plan was retired in the 2026-05-28 SRS
pivot — see `decisions.md` "Superseded" section for the carried-over
rationale.

## Current phase
**Phase 8 complete (8A/8B/8C) — environment & events.** Backend 152 tests green, frontend
type-check + build green. The scene is now *environmental*, riding the Phase-7 architecture
exactly (sampled-trajectory `analysis/` computers, additive `scenario-relative` fields with
`VERSION` still `"1"`, forward-additive `ScenarioBody` schema bump, single audited
`ScenarioService`). See [phase-8-plan.md](docs/phase-8-plan.md), Decision 25.
- **8A — Sun/Moon + lighting + eclipse.** Reuses Orekit `CelestialBodyFactory` Sun/Moon
  (already in the force model — no new dep); samples each body's **direction in the
  chief-LVLH scene** (`FrameService.directionInLvlh`, rotation-only — R15) → streamed
  stride-4 `sunVector`/`moonVector`. The proximity view drives a real `DirectionalLight`
  from it (correct Earth day/night terminator; ambient/hemisphere dropped low), resolving
  the R17 flat-lighting hole. `analysis/EclipseEventComputer` detects conical umbra/penumbra
  in **geocentric ECI** from per-craft positions captured for free in the existing loop
  (`SampledGeocentricCraft` vs the LVLH `SampledCraft` — frame split at the type level);
  streamed `eclipses`, drawn as timeline bands + per-craft material dimming
  (`spacecraftModel.setEclipse`, US-ENV-03 / UC-5).
- **8B — intra-scenario conjunctions + constraints + timeline events.** Schema **v5** (v4
  was measured ephemeris) adds a per-role `List<Constraint>` + top-level
  `missDistanceThresholdM`. `analysis/ConjunctionEventComputer` (pairwise LVLH range +
  golden-section refine **on the samples**) → `conjunctions`. `analysis/ConstraintChecker` →
  **sun-keep-out** (Sun↔boresight angle, completes UC-4 step 7; on a measured chief uses its
  real attitude) + **approach-corridor** (target outside a cone about the host ram axis
  within range) → `violations`. Audited via `addConstraint`/`removeConstraint`/
  `setMissDistanceThreshold` (new audit actions). New `EnvironmentPanel.tsx`; `Timeline`
  conjunction ticks + violation marks.
- **8C — catalog conjunction screening (UC-7).** `analysis/ScreeningService` +
  `POST /scenarios/{id}/screening?thresholdKm=…`: propagate the scenario craft over the
  window, screen vs the full live SGP4 catalog with a two-stage prune (radial-shell band →
  fine sampled closest-approach + golden-section, parallelised), returning a sorted list +
  CSV. A **snapshot** (catalog refreshes ~6 h — tagged with the run instant, R11 caveat).
`QuaternionSamples.rotate` promoted to a shared util; `encodeRelative` grew five additive
trailing params. `gen:api` regenerated (constraint + miss-distance + screening REST; stream
fields stay WebSocket-only). Verified on the dev stack: WS frame carries unit
`sunVector`/`moonVector` + eclipses + a conjunction + violations on `VERSION "1"`; constraint
add → 200, bad angle / missing sensor → 422; screening 64919 → 61 conjunctions below 50 km,
sorted, named. **Deferred (Decision 25):** plume impingement; gimbaled sensors /
frustum FOV (from Phase 7); GPU-depth occlusion; eclipse/conjunction annotations on the
**global-view** CZML. **Phase 9 next** — advanced maneuvers & analysis (roadmap §9).

**Measured-data ingestion — slices 1 & 2 complete (2026-06-22; feature track, not a roadmap
phase).** Real flight telemetry (TELEOS-2 "Whole-Orbit Data" CSVs: measured GNSS ECI
pos/vel + ADCS quaternions) imports as a scenario whose chief is the measured craft
(read-only truth). `io/WodCsvReader` (streaming parse) → immutable content-hashed
`MeasuredDataset` (`measured_dataset` table, V5; samples OUT of the jsonb body) →
`InitialState{kind:"ephemeris", datasetId}` (`ScenarioBody` schema **v4**) → served via an
Orekit tabulated `Ephemeris` in `ScenarioStreamService.prepareEphemerisRole` (the
sampling/stream pipeline is unchanged — "measured" is a per-ROLE source, not a `Fidelity`).
Server-path import (`POST /scenarios/import/measured {path,noradId?}`, path constrained to
`orbit.import.allowed-root`); `update()` merges so editing preserves the ephemeris chief.
**Slice 2 — measured attitude:** the reader also picks up `EST_ATTD_Q1..Q4_8` as a parallel
attitude series (codec v2 behind a backward-compatible sentinel); `AttitudeProfile.mode="measured"`
(set on the chief at import) is **SLERP-streamed through the existing `"fixed"` path** (`bodyAttitude`
+ shared `prop/QuaternionSamples`), so the craft flies its real orientation. The WOD quaternion
convention was **resolved empirically + pinned** (scalar-last Q4=w, body→ECI ⇒ identity reorder;
`prop/MeasuredAttitude` w/ `MeasuredAttitudeTest`; flippable in one place, physical direction confirmed
visually — R20). Frontend: a toggleable **body-axis triad** (`spacecraftModel.setAxesVisible` + a
"Body axes" control) makes orientation legible, legend reads "measured". Backend **126 tests green**,
frontend green; verified end-to-end (570 MB → ~3.2 s; orbit radius holds ~6953 km; chief
`attitude.mode=measured`, the WS frame carries the chief's varying measured `att`). **Gotcha:** keep
`EPHEMERIS_INTERP_POINTS = 2` — higher overshoots between nodes (Runge → 1e11 km orbit). **Slice 3 next:**
measured deputies (real RPO pair — needs a 2nd dataset, R19) / numerical handoff / OEM-AEM readers /
browser upload. See [measured-data-plan.md](docs/measured-data-plan.md), Decision 26.

Per-phase detail lives in `docs/phase-*-plan.md` and the rationale in
[decisions.md](docs/decisions.md); this is just the map of what exists:

- **Phase 1** — dual-container dev env (Spring Boot + Postgres + Flyway + frontend),
  OpenAPI-generated client, Spring Security pipeline (stub).
- **Phase 2** — Orekit 13.1.5 SGP4 core + `FrameService` (ECI/ECEF/geodetic);
  **catalog mode**: one shared SGP4 pass over ~15.5k sats broadcast as gzip CZML on
  `/stream/catalog`; globe consumes it (click-inspect, double-click focus, filters,
  search). [phase-2-plan.md](docs/phase-2-plan.md).
- **Phase 3** — 3A: scenario CRUD + immutable versioning + audit through one
  `ScenarioService` (chief + deputies, frozen-TLE jsonb bodies). 3B: numerical
  propagator (DP8(7), J4+, drag, SRP, third-body) + LVLH/RIC frames + fidelity
  dispatch (backend-only). [phase-3-plan.md](docs/phase-3-plan.md),
  [phase-3b-plan.md](docs/phase-3b-plan.md), Decisions 19–20.
- **Phase 4** — 4A: one authoritative clock (single rAF `clockEngine` writer) +
  per-scenario `/stream/scenario/{id}` CZML stream (precompute-once); the globe
  plays a loaded scenario. 4B: three.js proximity view (chief-LVLH) +
  `scenario-relative` stream; both viewports lockstep on one socket.
  [phase-4-plan.md](docs/phase-4-plan.md).
- **Phase 5** — relative readout (distance/range-rate/R-I-C) + backend closest
  approach + a distance-vs-time graph (`DistanceChart`, Table|Graph tab, no-dep SVG,
  windowed/filterable — Decision 22); impulsive ΔV maneuvers (`ScenarioBody` schema
  v2, audited, numerical re-propagation via Orekit `ImpulseManeuver`, glyphs + Σ|ΔV|
  budget); CW fidelity (`CwPropagation`) + Hohmann/Lambert templates.
  [phase-5-plan.md](docs/phase-5-plan.md).
- **Phase 6** — proximity scene: procedural spacecraft models + GLTF-swap seam +
  fixed-pixel marker LOD (`proximity/spacecraftModel.ts`); derived ram/LVLH
  orientation (`proximity/orientation.ts`, estimated until Phase 7 attitude);
  past/predicted `Line2` trajectory ribbons (`proximity/ribbons.ts`); camera modes
  (`proximity/cameraModes.ts`); Earth backdrop from the additive `chiefRadiusM`
  field (`proximity/earthBackdrop.ts`). [phase-6-plan.md](docs/phase-6-plan.md),
  Decision 23.
- **Phase 7** — sensors & FOV: `Sensor`/`Fov`/`Mount`/`AttitudeProfile` in
  `ScenarioBody` (schema v3); backend-authoritative modeled attitude
  (`FrameService.bodyQuaternionInLvlh` LVLH basis, streamed as a quaternion); translucent
  FOV volumes + sensor-frame camera (`proximity/sensors.ts`, `cameraModes.ts` `sensor`
  mode, `orientation.ts` `bodyOrientationAt`); acquisition/loss-of-sight detection
  (`analysis/SensorEventComputer` — in-FOV + range + Earth occlusion, computed from the
  rendered samples in the LVLH scene, deterministic) streamed in `events` and drawn as
  timeline AOS/LOS windows; `SensorPanel.tsx` (+ type presets) on the audited path.
  [phase-7-plan.md](docs/phase-7-plan.md), Decision 24.
- **Phase 8** — environment & events: Sun/Moon LVLH directions (`FrameService.directionInLvlh`,
  reusing Orekit Sun/Moon) → real `DirectionalLight` + Earth terminator (resolves R17 flat
  lighting); conical eclipse (`analysis/EclipseEventComputer`, geocentric `SampledGeocentricCraft`)
  → timeline bands + craft dimming; intra-scenario conjunctions (`ConjunctionEventComputer`) +
  sun-keep-out/approach-corridor constraints (`ConstraintChecker`, `ScenarioBody` schema v5 +
  `missDistanceThresholdM`, audited) → `conjunctions`/`violations` + timeline marks +
  `EnvironmentPanel.tsx`; catalog conjunction screening (`ScreeningService`,
  `POST /scenarios/{id}/screening`, two-stage shell-prune + fine refine) → sortable table + CSV.
  All `scenario-relative` additions are additive (`VERSION="1"`). [phase-8-plan.md](docs/phase-8-plan.md),
  Decision 25.

Invariants to preserve (see `decisions.md`): one streaming contract, `VERSION="1"`,
additive only (R12); every state frame-tagged via `FrameService` — relative velocity
uses the rotating LVLH transform, never the single-epoch `toRelativeState` (R15);
deterministic propagation, byte-identical reruns (R11); one `currentTime` writer;
ephemeris in stream buffers, not Zustand (Decision 5); all scenario edits (incl.
maneuvers) go through the single audited `ScenarioService` path (Decision 16).

## Stack
- **Frontend:** React + TS strict + Vite + CesiumJS (global view) + three.js
  (proximity view, new in Phase 4).
- **Frontend state:** Zustand, per-slice subscriptions. UI/control state only —
  ephemeris lives in stream buffers.
- **Backend:** Java + Spring Boot.
- **Propagation:** Orekit — SGP4, high-fidelity numerical (DP8(7), J4+, drag,
  SRP, third-body), Clohessy-Wiltshire. Per-scenario fidelity. Deterministic.
- **Persistence:** PostgreSQL (`jsonb` scenario bodies, versioned, audit log).
- **Streaming:** REST (OpenAPI 3.x) + WebSocket (CZML for global view,
  compact relative-state for proximity view).
- **Deployment:** containerized; Docker Compose for dev; cloud + on-prem.

## Architecture rules
- Frontend never propagates; all physics lives behind the streaming contract.
  See Decision 9.
- Every state vector carries a frame tag; all conversions go through one
  canonical utility wrapping Orekit's frames. See Decision 12.
- One authoritative simulation clock — frontend owns playback control,
  backend is authoritative on state. See Decision 11.
- Backend serves **two modes**: a shared catalog stream (one SGP4 pass over
  all ~14,500 active sats, broadcast to every viewer) and per-user scenario
  streams (≤10 spacecraft, selected fidelity). See Decision 13.
- Scenario data model is **chief + deputies**. The catalog browser is the
  primary composition path (click satellite → add to scenario). See
  Decisions 13, 14.
- Build professional-grade from day one — auth pipeline real (stub
  initially), `scenario.owner` exists from the first migration, scenario
  mutations go through one service layer, propagation is deterministic
  (seeded + pinned). See Decision 16.
- TypeScript strict mode on. Never disable.
- Per-slice Zustand subscriptions.

## Conventions
- Ask before adding dependencies.
- Commit only when the user explicitly asks — never auto-commit.
- Tag frame in code wherever state vectors live (names, types, comments):
  `ECI` / `ECEF` / `LVLH` / `RIC` / `body`.

## Build commands

### Full stack (Docker Compose)
- `docker compose up -d --build` — backend + Postgres + frontend (build on first run).
- `docker compose down` — stop services (preserves db volume).
- `docker compose down -v` — stop and wipe db.
- Backend: http://localhost:8081  ·  Frontend: http://localhost:5174
  (host ports 8080 / 5173 are taken by other services on this shared box).
- The backend mounts `/mnt/disk_large/shared_folder → /shared_folder:ro` (measured-data
  import root, `orbit.import.allowed-root`). Import a measured CSV:
  `curl -X POST localhost:8081/scenarios/import/measured -H 'Content-Type: application/json'
  -d '{"path":"/shared_folder/<…>.csv"}'` (see [measured-data-plan.md](docs/measured-data-plan.md)).
  `_teleos_samples/` holds throwaway sample CSVs (gitignored).

### Frontend (`frontend/`)
- `npm run dev` — Vite dev server on port 5173 (inside container; 5174 from host).
- `npm run type-check` — `tsc --noEmit`.
- `npm run gen:api` — regenerate the OpenAPI client from the running backend.
- **When `package.json` changes**, rebuild the image AND drop the anon volume
  or the container keeps stale deps. See "dep-change workflow" in
  [frontend/README.md](frontend/README.md). The browser-facing API base is
  `/api` (proxied by Vite to the backend); never use absolute backend URLs in
  client code.

### Backend (`backend/`) — Gradle, Spring Boot 3.5, Java 21
- `./gradlew bootRun` — start backend locally (requires local JDK 21 + a Postgres reachable at $DB_URL).
- `./gradlew build` — full build incl. tests.
- `./gradlew build -x test` — build without tests (faster, no DB needed).
- Set `JAVA_HOME=$HOME/jdk-21.0.11+10` and prepend to `PATH` (already in `~/.bashrc`).

### Stack (TBD — Phase 1)
- `docker compose up` — full local dev environment (frontend + backend + db).
