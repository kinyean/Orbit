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
- **Catalog** — the live set of ~14,500 real active satellites, served as a
  shared real-time feed and used as the composition path for scenarios.
- **LVLH / RIC** — Local Vertical Local Horizontal / Radial-In-track-Cross-track
  frame centered on the chief; the natural frame for proximity analysis.

---

## 1. Component overview

```
┌───────────────────────────────────────────────────────────────┐
│  FRONTEND CLIENT  (static, containerized)                      │
│                                                                 │
│  ┌──────────────────────┐    ┌──────────────────────────────┐  │
│  │ Global view          │    │ Proximity view               │  │
│  │ React + CesiumJS     │    │ React + three.js (LVLH)      │  │
│  │  catalog dots +      │◄──►│  chief at origin, deputies   │  │
│  │  scenario sats +     │ same clock                         │  │
│  │  orbits/ground tracks│    │  + relative-state stream      │  │
│  └──────────────────────┘    └──────────────────────────────┘  │
│  Catalog browser · Scenario panel · Timeline · Controls         │
└──────────────────────────┬─────────────────────────────────────┘
        REST (OpenAPI 3.x) │  WebSocket (CZML + relative state)
┌──────────────────────────┴─────────────────────────────────────┐
│  BACKEND  (Java + Spring Boot, containerized)                   │
│                                                                 │
│  ┌────────────────────────┐  ┌─────────────────────────────┐   │
│  │ Catalog service        │  │ Scenario service            │   │
│  │  one shared SGP4 pass  │  │  CRUD, versioning,          │   │
│  │  refreshed periodically│  │  import/export, audit       │   │
│  │  → broadcast CZML      │  │                              │   │
│  └────────────────────────┘  └─────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ Propagation core (Orekit) — multi-fidelity, frame utility │  │
│  │ Analysis (conjunctions, events, Monte Carlo, link budget) │  │
│  └──────────────────────────────────────────────────────────┘  │
│  Spring Security pipeline (auth/RBAC seam)                      │
└──────────────────────────┬─────────────────────────────────────┘
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

## 2. Two propagation/streaming modes (Decision 13)

The backend serves the same engine in two operating profiles, with very
different load characteristics:

| Mode | Spacecraft | Engine | Stream | Cached? |
|---|---|---|---|---|
| **Catalog** | All ~14,500 active | SGP4 (Orekit) | One shared CZML feed | Yes — computed once per refresh cycle, broadcast to every viewer |
| **Scenario** | Chief + ≤10 deputies | Selected fidelity (SGP4 / numerical / CW) | Per-user CZML + relative-state | No — per user, per scenario |

The catalog mode is what makes "see all the active satellites at once" cheap:
one SGP4 pass over the full catalog every refresh cycle, broadcast as CZML
chunks that Cesium interpolates between client-side. Tens of users converge
on the same data — one computation, fan-out.

The scenario mode is the per-user heavy lifting: a custom time range, the
selected fidelity (often the high-fidelity numerical propagator with drag /
SRP / third-body), a deputy maneuver plan, and chief-relative analysis. Each
active scenario is its own WebSocket session.

A user can have both running simultaneously — catalog populated globally
while a scenario propagates the selected spacecraft in detail.

---

## 3. Backend (Java + Spring Boot + Orekit)

Responsibilities:

- **Propagation core.** Orekit-backed multi-fidelity propagation: SGP4/SDP4,
  high-fidelity numerical (DP8(7), gravity field ≥J4, NRLMSISE-00 drag, SRP,
  Sun/Moon third-body), and Clohessy-Wiltshire for close range. Per-scenario
  fidelity selection. Deterministic (seeded, pinned).
- **Catalog service.** Periodic SGP4 pass over the active TLE set (from
  CelesTrak / Space-Track or uploaded TLEs). Produces a single shared CZML
  feed broadcast to all connected viewers.
- **Scenario service.** REST CRUD for scenarios, versioning, ownership, audit
  log. CCSDS OEM/OPM/AEM and TLE import. CZML and OEM export.
- **Frame transforms.** A single canonical utility wrapping Orekit's frames
  with IERS Earth-orientation corrections. All emitted states carry their
  frame tag (SRS §3.2.5).
- **Streaming.** WebSocket: one channel for the shared catalog feed; one
  channel per active scenario session. Both shapes carry CZML for the global
  view; scenarios additionally carry compact relative-state for the
  proximity view.
- **Analysis.** Conjunction detection, sensor acquisition/loss events,
  constraint checks, Monte Carlo dispersion, covariance evolution.
- **Security.** All requests through a Spring Security pipeline; auth/RBAC
  starts as a no-op stub but the pipeline is real from day one (Decision 16).

Module sketch:

```
backend/
├── prop/            Orekit wrappers, fidelity selection, frame utility
├── catalog/         shared SGP4 pass, TLE refresh, broadcast CZML feed
├── scenario/        domain model, persistence, versioning, audit
├── stream/          CZML + relative-state encoders, WebSocket handlers
├── analysis/        conjunctions, events, Monte Carlo, link budget
├── io/              CCSDS, TLE, Keplerian readers/writers
├── api/             REST controllers, OpenAPI annotations
├── security/        auth pipeline (stub today, OIDC/SAML-ready)
└── config/          Spring config, 12-factor env binding
```

---

## 4. Frontend (React + Vite + Cesium + three.js)

Responsibilities:

- **Global view (Cesium).** Earth at WGS84 scale + day/night terminator +
  ground tracks. Renders **two layers** simultaneously:
  - The **catalog layer** — all ~14,500 active satellites as
    `PointPrimitiveCollection` dots, fed by the shared catalog CZML stream.
  - The **scenario layer** — the chief and deputies of the active scenario,
    with full orbit paths, ground tracks, and highlighted markers, fed by
    the scenario CZML stream.
- **Proximity view (three.js).** Chief-centered LVLH scene. Spacecraft 3D
  models (GLTF) with articulable parts. Sensor FOV volumes as translucent
  geometry. Trajectory ribbons (past = solid, predicted = dashed). Delta-V
  vector annotations at maneuver epochs. Adjustable scale 1 m–100 km.
- **Catalog browser.** Filters (constellation, type, regime, country),
  search by name / NORAD ID, hit-padded clicks. Click a catalog satellite
  → designate as chief (if none) or add as deputy in the scenario being
  composed.
- **Scenario panel.** List, create, edit, duplicate, version-browse
  scenarios. Click-to-compose flow + form-based creation (for hypothetical
  spacecraft).
- **Timeline.** Scrub bar with annotated maneuver epochs, eclipse periods,
  sensor acquisition windows, conjunction events.
- **Controls.** Play / pause / step / reset / rate (0.01x–10000x) / reverse.
- **Shared clock.** Single Zustand slice; both views subscribe. The frontend
  owns playback control; the backend is authoritative on state.

Module sketch (extending what exists):

```
frontend/src/
├── views/
│   ├── global/         Cesium scene; catalog + scenario CZML data sources
│   └── proximity/      three.js scene, LVLH camera, models, FOV volumes
├── catalog/            browser UI, filters, search, click-to-compose wiring
├── scenario/           panel, CRUD UI, import/export forms
├── timeline/           scrub bar, event annotations
├── stream/             WebSocket client, decoded state buffers, interpolation
├── api/                generated OpenAPI client
├── store/              Zustand slices (clock, selection, layout, composer)
├── lib/                frame helpers, formatting, time-scale UI utils
└── components/         shared UI primitives
```

---

## 5. Data store (PostgreSQL)

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
Decision 16).

Catalog TLE data is *not* persisted as application state — it's refreshed
from CelesTrak/Space-Track on a backend schedule and held in memory for the
shared SGP4 pass. (Local file caching is fine for resilience; the database
holds *scenario* state.)

---

## 6. Data flows

### Catalog browse (always-on background)
1. Backend scheduler triggers every N seconds (e.g., 30 s).
2. Backend runs one SGP4 pass over all active TLEs; encodes the result as a
   CZML chunk covering the next ~N + buffer seconds.
3. CZML chunk is broadcast over the catalog WebSocket channel to every
   connected viewer.
4. Each Cesium instance ingests the chunk; `SampledPositionProperty`
   interpolates between samples for smooth per-frame motion.

### Click-to-compose
1. User clicks a satellite dot in the global view.
2. Frontend resolves the picked NORAD ID; current scenario-composer state
   in Zustand decides: first pick → chief, subsequent → deputy.
3. Frontend POSTs the new/updated scenario version to the backend.
4. Backend persists, returns the updated scenario; frontend opens a
   scenario stream for it (next flow).

### Scenario load + playback
1. UI requests `GET /scenarios/{id}/versions/{v}`.
2. Backend returns the scenario body; UI populates the panel.
3. UI opens `WS /stream/{scenarioId}?from=...&to=...`.
4. Backend propagates at the chosen fidelity; streams CZML to the global
   view's scenario layer and relative-state to the proximity view.
5. Frontend interpolates between samples; both views animate in lockstep.

### Scrub / rate change
1. UI advances the clock locally (or sets a new epoch on scrub).
2. UI sends `{kind: 'seek' | 'rate', value: ...}` over the scenario socket.
3. Backend resumes streaming at the new rate / direction, or jumps to the
   requested epoch and resumes.

### Maneuver evaluation
1. UI edits the maneuver plan; PUTs the new scenario version.
2. UI requests a fresh stream for the updated scenario.
3. Backend re-propagates from the maneuver epoch onward; streams updated
   CZML + relative state.
4. Frontend renders the new transfer path; delta-V vector annotation
   appears in the proximity view; cumulative delta-V budget updates.

---

## 7. Build roadmap

Phased so each phase ends with a working slice end-to-end. Sequence chosen to
get the smallest end-to-end pipeline running first, then deepen.

### Phase 0 — Foundation ✅ (done)
- React + Vite + Cesium scaffold; globe renders with day/night.
- Catalog UI (constellations filter, stats overlay) wired to client-side
  CelesTrak fetch — to be repointed at the backend in Phase 2.

### Phase 1 — Project structure & dual-container dev env
- Spring Boot backend skeleton + OpenAPI scaffold + Spring Security pipeline
  (no-op stub).
- PostgreSQL container; Flyway migrations; `users`, `scenarios`,
  `scenario_versions`, `audit_log` tables.
- Docker Compose for local dev (backend + frontend + db).
- Frontend gets an OpenAPI-generated client; one `GET /health` round-trip.
- Add an empty scenario panel + scenario-composer state in the store.
- Keep the existing catalog UI intact — it's first-class (Decision 13), not
  a relic. Its data source will be repointed in Phase 2.

### Phase 2 — Propagation pipeline + shared catalog stream
- Orekit wired in the backend with SGP4 (lowest-friction fidelity).
- Define and version the streaming contract; CZML encoding.
- Backend catalog service: periodic SGP4 pass over CelesTrak TLEs;
  broadcast CZML feed.
- Frontend global view consumes the catalog stream (replaces the
  client-side CelesTrak fetch from Phase 0).
- Frame utility v1: ECI / ECEF / geodetic.
- Click-to-compose wiring: catalog click → scenario composer state.

### Phase 3 — High-fidelity propagation + scenario CRUD
- Add the numerical propagator (DP8(7), gravity ≥J4, drag, SRP, third-body).
- Per-scenario fidelity selection.
- Full frame management (LVLH / RIC + per-spacecraft body frames).
- `Scenario` REST CRUD + versioning; initial states from TLE / CCSDS /
  Keplerian.
- Multiple deputies; scenario panel UI; load scenario → per-user scenario
  stream.

### Phase 4 — Dual viewports + shared clock
- three.js proximity view scaffold in LVLH frame.
- Relative-state stream (alongside CZML for the scenario).
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
- Earth backdrop decision (default yes — see Deferred in decisions.md).

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
- Sample scenarios; tooltips / help; performance pass to SRS §5.1 metrics.
- PNG snapshots + MP4 sequence export from rendered canvases.
- OpenAPI docs polish; user guide.

---

## 8. What carries over from the Phase-0 scaffold

Reusable and **first-class going forward**:
- React + Vite + TypeScript project structure.
- [components/Globe.tsx](../satellite-tracker/src/components/Globe.tsx) — the
  Cesium viewer setup with day/night lighting.
- [components/FilterPanel.tsx](../satellite-tracker/src/components/FilterPanel.tsx)
  — repurposed as the catalog browser's constellation filter (Decision 13).
  Stays.
- [components/StatsOverlay.tsx](../satellite-tracker/src/components/StatsOverlay.tsx)
  — repurposed for catalog + scenario stats; counts come from the catalog
  stream rather than hardcoded.
- Zustand store pattern (Decision 5).
- Cesium ion configuration via `.env` and the existing token.

Repointed in Phase 2 (no longer fetched client-side):
- [lib/celestrak.ts](../satellite-tracker/src/lib/celestrak.ts) — TLE
  ingestion moves to the backend (Decision 15); the frontend consumes the
  backend catalog stream instead.
- [lib/propagator.ts](../satellite-tracker/src/lib/propagator.ts) —
  satellite.js drops out; the frontend no longer propagates.

The existing TimeController, top bar, and info panel survive as UI primitives
and get rewired to scenario state in Phases 1–4.

---

## 9. Success metrics (SRS §5.1)

These are the v1 acceptance targets:

- Proximity view: 60 fps with ≤10 spacecraft on mid-range discrete GPU.
- Global view: 30 fps with the full catalog (~14,500 dots) + scenario layer.
- Scrub latency: ≤200 ms input-to-frame.
- Scenario load: ≤5 s for a 24-hour scenario.
- High-fidelity propagation: sub-km / 24h LEO against reference (§5.2.2).
- CW: sub-meter / 1h for separations under 10 km (§5.2.3).

Each phase ends with a check against the relevant subset.
