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

### Phase 1 — Project structure & dual-container dev env ✅ (done)
- Spring Boot backend skeleton + OpenAPI scaffold + Spring Security pipeline
  (no-op stub).
- PostgreSQL container; Flyway migrations; `users`, `scenarios`,
  `scenario_versions`, `audit_log` tables.
- Docker Compose for local dev (backend + frontend + db).
- Frontend gets an OpenAPI-generated client; one `GET /health` round-trip.
- Add an empty scenario panel + scenario-composer state in the store.
- Keep the existing catalog UI intact — it's first-class (Decision 13), not
  a relic. Its data source will be repointed in Phase 2.

### Phase 2 — Propagation pipeline + shared catalog stream ✅ (done)
- Orekit wired in the backend with SGP4 (lowest-friction fidelity).
- Define and version the streaming contract; CZML encoding.
- Backend catalog service: periodic SGP4 pass over the active TLE set;
  broadcast CZML feed. (*Deviation:* CelesTrak is firewall-blocked here, so it
  loads a bundled offline OMM seed + best-effort GitHub-mirror refresh, and the
  feed is gzip-compressed binary because the 7.36 MB frame couldn't drain to a
  remote browser uncompressed.)
- Frontend global view consumes the catalog stream (replaces the
  client-side CelesTrak fetch from Phase 0).
- Frame utility v1: ECI / ECEF / geodetic.
- Global-view camera interaction: single-click inspect, double-click focus
  (smooth, ENU tracked-entity orbit — Decision 18). (*Note:* catalog
  click→info-panel landed; click→scenario-composer wiring is deferred to
  Phase 3 with the rest of scenario CRUD.)

### Phase 3 — High-fidelity propagation + scenario CRUD
Sliced into **3A** (scenario composition on SGP4) then **3B** (physics depth).
- **3A ✅ (done):** `Scenario` REST CRUD + immutable versioning + audit log
  through one service layer (Decision 16); initial states from catalog TLE
  (frozen snapshot per role, reproducible); multiple deputies; scenario panel UI
  (list/save/load/delete) + wired composer (set-chief / add-deputy / remove).
  Loading repopulates the composer statically (live scenario streaming is
  Phase 4). CCSDS OEM + Keplerian initial-state sources deferred to a later phase.
- **3B ✅ (done):** numerical propagator (DP8(7), gravity ≥J4 [16×16], NRLMSISE-00
  drag, SRP, Sun/Moon third-body) via `NumericalPropagation`; fidelity dispatch
  (`PropagationService`: `sgp4`/`numerical`; `cw` deferred to Phase 5); frame
  management v2 (LVLH / RIC + a minimal per-spacecraft body frame + a single-epoch
  relative-state helper). Backend-only and engine-deepening — no UI or contract
  change; proven by `./gradlew test` (orientation pinned by signed axis,
  bit-identical reruns). Becomes user-visible in Phase 4. See Decision 20.

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

### Phase 6 — Proximity visualization ✅ (done)
- Spacecraft models with articulable parts: procedural box bus + solar arrays +
  dish (named joints, deployed pose) **plus a `GLTFLoader` swap seam** for a real
  `.glb` (R6 — never blocked on art assets).
- Trajectory ribbons (past solid / predicted dashed) via `Line2` fat lines, split
  at the current time from the client-side `samples`.
- Adjustable scale 1 m–100 km (a fixed-pixel marker is the far-LOD fallback).
- Camera modes: chief-body, deputy-body, fixed external.
- Earth backdrop **decided: yes** — true-scale sphere along −R from a new additive
  `chiefRadiusM` stream field, with an Earth/Stars/Off toggle; flat non-physical
  lighting until the Phase 8 Sun vector. Derived ram/LVLH orientation is a labeled
  estimate until Phase 7 attitude. See Decision 23, [phase-6-plan.md](./phase-6-plan.md).

### Phase 7 — Sensors & FOV ✅ (done)
- Sensor model (type, FOV geometry, range, pointing) — cone + rectangular, body-fixed;
  first-class scenario objects (`ScenarioBody` schema v3) on the audited path.
- Translucent FOV volumes in the proximity view (`proximity/sensors.ts`), riding a
  **backend-authoritative modeled attitude** (LVLH-aligned / fixed; streamed quaternion).
- Occlusion: Earth line-of-sight (analytic ray-vs-sphere). *Sun occlusion deferred to
  Phase 8 with the Sun vector; inter-spacecraft is negligible for point targets.*
- Sensor-frame view (camera anchored along a sensor's boresight).
- Acquisition / loss-of-sight events (`analysis/SensorEventComputer`) on the timeline.
- See [phase-7-plan.md](./phase-7-plan.md), Decision 24. *(Deferred: CCSDS AEM measured
  attitude, gimbaled pointing, frustum/polygonal FOV, sun-keep-out.)*

### Phase 8 — Environment & events ✅ (done)
Sliced 8A/8B/8C (see [phase-8-plan.md](./phase-8-plan.md), Decision 25). Rides the Phase-7
architecture (sampled-trajectory `analysis/` computers, additive `scenario-relative` fields —
`VERSION` stays `"1"`, forward-additive `ScenarioBody` schema **v5**, single audited
`ScenarioService`). Backend 152 tests green; frontend type-check + build green; verified
end-to-end on the dev stack.
- **8A** — Sun/Moon positions (reuse Orekit `CelestialBodyFactory`) streamed as LVLH unit
  directions → real `DirectionalLight` + Earth day/night terminator (resolves R17 flat
  lighting); conical umbra/penumbra eclipse per spacecraft (`EclipseEventComputer`, geocentric
  ECI) → timeline bands + Sun-consistent craft dimming.
- **8B** — intra-scenario conjunction detection (`ConjunctionEventComputer`, configurable
  `missDistanceThresholdM`); constraint checks — sun-keep-out + approach corridor
  (`ConstraintChecker`); timeline event annotations (eclipse bands, conjunction ticks,
  violation marks) + `EnvironmentPanel`. *(Plume impingement deferred — needs per-burn
  plume geometry.)*
- **8C** — catalog conjunction screening (`ScreeningService`, `POST /scenarios/{id}/screening`,
  two-stage shell-prune + fine refine) → sorted results table + CSV (UC-7); a snapshot vs the
  live catalog (R11 caveat).

### Phase 9 — Advanced maneuvers & analysis ✅ (done — see [phase-9-plan.md](./phase-9-plan.md), Decision 27)
Sliced 9A/9B/9C/9D. Rides the Phase 4–8 architecture (sampled-trajectory `analysis/` computers,
additive `scenario-relative` fields — `VERSION` stays `"1"`, forward-additive `ScenarioBody`
schema **v6**, single audited `ScenarioService`). Backend 187 tests green; frontend type-check +
build green; verified on the dev stack. Resolves **R16**; introduces the first **seeded RNG**
(determinism held — per-sample seed + ordered collect).
- ✅ **9A — Flight-ready rendezvous** — moves the two-impulse Lambert template (Phase 5C) from an
  open-loop two-body *sketch* to a converged plan: a **differential corrector**
  (`RendezvousCorrector`) against the real propagators (fixes the R16 model-mismatch miss), an
  **arrival × revolution ΔV search** (`RendezvousSearchService`), and a **phasing-orbit planner**.
- ✅ **9B — CW close-range templates + finite burns** — NMC ellipse + V-bar/R-bar hold + **glideslope**
  (constant-closing-rate approach in chained CW two-impulse legs + park) + **closed-loop
  station-keeping** (periodic corrective burns, fed back from the real propagator)
  (`CwTargeting` + `ManeuverTemplateService.nmc`/`hold`/`glideslope`/`stationKeep`); **finite-burn
  maneuvers** (thrust, Isp; v6-additive `Impulse`/`Maneuver` fields → Orekit `ConstantThrustManeuver`
  of the Tsiolkovsky duration, centred on the epoch).
- ✅ **9C — Monte Carlo + covariance** — dispersion on initial state + maneuver execution +
  covariance ellipsoids in the relative frame (`MonteCarloService`, UC-6).
- ✅ **9D — Link budget / SNR overlays** for RF and optical sensors (`LinkBudgetComputer`,
  schema-v6 `LinkBudget` on a sensor). ⬜ *Deferred:* optical detector NEP/QE detail.

### Phase 10 — Enterprise hardening ✅ (done — see [phase-10-plan.md](./phase-10-plan.md), Decision 28)
Sliced 10A/10B/10C. Activates the Decision-16 seams (additive, not a rewrite). Backend
**203 tests green**; frontend type-check green; Helm chart `helm lint` + `helm template` clean.
- ✅ **10A — Real auth + RBAC.** OIDC **resource-server** (stateless bearer JWT) gated by
  `orbit.auth.mode` (`stub` default keeps local dev IdP-free; `oidc` enforces auth + roles);
  `JwtAuthenticationConverter` maps Keycloak realm roles → `ROLE_*`; WebSocket auth via
  `?access_token=` (query-param bearer resolver). Ownership already enforced; capability role
  rules added. Frontend auth-code+PKCE (`react-oidc-context`) + Bearer middleware. Self-hosted
  **Keycloak** dev overlay (`docker-compose.oidc.yml`).
- ✅ **10B — Governance & trust.** Audit-log + version-history REST (`/scenarios/{id}/audit`,
  `/versions`) + `AuditLogPanel`; end-to-end **reproducibility** tests (byte-identical reruns);
  **Orekit-reference §5.2 validation suite** + [validation-conformance.md](./validation-conformance.md)
  (AIAA 2006-6753 conformance inherited from Orekit, R2 — we validate correct integration).
- ✅ **10C — Deployment.** Prod frontend image (nginx + runtime `/env.js`); **Helm chart**
  (`deploy/helm/orbit`) — backend/frontend/Keycloak Deployments, Postgres StatefulSet,
  cert-manager **TLS** at split api/web/keycloak Ingresses, k8s **Secrets**, external-DB /
  external-IdP toggles; offline `docker save` + `helm package` bundle ([scripts/bundle.sh](../scripts/bundle.sh));
  [deployment.md](./deployment.md) runbook. Dev stays on Compose. **Deferred:** SAML2; Keycloak
  HA; external golden vectors.

### Phase 11 — Polish & ship ✅ (done — see [phase-11-plan.md](./phase-11-plan.md), Decision 29)
Sliced 11A/11B/11C. Scope extended (with the user) to complete SRS §4.2. Backend
**217 tests green**; frontend type-check + build green. New dep: `mp4-muxer`.
- ✅ **11B — Export (§4.2 complete).** PNG snapshots (same-task canvas capture, no
  `preserveDrawingBuffer`); **MP4** via a deterministic frame-stepped offline render
  (WebCodecs H.264 + `mp4-muxer`); **events JSON/CSV** (client-side from the stream
  buffer); **CCSDS OEM export** (`GET /scenarios/{id}/export/oem`, Orekit `OemWriter`,
  byte-identical reruns, audited `EXPORT_OEM`). Resolves the deferred media-export
  decision (§4.2.3).
- ✅ **11A — Usability (§5.6).** Demo set grown to five (adds sensor/link-budget,
  eclipse, V-bar station) and seeded **per user on first login** (`UserProvisioner`
  event → seeder, `AFTER_COMMIT`); `?` Help overlay + first-run hint; tooltip audit
  (every interactive control titled).
- ✅ **11C — Perf + docs.** `perf.ts` + PerfHud (live FPS / scrub latency / load time
  vs the §5.1 targets — the R7 FPS counter, finally instrumented); OpenAPI info bean +
  `@Tag`/`@Operation` on all 31 endpoints; [user-guide.md](./user-guide.md); root
  README. ⬜ *§5.1 readings on reference hardware pending (evidence table in the
  phase plan).*

### Measured-data ingestion *(feature track, off the phase line — Decision 26)*
Real measured telemetry (WOD CSV: GNSS ECI pos/vel + ADCS quaternions) imported as a
scenario whose chief is the measured craft (read-only truth), served via an Orekit
tabulated ephemeris through the existing stream. Generalizes the deferred CCSDS OEM
import (Decision 19) + AEM attitude (Decision 24). **Slice 1 done** (position);
slices 2–3 (attitude; measured deputies / numerical handoff / OEM-AEM readers / upload)
in [measured-data-plan.md](./measured-data-plan.md).

---

## 8. What carries over from the Phase-0 scaffold

Reusable and **first-class going forward**:
- React + Vite + TypeScript project structure.
- [components/Globe.tsx](../frontend/src/components/Globe.tsx) — the
  Cesium viewer setup with day/night lighting.
- [components/FilterPanel.tsx](../frontend/src/components/FilterPanel.tsx)
  — repurposed as the catalog browser's constellation filter (Decision 13).
  Stays.
- [components/StatsOverlay.tsx](../frontend/src/components/StatsOverlay.tsx)
  — repurposed for catalog + scenario stats; counts come from the catalog
  stream rather than hardcoded.
- Zustand store pattern (Decision 5).
- Cesium ion configuration via `.env` and the existing token.

Repointed in Phase 2 (no longer fetched client-side):
- [lib/celestrak.ts](../frontend/src/lib/celestrak.ts) — TLE
  ingestion moves to the backend (Decision 15); the frontend consumes the
  backend catalog stream instead.
- [lib/propagator.ts](../frontend/src/lib/propagator.ts) —
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

Each phase ends with a check against the relevant subset. *(Phase 11: these metrics
are now instrumented in-app — the ⏱ performance HUD shows live per-view FPS, scrub
latency, and scenario-load time against these targets.)*
