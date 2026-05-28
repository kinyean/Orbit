# Architecture & build roadmap

How we build the system specified in
[Software Requirements Specification.md](./Software%20Requirements%20Specification.md).
This document is the **HOW** + **WHEN**. The **WHAT** is the SRS; the **WHY**
lives in [decisions.md](./decisions.md).

The naming throughout:
- **Chief** — the designated reference spacecraft (origin of the LVLH frame).
- **Deputy / deputies** — other spacecraft expressed relative to the chief.
- **Scenario** — the central persistent artifact: chief + deputies + initial
  states + maneuvers + sensors + attitude.
- **LVLH / RIC** — Local Vertical Local Horizontal / Radial-In-track-Cross-track
  frame centered on the chief; the natural frame for proximity analysis.

---

## 1. Component overview

```
┌────────────────────────────────────────────────────────────────┐
│  FRONTEND CLIENT  (static, containerized)                       │
│                                                                  │
│  ┌──────────────────────┐    ┌──────────────────────────────┐   │
│  │ Global view          │    │ Proximity view               │   │
│  │ React + CesiumJS     │    │ React + three.js (LVLH)      │   │
│  │  (Phase-1 scaffold,  │◄──►│  (new)                       │   │
│  │   carried over)      │ same clock + relative-state stream│   │
│  └──────────────────────┘    └──────────────────────────────┘   │
│  Scenario panel · Timeline · Sensor/Attitude controls           │
└──────────────────────────┬──────────────────────────────────────┘
        REST (OpenAPI 3.x) │  WebSocket (CZML + relative state)
┌──────────────────────────┴──────────────────────────────────────┐
│  BACKEND  (Java + Spring Boot, containerized)                    │
│                                                                  │
│  ┌─────────────────────────┐    ┌──────────────────────────┐    │
│  │ Propagation & analysis  │    │ Scenario service         │    │
│  │  Orekit                 │    │  CRUD, versioning,       │    │
│  │  (SGP4 / numerical / CW)│    │  import/export, audit    │    │
│  └─────────────────────────┘    └──────────────────────────┘    │
│  Spring Security pipeline (auth/RBAC seam)                       │
└──────────────────────────┬──────────────────────────────────────┘
                           │ JDBC
                ┌──────────┴───────────┐
                │  PostgreSQL          │
                │  scenarios, versions,│
                │  users, audit_log    │
                └──────────────────────┘
                           ▲
                           │
                  External interfaces:
                  CelesTrak / Space-Track (TLE)
                  Uploaded CCSDS OEM / OPM / AEM files
```

Decoupling rule (SRS §5.3.3): the frontend never propagates. All physics lives
behind the streaming contract.

---

## 2. Backend (Java + Spring Boot + Orekit)

Responsibilities:

- **Propagation.** Orekit-backed multi-fidelity propagation: SGP4/SDP4,
  high-fidelity numerical (DP8(7), gravity field ≥J4, NRLMSISE-00 drag, SRP,
  Sun/Moon third-body), and Clohessy-Wiltshire for close range. Per-scenario
  fidelity selection. Deterministic (seeded, pinned).
- **Frame transforms.** A single canonical utility wrapping Orekit's frames
  with IERS Earth-orientation corrections. All emitted states carry their
  frame tag (SRS §3.2.5).
- **Scenario service.** REST CRUD for scenarios, versioning, ownership, audit
  log. CCSDS OEM/OPM/AEM and TLE import. CZML and OEM export.
- **Streaming.** WebSocket streams two payloads from one propagation result:
  CZML for the global view, compact relative-state for the proximity view.
- **Analysis.** Conjunction detection, sensor acquisition/loss events,
  constraint checks, Monte Carlo dispersion, covariance evolution.
- **Security.** All requests through a Spring Security pipeline; auth/RBAC
  starts as a no-op stub but the pipeline is real from day one.

Module sketch:

```
backend/
├── prop/            Orekit wrappers, fidelity selection, frame utility
├── scenario/        Domain model, persistence, versioning, audit
├── stream/          CZML + relative-state encoders, WebSocket handlers
├── analysis/        Conjunctions, events, Monte Carlo, link budget
├── io/              CCSDS, TLE, Keplerian readers/writers
├── api/             REST controllers, OpenAPI annotations
├── security/        Auth pipeline (stub today, OIDC/SAML-ready)
└── config/          Spring config, 12-factor env binding
```

---

## 3. Frontend (React + Vite + Cesium + three.js)

Responsibilities:

- **Global view (Cesium).** WGS84 Earth, day/night terminator, ground tracks,
  full orbital paths, current positions. Consumes the CZML stream directly.
  Carries over the Phase-1 scaffold ([components/Globe.tsx](../satellite-tracker/src/components/Globe.tsx)).
- **Proximity view (three.js).** Chief-centered LVLH scene. Spacecraft 3D
  models (GLTF) with articulable parts. Sensor FOV volumes as translucent
  geometry. Trajectory ribbons (past = solid, predicted = dashed). Delta-V
  vector annotations at maneuver epochs. Adjustable scale 1 m–100 km.
- **Scenario panel.** List, create, edit, duplicate, version-browse scenarios.
- **Timeline.** Scrub bar with annotated maneuver epochs, eclipse periods,
  sensor acquisition windows, conjunction events.
- **Controls.** Play / pause / step / reset / rate (0.01x–10000x) / reverse.
- **Shared clock.** Single Zustand slice; both views subscribe. The frontend
  owns playback control; the backend is authoritative on state.

Module sketch (extending what exists):

```
frontend/src/
├── views/
│   ├── global/         CesiumJS scene, CZML data source, ground tracks
│   └── proximity/      three.js scene, LVLH camera, spacecraft models, FOV
├── timeline/           scrub bar, event annotations
├── scenario/           panel, CRUD UI, import/export forms
├── stream/             WebSocket client, decoded state buffers, interpolation
├── api/                generated OpenAPI client
├── store/              Zustand slices (clock, selection, layout)
├── lib/                frame helpers, formatting, time-scale UI utils
└── components/         shared UI primitives
```

---

## 4. Data store (PostgreSQL)

Tables (sketch, names will firm up with the schema):

- `users` — id, email, sso_subject, roles
- `scenarios` — id, owner_id, name, latest_version_id, created_at
- `scenario_versions` — id, scenario_id, version_no, author_id, created_at,
  body (`jsonb`: chief, deputies, maneuvers, sensors, attitude, fidelity, time
  range)
- `audit_log` — id, scenario_id, version_id, actor_id, action, timestamp,
  diff_summary
- (later) `runs` — execution records linking a scenario version to its
  computed events / analysis outputs

`owner_id` and `roles` exist on day one even when auth is stubbed (RBAC seam,
Decision 15).

---

## 5. Data flows

### Scenario load
1. UI requests `GET /scenarios/{id}/versions/{v}`.
2. Backend returns the scenario body; UI populates the panel.
3. UI requests a stream for the scenario's time range and fidelity:
   `WS /stream/{scenarioId}?from=...&to=...`.
4. Backend propagates, streams CZML to the global view and relative-state to
   the proximity view, time-tagged.

### Playback / scrub
1. UI advances the clock locally (or sets a new epoch on scrub).
2. UI sends `{kind: 'seek' | 'rate', value: ...}` over the WebSocket.
3. Backend either continues streaming at the new rate / direction, or jumps to
   the requested epoch and resumes.
4. Both views interpolate between received samples for smooth per-frame motion.

### Maneuver evaluation
1. UI edits the maneuver plan; PUTs the new scenario version.
2. UI requests a fresh stream for the updated scenario.
3. Backend re-propagates from the maneuver epoch onward (pre-maneuver state is
   cached); streams updated CZML + relative state.
4. Frontend renders the new transfer path; delta-V vector annotation appears
   in the proximity view; the cumulative delta-V budget updates.

---

## 6. Build roadmap

Phased so each phase ends with a working slice end-to-end. Sequence chosen to
get the smallest end-to-end pipeline running first, then deepen.

### Phase 0 — Foundation ✅ (done)
- React + Vite + Cesium scaffold; globe renders with day/night.

### Phase 1 — Project structure & dual-container dev env
- Spring Boot backend skeleton + OpenAPI scaffold + Spring Security pipeline
  (no-op stub).
- PostgreSQL container; Flyway migrations; `users`, `scenarios`,
  `scenario_versions`, `audit_log` tables.
- Docker Compose for local dev (backend + frontend + db).
- Frontend gets an OpenAPI-generated client; one `GET /health` round-trip.
- Reshape the frontend shell from "catalog browser" to "scenario shell"
  (remove the obsolete constellation filter / catalog stats; introduce an
  empty scenario panel).

### Phase 2 — Propagation pipeline (smallest end-to-end slice)
- Orekit wired in the backend with SGP4 (lowest-friction fidelity).
- Define and version the streaming contract; encode CZML.
- Backend can stream a single TLE-defined satellite to the global view.
- Frontend renders it from the stream (no client-side propagation).
- Frame utility v1: ECI/ECEF/geodetic.

### Phase 3 — High-fidelity numerical propagation + scenario CRUD
- Add the numerical propagator (DP8(7), gravity ≥J4, drag, SRP, third-body).
- Per-scenario fidelity selection.
- Full frame management (LVLH/RIC + per-spacecraft body frames).
- `Scenario` REST CRUD + versioning; initial states from TLE/CCSDS/Keplerian.
- Multiple deputies; scenario panel UI; load scenario into the global view.

### Phase 4 — Dual viewports + shared clock
- three.js proximity view scaffold in LVLH frame.
- Relative-state stream (alongside CZML).
- Shared clock slice in the store; both views in lockstep.
- Time controls: play / pause / step / scrub / rate (0.01x–10000x) / reverse.
- Timeline scrub bar (annotations come later).

### Phase 5 — Relative motion + initial maneuvers
- Backend computes and streams chief-LVLH relative state for deputies.
- Closed-form CW alternative for close-range scenarios.
- Impulsive delta-V maneuvers in the scenario model.
- Maneuver templates: Hohmann, two-impulse rendezvous.
- Delta-V vector annotations + cumulative budget UI.

### Phase 6 — Proximity visualization
- Spacecraft GLTF models with articulable parts (solar arrays, antennas).
- Trajectory ribbons (past solid / predicted dashed).
- Adjustable scale 1 m–100 km.
- Camera modes: chief-body, deputy-body, fixed external.

### Phase 7 — Sensors & FOV
- Sensor model (type, FOV geometry, range, pointing).
- Translucent FOV volumes in the proximity view.
- Occlusion against other spacecraft, Earth, Sun.
- Sensor-frame view (camera anchored to a sensor's pointing).
- Acquisition / loss-of-sight events on the timeline.

### Phase 8 — Environment & events
- Sun/Moon positions; eclipse umbra/penumbra per spacecraft.
- Spacecraft illumination consistent with sun vector.
- Conjunction detection (configurable miss-distance threshold).
- Constraint checks: approach corridor, sun keep-out, plume impingement.
- Timeline event annotations populated.

### Phase 9 — Advanced maneuvers & analysis
- Maneuver templates: glideslope, V-bar/R-bar hold, NMC ellipse,
  station-keeping.
- Finite-burn maneuvers (thrust, Isp, duration).
- Monte Carlo dispersion on initial state + maneuver execution.
- Covariance ellipsoids in the relative frame.
- Link budget / SNR overlays for RF and optical sensors.

### Phase 10 — Enterprise hardening
- Real OIDC/SAML integration + RBAC roles activated.
- §5.2 validation test suite (compare to Orekit reference cases; document
  AIAA 2006-6753 conformance).
- Audit-log UI; reproducibility tests (bit-identical reruns).
- TLS termination at ingress; secrets management.
- On-prem packaging (image bundle / Helm or Compose).

### Phase 11 — Polish & ship
- Sample scenarios; tooltips/help; performance pass to SRS §5.1 metrics.
- PNG snapshots + MP4 sequence export from rendered canvases.
- OpenAPI docs polish; user guide.

---

## 7. What carries over from the Phase-1 scaffold

Reusable:
- React + Vite + TypeScript project structure.
- [components/Globe.tsx](../satellite-tracker/src/components/Globe.tsx) — the
  Cesium viewer setup with day/night lighting.
- Zustand store pattern (Decision 5).
- Cesium ion configuration via `.env` and the existing token.

Replaced or removed:
- [components/FilterPanel.tsx](../satellite-tracker/src/components/FilterPanel.tsx) —
  obsolete (catalog-era constellations). Removed in Phase 1.
- [components/StatsOverlay.tsx](../satellite-tracker/src/components/StatsOverlay.tsx) —
  catalog stats; removed or repurposed for scenario stats.
- [lib/celestrak.ts](../satellite-tracker/src/lib/celestrak.ts) — direct
  client-side fetch is gone; TLE ingestion is a backend concern (Decision 14).
- [lib/propagator.ts](../satellite-tracker/src/lib/propagator.ts) — satellite.js
  is dropped; the frontend no longer propagates.

The current bottom bar (TimeController), top bar, and info panel survive as
UI primitives and get rewired to scenario state in Phase 1.

---

## 8. Success metrics (SRS §5.1)

These are the v1 acceptance targets:

- Proximity view: 60 fps with ≤10 spacecraft on mid-range discrete GPU.
- Global view: 30 fps under the same conditions.
- Scrub latency: ≤200 ms input-to-frame.
- Scenario load: ≤5 s for a 24-hour scenario.
- High-fidelity propagation: sub-km / 24h LEO against reference (§5.2.2).
- CW: sub-meter / 1h for separations under 10 km (§5.2.3).

Each phase ends with a check against the relevant subset.
