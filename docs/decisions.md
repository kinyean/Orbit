# Decisions

The single source of truth for "why this, not that." Read this before
deviating — the alternatives here have already been weighed.

> **2026-05-28 — Project pivot.** A Software Requirements Specification
> ([Software Requirements Specification.md](./Software%20Requirements%20Specification.md))
> redefined this project from a *public satellite tracker* into an
> *Inter-Satellite Remote Proximity Operations (RPO) simulation platform* for
> flight-dynamics engineers. The SRS is now the authoritative spec. Several
> early decisions were reversed; they are retained in
> [Superseded decisions](#superseded-decisions-pre-srs-pivot) for the record.
> Keystone choices from the pivot: backend required (Java + Spring Boot),
> Orekit propagation engine, dual-viewport client (Cesium + three.js),
> scenario-based data model with a first-class catalog browser, and a
> "build professional-grade from the start" posture.

Format per decision: **Context**, **Decision**, **Why**, **Alternatives
considered**, **Consequences**.

## Table of contents

**Frontend**
1. [React + TypeScript](#1-react--typescript)
2. [Build tool: Vite](#2-build-tool-vite)
3. [Global-view engine: CesiumJS](#3-global-view-engine-cesiumjs)
4. [Proximity-view engine: three.js](#4-proximity-view-engine-threejs)
5. [Frontend state: Zustand](#5-frontend-state-zustand)

**Backend & propagation**
6. [Backend: Java + Spring Boot](#6-backend-java--spring-boot)
7. [Propagation engine: Orekit, multi-fidelity](#7-propagation-engine-orekit-multi-fidelity)
8. [Scenario store: PostgreSQL](#8-scenario-store-postgresql)
20. [Numerical propagator: pinned settings + fidelity dispatch (Phase 3B)](#20-numerical-propagator-pinned-settings--fidelity-dispatch-phase-3b)

**Architecture & contracts**
9. [Four-component architecture, decoupled visualization](#9-four-component-architecture-decoupled-visualization)
10. [State-streaming contract: REST + WebSocket + CZML](#10-state-streaming-contract-rest--websocket--czml)
11. [Single authoritative simulation clock](#11-single-authoritative-simulation-clock)
12. [Frame management: canonical utility, frame-tagged states](#12-frame-management-canonical-utility-frame-tagged-states)
13. [Two propagation/streaming modes: shared catalog + per-user scenarios](#13-two-propagationstreaming-modes-shared-catalog--per-user-scenarios)
21. [Phase-4 clock model + live catalog time-travel (Phase 4A)](#21-phase-4-clock-model--live-catalog-time-travel-phase-4a)

**Data**
14. [Scenario data model: chief + deputies](#14-scenario-data-model-chief--deputies)
15. [Data formats: TLE, CCSDS, Keplerian, CZML](#15-data-formats-tle-ccsds-keplerian-czml)
19. [Scenario body schema v1 + persistence design (Phase 3A)](#19-scenario-body-schema-v1--persistence-design-phase-3a)
26. [Measured-data ingestion: WOD CSV as an ephemeris-role scenario](#26-measured-data-ingestion-wod-csv-as-an-ephemeris-role-scenario)

**Environment & events**
25. [Sun/Moon + lighting, eclipse, conjunctions, constraints (Phase 8)](#25-sunmoon--lighting-eclipse-conjunctions-constraints-phase-8)

**Maneuvers & analysis**
27. [Advanced maneuvers & analysis: corrector, CW templates, Monte Carlo, link budget (Phase 9)](#27-advanced-maneuvers--analysis-corrector-cw-templates-monte-carlo-link-budget-phase-9)

**Cross-cutting / enterprise**
16. [Enterprise posture: professional-grade from the start](#16-enterprise-posture-professional-grade-from-the-start)
17. [Deployment: containerized, cloud + on-prem](#17-deployment-containerized-cloud--on-prem)

**UX / interaction**
18. [Global-view camera: click-to-inspect, double-click focus](#18-global-view-camera-click-to-inspect-double-click-focus)
22. [Distance-vs-time graph: tab-in-readout, no-dep SVG, windowed (Phase 5)](#22-distance-vs-time-graph-tab-in-readout-no-dep-svg-windowed-phase-5)
23. [Proximity scene: procedural models + derived orientation + correct Earth backdrop (Phase 6)](#23-proximity-scene-procedural-models--derived-orientation--correct-earth-backdrop-phase-6)
24. [Sensors & FOV: modeled backend attitude + cone/rect FOV + sampled occlusion-aware events (Phase 7)](#24-sensors--fov-modeled-backend-attitude--conerect-fov--sampled-occlusion-aware-events-phase-7)

[Superseded decisions (pre-SRS pivot)](#superseded-decisions-pre-srs-pivot)
·
[Deferred decisions](#deferred-decisions)

---

# Frontend

## 1. React + TypeScript

**Context.** The client is an interactive multi-panel app with two 3D
viewports, a timeline, and scenario controls.

**Decision.** React (hooks, function components) + TypeScript strict mode.

**Why.** Deepest ecosystem, biggest talent pool. TypeScript matters
doubly here: orbital/relative-motion code is full of unit and frame pitfalls
(km vs m, degrees vs radians, ECI vs LVLH) that types catch before runtime.

**Alternatives considered.** Vue, Svelte, SolidJS — all viable, smaller
ecosystems. No reason to go exotic.

**Consequences.** Strict mode stays on; per-slice store subscriptions
(Decision 5).

## 2. Build tool: Vite

**Context.** Need a fast TS/JSX bundler producing static output for the
client container, with the CesiumJS asset story handled.

**Decision.** Vite + `@vitejs/plugin-react` + `vite-plugin-cesium`.

**Why.** Sub-second HMR, static output, minimal config; Cesium plugin handles
`CESIUM_BASE_URL` and asset copy. three.js needs no special build handling.

**Alternatives considered.** Next.js (server features we don't need for the
client), CRA (deprecated), raw Webpack (more config).

**Consequences.** Two `tsconfig` files (browser code vs `vite.config.ts`).

## 3. Global-view engine: CesiumJS

**Context.** The global view (SRS §3.8) renders Earth at WGS84 scale with
orbital paths, ground tracks, and time-dynamic state. SRS §6.1.1 mandates
CesiumJS.

**Decision.** CesiumJS for the global view. Consumes time-dynamic state as
CZML (SRS §3.8.5).

**Why.** Cesium natively handles the WGS84 ellipsoid, day/night terminator,
atmosphere, ground tracks, geodetic math, and a time-driven clock — exactly
the global-view needs. Mandated by the spec besides.

**Alternatives considered.** None — spec-mandated. (The existing Phase-1
scaffold is already this engine and carries over directly.)

**Consequences.** Cesium ion provides imagery (5 GB/mo free; self-host
trigger deferred). The global view is one of two engines (Decision 4).

## 4. Proximity-view engine: three.js

**Context.** The proximity view (SRS §3.9) renders the close-range scene in
the chief's LVLH frame at scales from 1 m to 100 km: spacecraft 3D models with
articulable parts, sensor FOV volumes, relative-motion trajectory ribbons,
delta-V vectors. SRS §6.1.2 specifies three.js or equivalent.

**Decision.** three.js for the proximity view, rendering in the chief-centered
LVLH frame.

**Why.** Cesium is globe-anchored and geo-centric — wrong tool for a
free-space, model-rich scene at meter scale. three.js is a general 3D engine
built for exactly this: custom GLTF models, articulated parts, translucent FOV
geometry, custom shaders. The two engines share one clock (Decision 11).

**Alternatives considered.**
- *Cesium for both views.* Cesium's scene graph fights free-space
  model-centric rendering; meter-scale proximity ops aren't its model.
- *Babylon.js.* Comparable to three.js; three.js has the larger ecosystem and
  is named in the spec.

**Consequences.** Two render engines to maintain. They are decoupled and
synchronized only through the shared clock and the relative-state stream
(Decision 10). Spacecraft models need a GLTF asset pipeline.

## 5. Frontend state: Zustand

**Context.** The client has shared UI/session state (current sim time,
selected objects, view layout, active scenario id) read by many components.
Heavy ephemeris data comes from the backend stream, not the store.

**Decision.** Zustand for **frontend UI/session state**, per-slice
subscriptions. Streamed ephemeris/state is held in purpose-built buffers, not
in Zustand.

**Why.** ~1 KB, no boilerplate, per-slice subscriptions avoid the Context
re-render trap during 60fps animation. The store holds control state; the
high-frequency position data lives in typed buffers fed by the WebSocket.

**Alternatives considered.** Redux Toolkit (overkill), Jotai (comparable),
React Context (re-render trap).

**Consequences.** Clear split: Zustand = control/UI; stream buffers = state
data. Both views subscribe to the same clock slice for synchronization.

---

# Backend & propagation

## 6. Backend: Java + Spring Boot

**Context.** The SRS requires a backend propagation/analysis service (§2.1.1,
§6.1.4) with REST + WebSocket APIs (§4.3), auth/RBAC (§5.5), and the chosen
propagation engine is Orekit — a Java library (Decision 7).

**Decision.** Java backend on Spring Boot, calling Orekit natively.

**Why.** Orekit is Java; native calls avoid bridging friction and get full
performance. Spring Boot brings batteries-included REST, WebSocket (STOMP),
Spring Security (OIDC/SAML/RBAC for §5.5), dependency injection, and a mature
ops ecosystem — directly serving the "build professional-grade from the start"
posture (Decision 16).

**Alternatives considered.**
- *Python + Orekit via JPype.* Friendlier app code and easy NumPy-based Monte
  Carlo, but a JVM bridge under the hood, weaker performance at the boundary,
  and a less mature enterprise-security story than Spring. Rejected given the
  professional posture.
- *Custom propagator in TS/Node.* Cannot meet the validated-accuracy bar
  (§5.2); rejected with Decision 7.

**Consequences.** Backend is JVM; containerized (Decision 17). Monte Carlo and
numerical analysis (SRS §3.12) are implemented in Java/Orekit or delegated to a
worker pool — to be detailed when that phase arrives.

## 7. Propagation engine: Orekit, multi-fidelity

**Context.** The SRS demands SGP4/SDP4 (§3.1.1), high-fidelity numerical
propagation (DP8(7) integrator, J4+ gravity, NRLMSISE-00 drag, SRP, third-body
— §3.1.2–6), Clohessy-Wiltshire relative motion (§3.1.7), per-scenario fidelity
selection (§3.1.8), CCSDS I/O (§4.1–4.2), IERS frame corrections (§3.2.2), and
validation to AIAA 2006-6753 / sub-km over 24h (§5.2).

**Decision.** Orekit as the single propagation engine, exposing three
fidelity modes selectable per scenario:
1. **SGP4/SDP4** from TLE.
2. **High-fidelity numerical** — DP8(7), configurable gravity field (≥J4),
   NRLMSISE-00 drag, SRP, Sun/Moon third-body.
3. **Clohessy-Wiltshire** linearized relative motion for close range.

Propagation is **deterministic** (fixed settings, seeded dispersions) for
reproducibility (§5.4.1).

**Why.** Orekit is the open-source industry standard and already provides every
item above — including CCSDS OEM/OPM/AEM parsing/writing and IERS-corrected
frames — already validated. Reimplementing any of this would be a multi-month
effort that still wouldn't clear the validation bar.

**Alternatives considered.**
- *Tudat / Astropy (Python).* Capable but more assembly, lighter CCSDS, and
  would pair with a Python backend we rejected (Decision 6).
- *Custom (TS/Java).* Can't realistically hit AIAA conformance soon.

**Consequences.**
- satellite.js is dropped — Orekit handles SGP4 (supersedes old Decision 5).
- The frontend never propagates; it consumes streamed state (Decision 10).
- Fidelity selection is part of the scenario model (Decision 14).
- A validation test suite against reference solutions is required for §5.2
  (deferred but architected for).
- The same engine serves both load profiles (Decision 13): SGP4 for the shared
  catalog, selected fidelity per scenario.

## 8. Scenario store: PostgreSQL

**Context.** Scenarios are persistent, serializable artifacts with versioning,
author/timestamp metadata (§3.10.5), and audit logging (§5.4.2). They contain
nested, somewhat free-form payloads (states, maneuver plans, sensor configs,
attitude profiles).

**Decision.** PostgreSQL. Relational tables for scenarios, versions, users,
and audit log; `jsonb` columns for the flexible scenario payloads.

**Why.** Professional default: mature, transactional, supports both rigid
relational structure (versioning, ownership, audit) and flexible `jsonb`
(scenario bodies). One store covers persistence, versioning, and audit.

**Alternatives considered.**
- *SQLite.* Fine for single-user/dev; weak for concurrent multi-user and the
  professional deployment story.
- *MongoDB.* Flexible documents, but weaker transactional/relational support
  for versioning + audit + RBAC ownership.

**Consequences.** Backend gains a database dependency and migrations
(Flyway/Liquibase). Scenario ownership column exists from day one as the RBAC
seam (Decision 16).

---

# Architecture & contracts

## 9. Four-component architecture, decoupled visualization

**Context.** SRS §2.1.1 names four components; §5.3.3 requires the
visualization layer be decoupled from the propagation engine via a defined
state-streaming contract; §5.3.1–2 require new propagators/sensors/maneuvers be
addable without touching rendering.

**Decision.** Four components:
1. **Backend propagation & analysis service** (Java/Spring/Orekit).
2. **Frontend dual-viewport client** (React + Cesium + three.js).
3. **Scenario data store** (PostgreSQL).
4. **External interfaces** (CelesTrak/Space-Track, CCSDS files).

The frontend and backend communicate **only** through the streaming contract
(Decision 10) — the frontend has no propagation logic.

**Why.** Hard decoupling is a spec requirement and good architecture: the
engine can grow (new perturbations, new fidelity) without rendering changes,
and the rendering can grow without engine changes. The contract is the seam.

**Alternatives considered.**
- *Client-side propagation (the old no-backend model).* Reversed by the SRS —
  cannot meet fidelity/validation, and the spec mandates a backend. See
  [superseded Decision A](#superseded-decisions-pre-srs-pivot).

**Consequences.** A network hop sits between compute and render; the streaming
contract must be efficient and well-specified (Decision 10). Local dev runs all
components via Docker Compose (Decision 17).

## 10. State-streaming contract: REST + WebSocket + CZML

**Context.** SRS §4.3.1 (REST for scenario CRUD), §4.3.2 (WebSocket for
time-synchronized state), §4.3.3 (OpenAPI 3.x), §3.8.5 (global view consumes
CZML). Both views must stay synchronized (§3.11.4).

**Decision.**
- **REST** (OpenAPI 3.x documented) for scenario CRUD, import/export, and
  analysis requests.
- **WebSocket** for time-synchronized state streaming during playback.
- **CZML** as the payload for global-view time-dynamic data.
- A compact **relative-state stream** (positions/velocities in LVLH + events)
  for the proximity view.

This contract is the decoupling seam of Decision 9 and carries both streaming
modes of Decision 13 (shared catalog feed; per-user scenario feed).

**Why.** REST/WebSocket/OpenAPI/CZML are all spec-named. CZML is Cesium's
native time-dynamic format; its `SampledPositionProperty` model fits both
load profiles — backend ships sample chunks, Cesium interpolates client-side
between samples at 60fps. The relative-state stream keeps the proximity view
independent of CZML and of Cesium specifics.

**Alternatives considered.**
- *Polling REST for state.* Too coarse for 60fps sync; WebSocket is specified.
- *One payload format for both views.* CZML is Cesium-shaped; forcing the
  three.js view to parse it couples them. Separate streams keep them decoupled.

**Consequences.** Backend emits two stream shapes from one propagation result.
Versioned contract; the frontend buffers and interpolates between samples for
smooth playback (echoing the old worker-interpolation idea, now across the
network).

## 11. Single authoritative simulation clock

**Context.** SRS §3.3.1 requires a single authoritative simulation clock;
§6.1.3 requires both viewports share it; §3.3.4 requires 0.01x–10000x time
scaling including reverse; §3.3.2–3 require UTC + leap seconds and J2000.

**Decision.** One authoritative simulation clock. The **frontend owns playback
control** (play/pause/scrub/rate/direction); the **backend is the authority on
time-tagged state** (it propagates at the requested epochs and tags every
sample). Both Cesium and three.js views read the same clock slice in the store.
Time scales/frames use Orekit's `TimeScalesFactory` (UTC w/ leap seconds, TAI,
J2000) on the backend.

**Why.** Confirms and extends the earlier "single source of truth for time"
choice — now validated by the SRS. Frontend-owns-control keeps the UI
responsive; backend-owns-state keeps physics authoritative. Orekit handles leap
seconds and time-scale conversions correctly (avoids the JS `Date` leap-second
gap).

**Alternatives considered.**
- *Per-view clocks.* Causes desync; spec forbids (§3.11.4).
- *Backend drives the clock tick.* Adds latency to play/pause/scrub; let the UI
  own control and request state.

**Consequences.** Scrubbing requests state at the new epoch; playback streams
state forward/backward at the chosen rate. Both views update from the same
stream, staying in lockstep.

## 12. Frame management: canonical utility, frame-tagged states

**Context.** SRS §3.2: support ECI (J2000/ICRF), ECEF (ITRF with polar motion
+ UT1-UTC), LVLH and RIC centered on the chief, and per-spacecraft body frames
from attitude quaternions; §3.2.5 every state tagged with its frame; §3.2.6 a
single canonical transformation utility; §5.2.4 transforms precise to 1e-9.

**Decision.** All transforms go through **one canonical frame utility backed by
Orekit's frames** (which include IERS Earth-orientation data). Every state
vector carries an explicit frame tag. Supported frames: ECI(J2000/ICRF),
ECEF(ITRF), LVLH/RIC(chief-centered), body-fixed(per-spacecraft, quaternion).

**Why.** Frame bugs are the dominant error class in this domain; a single
audited conversion path plus mandatory frame tags makes mismatches detectable
and prevents silent errors. Orekit already provides IERS-accurate frames to the
required precision.

**Alternatives considered.**
- *Ad-hoc conversions at call sites.* The classic source of frame bugs.
  Rejected.
- *Hand-rolled frame math.* Won't meet §5.2.4 precision with IERS corrections.

**Consequences.** Confirms/extends the earlier RIC and ECEF choices, now under
one utility. The frontend receives pre-tagged state and converts for display
via a thin client mirror of the same conventions.

## 13. Two propagation/streaming modes: shared catalog + per-user scenarios

**Context.** The system serves two very different load profiles:
- **Catalog browse** — all ~14,500 active satellites in real time, the same
  view for every viewer ("the sky right now"). Used for discovery and as the
  composition path for scenarios (click a real satellite → add to the
  scenario being built).
- **Scenario analysis** — per-user, ≤10 spacecraft (SRS §5.1.1), high-fidelity
  propagation over a custom time range with hypothetical maneuvers.

Doing both with per-user propagation wastes compute (every user re-computing
the same global catalog). Treating them with one streaming model is also wrong
(catalog can't be expressed as one user's scenario).

**Decision.** The backend serves two propagation/streaming modes:

| Mode | Spacecraft | Engine | Stream | Cached? |
|---|---|---|---|---|
| **Catalog** | All ~14,500 active | SGP4 (Orekit) | One shared CZML feed | Yes — computed once per refresh cycle, broadcast to every viewer |
| **Scenario** | Chief + ≤10 deputies | Selected fidelity (SGP4 / numerical / CW) | Per-user CZML + relative-state | No — per user, per scenario |

Both modes flow through the same streaming contract (Decision 10); the catalog
is effectively a degenerate, shared scenario.

**Why.** Catalog propagation is naturally shareable — every user looking at
"now" wants the same data. One computation per refresh cycle, fan-out to many
viewers, is dramatically cheaper than per-user. Scenarios are inherently
per-user (custom time ranges, fidelity, maneuvers), so caching doesn't apply.

The dual model also matches the UX: the catalog is *how you compose*; the
scenario is *what you save and analyze*. Catalog rendering at ~14,500 dots is
the right default (no artificial cap), and search/filter/hit-padding handle
click precision in dense regions.

**Alternatives considered.**
- *Per-user catalog propagation.* Wasteful; one shared cache is the clear win.
- *Skip the catalog altogether, scenario-only.* Loses the click-to-compose
  discovery UX and the "look at all the satellites" visual context for the
  global view.
- *Cap the catalog at a smaller number (e.g., 2k).* Render-side, Cesium handles
  14,500 fine; bandwidth is manageable with CZML sample-and-interpolate;
  capping would only hurt the experience.

**Consequences.**
- The catalog browser is a **first-class feature**, not a relic of the
  pre-pivot plan. Phase-1 scaffold's catalog UI is repurposed (constellation
  filters, stats), not removed.
- Backend has two execution paths: a shared catalog refresher (driven by a
  scheduler) and per-user scenario propagators (driven by connection
  lifetimes).
- Catalog stream is broadcast (one CZML chunk fan-out); scenario streams are
  per-user (own WebSocket session per scenario).
- Click-to-compose: click a satellite in the catalog → designate as chief if
  none yet, otherwise add as deputy in the in-progress scenario.

---

# Data

## 14. Scenario data model: chief + deputies

**Context.** SRS §2.2.1: a scenario is one chief spacecraft, one or more
deputies, initial states, a maneuver plan, attitude profiles, and sensor
configs. §3.10: persist, edit, duplicate, delete, version. §3.1.8: per-scenario
fidelity.

**Decision.** A scenario is the central artifact:
```
Scenario
 ├─ metadata (id, name, owner, version, author, timestamps)
 ├─ time range + propagator fidelity selection
 ├─ chief        (initial state, attitude profile)
 ├─ deputies[]   (initial state, attitude profile, maneuver plan)
 ├─ sensors[]    (type, FOV geometry, range limits, mounting/pointing)
 └─ events/analysis config (miss-distance thresholds, constraints)
```
Persisted in PostgreSQL (Decision 8), versioned with author + timestamp.

**Why.** Mirrors the SRS operational concept directly. Chief-relative framing
(everything expressed relative to the chief) matches the LVLH/RIC analysis
(Decision 12) and the proximity view (Decision 4).

**Alternatives considered.**
- *Flat catalog of equal satellites (the old tracker model).* Doesn't capture
  the chief/deputy relationship RPO analysis is built on. Superseded — but
  catalog browsing survives as the *composition path* (Decision 13).

**Consequences.** The whole analysis UI is scenario-scoped. Loading a scenario
configures both views, the clock range, and the analysis pipeline. Creation is
either by click-to-compose from the catalog (Decision 13) or by form/import
for hypothetical spacecraft.

## 15. Data formats: TLE, CCSDS, Keplerian, CZML

**Context.** SRS §3.10.3–4 import from TLE, CCSDS OEM/OPM, Keplerian; §4.1
ingest TLE (file/URL) + CCSDS OEM/OPM + AEM attitude; §4.2 export OEM, JSON/CSV
events, PNG/MP4; §3.8.5 CZML for the global view.

**Decision.** Use Orekit's CCSDS support for OEM/OPM/AEM parse+write; accept TLE
(CelesTrak/Space-Track or upload) and Keplerian elements as initial-state
inputs; generate CZML for Cesium; export events as JSON/CSV and views as
PNG/MP4 (client-side capture).

**Why.** Orekit already implements the CCSDS suite — no parser to write. These
are the spec-named interchange formats used across flight dynamics.

**Alternatives considered.** Hand-rolled CCSDS parsers — needless given Orekit.

**Consequences.** Import/export is a backend concern (Orekit); CZML generation
sits at the streaming boundary (Decision 10); media export (PNG/MP4) is
client-side from the rendered canvases.

---

# Cross-cutting / enterprise

## 16. Enterprise posture: professional-grade from the start

**Context.** Project may go professional; chosen posture is to build so a
rebuild is never needed (the cross-cutting concerns — auth, audit,
reproducibility — are the expensive-to-retrofit ones).

**Decision.** Build the **architecture** to professional grade now, even where
the heavy *implementations* are sequenced later. Concretely, from day one:
- **Auth seam:** all API traffic through one Spring Security pipeline; real
  OIDC/SAML + RBAC wired when needed, not stubbed-then-rethreaded.
- **Ownership in the data model:** scenarios carry an `owner` + permissions
  from the first migration (Decisions 8, 14).
- **Single mutation path** for scenarios → the audit-log hook point (§5.4.2).
- **Deterministic propagation:** seeded dispersions, pinned Orekit settings →
  reproducibility (§5.4.1) holds by construction.
- **12-factor config** → cloud/on-prem portability (Decision 17).

**Why.** These seams cost ~a day of discipline now and turn a painful
cross-cutting retrofit into additive work. Validated accuracy is already
covered by Orekit (Decision 7), so "professional" here is mostly the wrapper.

**Alternatives considered.**
- *Defer everything incl. seams.* Risks a partial backend rewrite to add auth
  later. Rejected given the stated posture.

**Consequences.** Slightly more upfront structure (security pipeline, audit
layer, migrations). Conversion to full professional = implement real IdP/RBAC +
write the §5.2 validation suite + deployment hardening — additive, weeks not a
rewrite.

## 17. Deployment: containerized, cloud + on-prem

**Context.** SRS §6.2.1 containerized services; §6.2.2 cloud and on-prem.

**Decision.** Each component is a container (backend, frontend, PostgreSQL).
Docker Compose for local/dev; the same images deploy to cloud or on-prem.
Config via environment (12-factor).

**Why.** Spec-mandated and the only sane way to run a multi-service system
reproducibly across environments. (Note: this reverses the earlier
"static-only, no containers" stance, which was correct for the old
frontend-only scope and wrong for this one.)

**Alternatives considered.** Static-hosting the frontend only — insufficient;
there's a stateful backend + DB now.

**Consequences.** Docker is a hard dependency for development. TLS terminates
at the ingress/reverse proxy (§5.5.3). On-prem packaging is a later
deliverable, but nothing in the design blocks it.

---

# UX / interaction

## 18. Global-view camera: click-to-inspect, double-click focus

**Context.** The global view shows ~15,500 small moving dots. Users need to
(a) inspect one satellite's details and (b) point the camera at one and watch
it move — without losing their place. Cesium offers `viewer.trackedEntity`,
which orbits-and-follows an entity, but setting it *snaps* the camera to a pose
Cesium computes (and, for fast objects, in a velocity-aligned frame it picks
automatically). This wiring was the single most-iterated part of Phase 2.

**Decision.** Two distinct gestures on the global view:
- **Single-click = inspect only.** Selects the satellite: info panel populates,
  a yellow highlight ring tracks it. **The camera does not move.** (Matches the
  "click-to-inspect, then commit" UX pattern in
  [use-cases.md](./use-cases.md).)
- **Double-click (or search-Enter) = focus.** A ~0.8 s smooth animation
  recenters the satellite **without changing zoom** (keeps the camera's current
  distance), then hands off to `viewer.trackedEntity` so the user can orbit
  (drag) and zoom toward it (scroll). A "Reset view" button releases tracking
  and flies home.

Two implementation choices make the focus twist-free:
1. **Converge to the live tracked pose.** Each frame the focus animation
   computes the *exact* pose tracking would have for the satellite's *current*
   position (the same `camera.lookAtTransform(enu, offset)` call EntityView
   makes) and blends the camera toward it. At animation end the camera already
   equals the tracked pose, so engaging `trackedEntity` changes nothing.
2. **Force the ENU tracking frame.** `entity.trackingReferenceFrame =
   TrackingReferenceFrame.ENU`. By default Cesium auto-selects a velocity
   (VVLH) frame for fast objects; converging to an ENU pose and then engaging
   in a VVLH frame is what produced the persistent orientation "twist". Forcing
   ENU makes both sides use the identical, position-only frame.

These are **not redundant** — they fix two independent problems, and as
implemented neither works alone. Think of two axes: *which* pose to aim at, and
*actually hitting it* while the target moves.
- #2 fixes a **frame-type mismatch.** The animation must compute a target pose,
  and it does so in ENU; tracking, left to its defaults, engages a satellite in
  VVLH — *a different orientation entirely*. #2 forces tracking into ENU so both
  sides mean the same thing.
- #1 fixes a **moving-target/timing mismatch.** The ~0.8 s animation runs while
  the satellite travels ~6 km and its frame rotates. Aiming at a pose computed
  once at the start would leave the camera a step behind when tracking engages —
  a small jump whose size varies with orbit geometry (worst near the poles),
  which is exactly the *intermittent* twist seen in earlier attempts. #1
  recomputes the target every frame so the camera lands on the live pose.
- **Neither alone:** #2 without #1 → frames agree but the camera lands on a
  stale (start-of-focus) pose → residual twist. #1 without #2 → the blend lands
  perfectly on the *ENU* pose, then tracking snaps to the *VVLH* pose → twist.
  They're a matched pair: #1 picks an ENU pose to aim at; #2 makes tracking
  actually use that ENU frame.

**Why.** Inspect-without-moving keeps dense-region exploration calm and matches
the documented UX pattern. Double-click focus matches familiar tools (e.g.
LeoLabs): centering is automatic, zoom stays manual. The twist was traced — by
reading the installed Cesium `EntityView` source — to a frame mismatch at the
fly-then-track hand-off, not a Cesium limitation; the fix is exact, not a
workaround.

**Alternatives considered.**
- *Default `trackedEntity` (instant snap).* Jarring; auto-zooms to a
  bounding-sphere distance and twists into the VVLH frame.
- *`camera.flyTo` to the entity's bounding sphere, then track.* Inconsistent
  framing (the time-dynamic sphere encloses a variable slice of the orbit) and
  the same hand-off twist.
- *A fully custom camera controller (no `trackedEntity`).* Full control but
  reimplements orbit/zoom — unnecessary once the frame mismatch was identified.

**Consequences.** Focus is smooth, centered, twist-free, and preserves zoom.
Implemented in [`frontend/src/components/Globe.tsx`](../frontend/src/components/Globe.tsx)
(the focus `useEffect`). One known trade-off: an ENU follow can **slowly roll**
over a long pass as the satellite crosses latitudes (smooth, not a snap). If
that becomes undesirable, switch the tracking frame to `VELOCITY` and match the
animation target with `Transforms.rotationMatrixFromPositionVelocity` (the
exact basis Cesium's velocity frame uses).

**Addendum (persistent path markers + line restyle).** The yellow selection
ring is a *single* entity that follows the last click, but orbit paths are
toggle-on and *persist* across clicks — so after clicking a second satellite the
first's path had no anchor and was hard to relocate. Fix: every satellite with a
shown path gets a persistent, **path-colored** marker that live-tracks it — a
solid core dot plus a sonar-ping ring (an expanding/fading `CallbackProperty`
loop). Yellow ring = current selection; colored pulse = has a path shown (they
coexist). The catalog orbit polyline was also restyled to match the scenario
trails (fine `dashLength: 6`, was `16`). One trap this introduced and closed: the
markers sit *over* the dot with `disableDepthTestDistance: Infinity`, so a click
lands on them — `pickSatellite` now resolves the `orbit-dot-`/`orbit-pulse-<id>`
ids back to the canonical `sat-`/`scn-` entity, or inspect **and** double-click
focus silently break on any satellite whose path is shown.

**Addendum (Galileo constellation classification).** `constellationOf` keyed
constellations off a name *prefix*, but the catalog names Galileo satellites
`GSAT0XXX (GALILEO NN)` — the `GALILEO` token is in the parenthetical, never the
prefix — so `startsWith('GALILEO')` matched **zero** of the 33 present, and the
Galileo filter showed nothing (they rendered as unclassified dots). Matched
`includes('GALILEO')` instead (verified no false positives against the seed). The
other five matchers are correct against the real data (GPS satellites are named
`NAVSTAR …`, which the existing prefix match catches).

---

## 19. Scenario body schema v1 + persistence design (Phase 3A)

**Context.** Phase 3A makes the scenario the real, persistent artifact (UC-1,
US-SCN-03/11/12) on the existing SGP4 engine. The `V1__init.sql` schema (users /
scenarios / scenario_versions / audit_log, jsonb body, the circular
`scenarios.latest_version_id` ↔ `scenario_versions.id` FK) was already in place;
this decision records how it's mapped and exercised.

**Decision.**
- **`jsonb` body schema v1** is an immutable typed record `ScenarioBody`:
  `{ schemaVersion:1, fidelity, timeRange{start,end},
  chief{role,noradId,name,initialState{kind:"tle",tle{line1,line2,epoch}}},
  deputies[…] }`. Each role stores **both** the NORAD id (the composer's join
  key → display names via the frontend catalog index) **and a frozen TLE
  snapshot** captured at compose time, so a saved scenario is reproducible and
  does not drift when the 6-h catalog refresh replaces the in-memory TLE (SRS
  §5.4.1). The body is mapped as a raw `String` (`@JdbcTypeCode(JSON)`) so the
  evolving schema stays out of the `ddl-auto=validate` entity; `ScenarioService`
  owns (de)serialization.
- **Single mutation path** (`ScenarioService`, Decision 16): create/update/
  delete are each one `@Transactional` that writes the version row **and exactly
  one `audit_log` row** together. Versions are immutable (`version_no = max+1`);
  **delete is soft** (`deleted_at`, added by `V3`) so history + audit survive.
  UUID PKs are **service-generated** (`UUID.randomUUID()`, entities implement
  `Persistable` so `save()` does `persist()` not `merge()`) — we need ids before
  insert to resolve the circular FK in one transaction.
- **Fidelity dispatch seam:** the body carries `fidelity`; Phase 3A honors only
  `"sgp4"`. `numerical` (3B) and `cw` (Phase 5) slot in without a schema change.

**Why.** Freezing the TLE is the cheapest way to meet Frank's reproducibility
bar without an OEM pipeline yet. One audited mutation path + immutable versions +
soft-delete is the Decision-16 posture made concrete. Plain-UUID FK fields (not
`@ManyToOne`) suit `open-in-view=false` and avoid lazy-association traps.

**Alternatives considered.** Storing only the NORAD id (rejected — drifts on
refresh); a typed `@Embeddable`/columns for the body (rejected — fights the
evolving schema against `validate`); DB-generated UUIDs (rejected — can't
resolve the circular FK before insert); hard delete (rejected — cascade-wipes
versions and orphans the audit trail).

**Phase-3A dependency decisions** (CLAUDE.md "ask before adding" — taken with
the user):
- **`spring-boot-starter-validation`** — `@Valid` request DTOs; field
  constraints surface in OpenAPI so the generated client is contract-aware.
  Semantic rules (deputies-require-chief, `end>start`, NORAD resolvable) stay in
  the service → 422.
- **Testcontainers (Postgres)** — `@DataJpaTest` + full-context tests run against
  a real ephemeral Postgres (the schema uses PG-only `TEXT[]` / `jsonb` /
  `gen_random_uuid()` that H2 can't emulate). `./gradlew test` now needs a Docker
  daemon; the existing pure-JUnit prop/catalog tests are unaffected.
- **springdoc-openapi 2.6.0 → 2.8.9** (version bump, not a new dep) — 2.6.0 is
  built against Spring Framework 6.1 and throws `NoSuchMethodError` on
  `ControllerAdviceBean` the moment a `@ControllerAdvice` exists (it walks advice
  beans to compute generic responses), 500-ing `/v3/api-docs` under Spring Boot
  3.5 (Spring 6.2). 2.8.x targets Boot 3.5.

**Consequences.** The frontend `gen:api` produces typed scenario paths +
`ScenarioBody`. CCSDS OEM + Keplerian initial-state sources are deferred (later
phase); live scenario streaming on load is Phase 4. The body schema is
versioned (`schemaVersion`) so future shapes migrate forward additively.

---

## 20. Numerical propagator: pinned settings + fidelity dispatch (Phase 3B)

**Context.** Phase 3B adds the high-fidelity numerical propagator Frank must
trust (US-PROP-02; SRS §3.1.2–6) and the LVLH/RIC frames (US-FRAME-02;
§3.2.3–4), and stands up the fidelity-selection seam (§3.1.8). It is
**backend-only** — no UI, no streaming, no contract/schema change; Phase 4
*consumes* this engine. Built on Orekit 13.1.5; the bundled `orekit-data`
already carries the space-weather, gravity, and ephemeris files needed.

**Decision.**
- **Pinned settings, in code.** `PropagationSettings.DEFAULT` (500 kg, 1 m²,
  Cd 2.2, Cr 1.8, gravity 16×16, DP8(7) position tolerance 1e-3 m, steps
  1e-3…300 s) is a constant — never read from the environment — so propagation
  is deterministic by construction (SRS §5.4.1, R11). These describe a generic
  spacecraft and **move into the scenario body in Phase 4** (when they become
  user-tunable + reproduced across users, R11's "settings in the body" applies).
- **Force model.** Orekit `NumericalPropagator` + Hipparchus
  `DormandPrince853Integrator`, Cartesian orbit type, seeded from the TLE's ECI
  (EME2000) state at its own epoch. Forces: `HolmesFeatherstoneAttractionModel`
  (≥J4), `NRLMSISE00` drag (CSSI space-weather), `SolarRadiationPressure`,
  Sun+Moon `ThirdBodyAttraction`. The harmonic model supplies only the
  **non-central** field; Orekit's `setInitialState` auto-adds the Newtonian
  central term from `orbit.getMu()` — so the orbit is seeded with the **gravity
  field's own μ** (not WGS84 μ) to keep the central and non-central parts
  consistent (and the run reproducible). No double-counted central term.
- **Fidelity enum lives in the propagation layer only.** `Fidelity.fromString`
  parses the persisted `ScenarioBody.fidelity` **string** (untouched, Decision
  19); unknown/blank → `SGP4` (safe default). `PropagationService.propagatorFor`
  dispatches `SGP4`→SGP4, `NUMERICAL`→numerical; `CW` throws
  `UnsupportedOperationException` (Phase 5). `sample()` returns a uniform ECI
  `StateVector` for either engine, so downstream never branches on fidelity.
- **Frames stay Orekit `Frame` objects** (Decision 12 holds — no enum/string
  tag, no `StateVector` change). LVLH/RIC are `LocalOrbitalFrame`s. In Orekit
  13.1.5 `LOFType.LVLH` ≡ `LOFType.QSW` (X = radial-out, Z = orbital momentum),
  which **is** this project's glossary R/I/C — explicitly **not** `LVLH_CCSDS`
  (the nadir-down convention CCSDS/Wertz/FreeFlyer use). The choice is pinned by
  an orientation test that asserts a known displacement lands on the expected
  **signed** axis (radial→+R, in-track→+I, cross-track→+C); a closed-loop test
  alone is rotation-invariant and would not catch a flipped convention (R15).

**Why.** Pinning settings in code is the cheapest path to determinism before
they're user-facing. Keeping the `Fidelity` enum out of the persistence layer
preserves the evolving-string body (Decision 19) and the `ddl-auto=validate`
posture. Seeding with the field μ removes a silent central/non-central
mismatch. Sign-pinned frame tests close the R15 gap the closed-loop test left
open.

**Alternatives considered.**
- *Settings in the scenario body now.* Rejected — no per-scenario propagation
  consumer until Phase 4; pinned constants are simpler and equally deterministic
  meanwhile.
- *Fidelity enum persisted in the body.* Rejected — fights the evolving
  jsonb-string schema; parsing in the prop layer is additive.
- *WGS84 μ for the seed orbit.* Rejected — inconsistent with the gravity
  field's μ used by the harmonics; a small but avoidable error.
- *Using `LOFType.LVLH_CCSDS` (or trusting the closed-loop test for
  orientation).* Rejected — would silently flip R/I/C away from the glossary;
  the signed-axis test makes the convention explicit.

**Consequences.** `cw` is a known-but-unimplemented fidelity until Phase 5.
Default fidelity stays `sgp4` for new scenarios through 3B (numerical-by-default
flips when Phase 4 wires streaming, per US-PROP-03). The §5.2 golden-vector /
AIAA conformance suite remains Phase 10; 3B is covered by invariant +
determinism + orientation tests. `body()` is a minimal static-rotation frame
until attitude profiles arrive with sensors (Phase 7).

---

## 21. Phase-4 clock model + live catalog time-travel (Phase 4A)

**Context.** Phase 4A gave the app one authoritative simulation clock (Decision
11). A *scenario* has a finite `[start,end]` window, so its whole ephemeris is
precomputed on connect and streamed once (`scenario-czml`), and playback is pure
client-side clock math. The *catalog*, by contrast, is a shared broadcast of a
rolling ~180 s window around "now" (Decision 13) — so stepping/scrubbing the
clock in catalog mode ran off the available data and the dots froze. Users want
to step/scrub the live catalog too ("propagate the real satellites to another
time").

**Decision.**
- **One clock, two regimes.** A single rAF `clockEngine` is the only writer of
  `currentTime`; both viewports read it. The clock slice carries `bounds` and a
  catalog-only `catalogLive` flag:
  - *Scenario mode* (a scenario is loaded): full transport (play/pause/step/
    reset/reverse/rate 0.01×–10000×) inside the scenario window; the catalog
    layer is hidden.
  - *Catalog mode* (no scenario): `catalogLive=true` follows real time via the
    shared broadcast. A `● LIVE` toggle plus step ± and a ±12 h scrub bar let the
    user leave "now"; doing so sets `catalogLive=false` ("frozen").
- **Live time-travel = on-demand catalog snapshots.** When frozen, the client
  sends `{kind:"seek",epoch,windowSeconds?}` on the *same* catalog socket; the
  backend propagates the **whole** tracked set over `[epoch, epoch+window]` (past
  or future) and replies to that one session with a `catalog-snapshot` message
  (same CZML body as the broadcast, tagged distinctly). The client applies
  `catalog-snapshot` always but ignores the live `catalog-czml` broadcast while
  frozen, so the traveled view isn't yanked back to "now". Returning to live
  requests an immediate "now" snapshot, then resumes the broadcast.
- **Play-from-a-traveled-time = rolling prefetched snapshots, capped at 100×.**
  Each snapshot covers a window; when the user plays forward/backward from a
  traveled instant, the client widens the requested window with the rate (so
  there's prefetch headroom) and re-requests the next window *before* the clock
  reaches the current one's edge — continuous motion without per-frame requests.
  The live playback rate is **capped at 100×** (vs 10000× for scenarios) so the
  per-user bandwidth stays bounded (≲1 MB/s at the cap).

**Why.** Snapshots are the cheap, correct fit for *stepping* (one pass per jump,
~1 MB gz) and, rolled with a rate-scaled window + prefetch, also give smooth
*play-from-a-point* at realtime up to 100× without a standing per-frame stream.
The shared realtime broadcast stays the default "everyone watching now" path
(Decision 13 intact for the common case). **Uncapped** fast-forward of the whole
catalog was rejected — beyond ~100× the window/refresh rate needed to keep up is
multi-MB/s for ~15.5 k sats, more than the value of it.

**Alternatives considered.**
- *Widen the broadcast window so steps stay client-side.* Rejected — a useful
  ±hours window at a fine step is tens of MB per broadcast, and a coarse step
  makes LEO interpolation inaccurate.
- *Per-user continuous catalog propagation over the whole window.* Rejected —
  the payload/compute the shared-broadcast model (Decision 13) was designed to
  avoid; snapshots give time-travel without it.
- *Re-center the shared broadcast for a seeking user.* Rejected — the broadcast
  is shared; one user's time-travel must not move everyone's "now".

**Consequences.** This adds a **per-user** catalog computation path — a bounded,
deliberate extension of Decision 13 (the broadcast still serves the live default).
Stepping/scrubbing shows a static snapshot; playing from a traveled time animates
via rolling windowed snapshots up to the 100× cap (above that you'd return to
live). The inbound `seek` carries an optional `windowSeconds` (clamped server-side
to ≤2400 s) and is handled on the catalog socket via a callback the catalog
service registers (no service↔handler cycle). Verified end-to-end: a seek returns
a `catalog-snapshot` at the chosen epoch, and a wider `windowSeconds` yields
proportionally more samples.

**Addendum (compose over a scenario — catalog refresh, Phase 6 UX).** A scenario
hides the catalog layer (its live ~180 s window can't represent the scenario
epoch). A `showCatalogInScenario` toggle (a floating globe button, off per load)
re-shows it so the user can pick a real satellite to add as a deputy mid-edit.
Because the scenario drives the clock, the revealed catalog is refreshed via the
**same `catalog-snapshot`/`seek` machinery** as live time-travel — the only change
was relaxing the time-travel subscription's `if (loadedScenario) return;` guard for
this case. **Tier A** (chosen over continuously animating the catalog): refresh the
dots + any shown orbit paths only when the clock **settles** — on reveal, pause,
step, or scrub (debounced 120 ms) — **never per playback tick**. Rationale: a
full-catalog snapshot is the bounded per-user compute path above, and scenarios play
up to 10000× (vs the catalog's 100× cap), so per-tick refresh would blow up
bandwidth; composition happens while paused/scrubbed anyway. Consequence: while a
scenario *plays*, revealed catalog dots animate only within the last snapshot window
then hold — pause or scrub to re-sync. Single-click path/marker toggling now branches
on the picked entity (`scn-` member → CZML trail; `sat-` → on-demand catalog path +
pulse) rather than on scenario-loaded state, so a revealed catalog satellite gets its
pulse + orbit path.

---

## 22. Distance-vs-time graph: tab-in-readout, no-dep SVG, windowed (Phase 5)

**Context.** The relative readout (Decision-5 refs/rAF, Phase 5A) shows the
*current-instant* chief↔deputy range numerically. Maya wanted to see how the
distance *evolves over the scenario* (when it closes, where the closest approach
is) without cluttering an already panel-dense UI. The full per-deputy LVLH
time-series is already buffered client-side (`relativeBuffer`), so no backend or
contract change is needed — distance is `hypot(R,I,C)` per sample.

**Decision.**
- **Placement: a `Table | Graph` tab inside the existing relative readout**, not a
  new panel — zero added screen real estate, reuses the panel's collapse chrome,
  color palette, and rAF loop. Tab choice persists in localStorage (`usePanelTab`,
  beside `useCollapsed`).
- **Rendering: hand-rolled SVG, no charting dependency** (CLAUDE.md "ask before
  adding deps"; the dataset is small, the panel theme is bespoke). Static curves are
  computed once per data/view change; the moving "now" cursor is driven imperatively
  from the readout's throttled rAF loop (Decision 5 — high-freq data never touches
  React/Zustand).
- **Windowing over fit-everything.** A span toggle (1h/6h/1d/7d/All) + pan scroller +
  "follow" keep only part of long, many-orbit scenarios on screen so individual
  orbits read as crisp lines. The `All` overview falls back to a **min/max envelope
  band** (per-pixel-column) when samples overplot pixels — its lower edge is the
  closest-approach trend. Log distance axis; date-aware time axis.
- **Closest-distance is explicit.** Each visible deputy gets a labeled marker at its
  **in-view sample minimum**. This is intentionally the coarse sample-grid value, not
  the backend's golden-section-refined TCA shown in the table — the frontend never
  propagates (Decision 9) and the refine is computed only for the global window, so
  the two "closest" numbers legitimately differ (graph ≥ table; gap grows with
  closing speed). Accepted as the cheap, honest in-view read.
- **Deputy filter.** A legend toggles curves on/off; the y-axis rescales to the
  *visible* set, so isolating one deputy zooms the axis onto it. Colors stay locked
  per deputy (filtering never recolors survivors); filter is graph-local.

**Why.** Tab-in-panel is the least-clutter placement; a no-dep SVG matches the lean
posture and theme and fits the refs/rAF pattern exactly; windowing is what makes
multi-week scenarios legible (a single polyline over ~460 orbits is unreadable
moiré, and the honest dense representation is the envelope band).

**Alternatives considered.** A separate/floating panel (more clutter — rejected); a
charting library e.g. uPlot (adds a dependency, canvas styling foreign to the theme,
overkill for a small series — rejected); plotting the whole duration as one line
(overplots into a mass — replaced by windowing + band); labeling the graph minimum
with the backend's refined TCA (would require per-view refine the frontend can't do —
rejected, in-view sample min accepted).

**Consequences.** `DistanceChart.tsx` + `lib/format.ts` (shared `fmtDistance`) +
`usePanelTab`. Purely additive, frontend-only. Unrelated same-change UI tidy: the
Cesium ion credit bar is hidden via CSS (`.cesium-widget-credits`) — keep visible
for any real deployment using ion imagery (ToS), fine for internal/dev.

---

## 23. Proximity scene: procedural models + derived orientation + correct Earth backdrop (Phase 6)

**Context.** Phase 6 (SRS §3.9.3/.5/.8) turns the proximity view's bare
`THREE.Points` into a scene: spacecraft 3D models with articulable parts
(US-PROX-01/02), past/predicted trajectory ribbons (US-PROX-03), camera modes
(US-PROX-04), and an Earth backdrop (US-PROX-05). Two long-deferred items came due
here: the **spacecraft 3D-model asset pipeline** and the **Earth-backdrop** choice
(both below in Deferred). No attitude exists yet (Phase 7) and no Sun vector
(Phase 8), so orientation and lighting must be honest placeholders.

**Decision.**
- **Models: procedural-first + a GLTF-swap seam.** Ship a generic procedural craft
  (box bus + two solar arrays on named hinge joints + a dish gimbal) built from
  three.js primitives, and a one-shot `GLTFLoader` that swaps in
  `/public/models/spacecraft.glb` when present, falling back to the primitive
  (R6). A fixed-pixel marker child is the far-LOD representation, so the
  pre-Phase-6 dots remain at 100 km (no regression); the model fades in by apparent
  on-screen size as you zoom, with a near-plane scale clamp.
- **Articulation: static deployed pose.** Joints exist with a rotation API ready for
  Phase 7/8, but nothing drives them — no faked sun-tracking. (US-PROX-02 "done" =
  named, deployed, articulable parts; driving them is later.)
- **Orientation: derived, labeled estimate.** `deriveBodyQuaternion` gives
  ram-pointing (nose +Y along the LVLH-frame velocity, top +Z toward +R) when the
  stream carries velocity (stride 7), and a fixed LVLH pose (nose along +I) for
  stride-4 deputies and the chief (the origin has no relative velocity). It never
  differentiates position to fake a velocity (R15-clean: it consumes the streamed
  state, derives nothing physical), and the legend marks it "estimated" until Phase 7.
  The same quaternion drives the body-frame cameras (one source of truth).
- **Ribbons: a depth-tested, sliding-window `THREE.Line` trail.** The full past+future
  path is already buffered (Decision 9 — no backend), but plotting the *whole* span is
  an unreadable multi-orbit spirograph that also smears over the Earth (the same lesson
  as the DistanceChart's windowing, Decision 22). So the ribbon is a **window** of
  ±WINDOW_SECONDS around `currentTime` — past solid, predicted dashed — selected per
  frame via `BufferGeometry.setDrawRange` (allocation-free; geometry built once).
  Coarsely-sampled long scenarios (the R8 cap raises the step → ~28 points/orbit) are
  **Catmull-Rom densified** toward ~30 s spacing so the curve reads smoothly rather than
  as faceted chords. Plain depth-tested `THREE.Line` (not the fat `Line2`): it respects
  the renderer's **logarithmic depth buffer** so the Earth occludes correctly and the
  trail no longer paints over the planet. (The earlier `Line2` + `depthTest:false`
  overlay smeared the whole ephemeris across the Earth and z-fought — replaced.)
- **Depth: logarithmic depth buffer.** The scene spans 1 m to ~100,000 km (the Earth
  backdrop); a normal depth buffer's precision collapses across that range → severe
  z-fighting (a flickering Earth) and mis-ordered geometry. `WebGLRenderer({
  logarithmicDepthBuffer: true })` fixes it across the whole range (core materials +
  `Line2`/`Line` all honor it). The Earth is a single convex sphere — no additive
  atmosphere shell (that shell z-fought the surface and read as a flickering blue haze).
- **Camera modes: one OrbitControls + a body-ride rig.** `external` (free orbit about
  the chief), `chief`/`deputy` ride the focus craft's derived body frame (offset kept
  in body space, re-applied each frame, the user's drag read back through the body
  quaternion), with an eased target transition on switch.
- **Earth backdrop: correct, from one additive stream field.** The backend adds the
  chief's geocentric radius (`chiefRadiusM`) to the `scenario-relative` envelope
  (additive — `VERSION` stays `"1"`, R12; determinism intact, R11). The view centers
  a true-scale Earth sphere at `(−chiefRadiusM, 0, 0)` along −R, so the limb is
  correct at LEO and a small disc at GEO. Procedural material + atmosphere rim (no
  texture asset — firewalled, R6) and flat **non-physical** lighting (the Sun vector
  / terminator is Phase 8). An Earth/Stars/Off toggle gives Frank "pure space".

**Why.** Procedural-first ships the §3.9.3 structure without being blocked on
licensing-clean rigged art (R6), and the GLTF seam means real models drop in later
with no rework. Deriving orientation from the velocity the stream already carries
needs no new physics and keeps the frontend non-propagating (Decision 9); labeling
it "estimated" is honest for an RPO tool where mis-reading pointing has
consequences. The full ribbon path is already client-side, so ribbons are
frontend-only. Placing Earth from the real chief radius is the only option that
gives correct limb/altitude, and it costs exactly one additive scalar.

**Alternatives considered.** Sourcing real GLTF models now (rejected — firewalled,
licensing/rigging effort could block the slice; the swap seam defers it cheaply).
Faked sun-tracking articulation (rejected — implies a Sun vector we don't have).
Fat `Line2` ribbons (tried, then rejected — `depthTest:false` smeared the whole
ephemeris over the Earth; the windowed depth-tested `THREE.Line` reads fine at 1 px
and respects the log-depth buffer) and `TubeGeometry` ribbons (rejected — world-units
width breaks across the scale range). Reparenting the camera under a craft for body
modes (rejected — fights
OrbitControls' world-space target/spherical). A frontend-only Earth at a
representative distance (kept only as the fallback when `chiefRadiusM` is absent —
inaccurate at GEO). Starfield-only / deferring Earth (rejected — the roadmap default
is "yes" for orientation context).

**Consequences.** New `frontend/src/proximity/{spacecraftModel,orientation,ribbons,
cameraModes,earthBackdrop}.ts`; `ProximityView.tsx` gains a light rig + per-craft
model groups + ribbons + the camera rig + an overlay (camera `<select>` +
Earth/Stars/Off). One additive backend field (`chiefRadiusM`); no REST/OpenAPI
change, so `gen:api` is a no-op. Known body-camera trade-off: a slow roll over a long
pass (like Decision 18's ENU follow), switchable back to `external`. Articulation
drivers (Phase 8 sun-tracking) and measured attitude (Phase 7) plug into the joints
and the orientation source already in place.

**Robustness (surfaced while validating Phase 6).** A scenario can leave the
propagator's valid domain over its time range — a maneuvered deputy propagates
numerically (Phase 5B), and decay or a maneuver can drive it **below the Earth's
surface**, where the NRLMSISE-00 drag model throws Orekit's "point is inside
ellipsoid". That previously crashed the whole per-scenario stream (close 1011 → the
client hammered reconnects, proximity view blank). Now:
- **Per-sample resilience.** `sampleRole` / `encodeRelative` catch the failure
  per step and **HOLD** the last valid position for the rest (bailing on the first
  failure — past decay every re-propagation re-fails, which is costly), so a body
  that decays *partway* still loads with its trail simply ending. The closest-approach
  refine (`relRange`) returns +∞ on a domain exit so it stays robust.
- **Clean rejection when truly unprocessable.** If a body never reaches a valid
  state in the window (`firstValid < 0`) — e.g. a degenerate maneuver — the service
  throws `ScenarioStreamUnprocessableException` → the existing **4422** close (no
  reconnect storm), the handler logs the reason, and the client surfaces a
  `scenarioStreamError` banner instead of a silent blank. (A real example found in
  testing: a saved scenario with a **12 km/s** ΔV — larger than orbital velocity —
  applied at the start; not a Phase 6 regression, just bad maneuver data the blank
  proximity view made visible. Long *valid* scenarios load fine, e.g. an 11-day SGP4
  scenario.)
- **Prevent the bad data, not just survive it.** Two maneuver-input guardrails (the
  Phase 5C templates were easy to misuse — the altitude field is an *absolute* target,
  not a delta, and Lambert will return a multi-km/s solution without complaint):
  `ManeuverTemplateService.hohmann` rejects a target below 150 km ("would re-enter")
  and the panel relabels/pre-checks it; the maneuver panel flags any deputy whose
  cumulative |ΔV| ≥ 5 km/s as far beyond a real burn (orbital speed ≈ 7.5 km/s → it
  re-enters or escapes). These are usability rails, not physics changes.
- **Rendezvous template — fix + honest limits.** `ManeuverTemplateService.rendezvous`
  was hardcoded to Lambert `nRev=0`, which is degenerate when the arrival is ≥1 orbit
  out (target wraps back near its start → a zero-rev path between near-coincident points
  costs tens of km/s). Now it solves across every feasible revolution count and keeps
  the cheapest. Two limits remain, by design (it is an open-loop two-body *sketch*, not
  a converging targeter): (a) the plan is two-body but the chief executes on SGP4 and
  the maneuvered deputy on the numerical model, so the transfer *misses* by the
  model difference (tens of km even in the ideal co-orbital case — a true rendezvous
  needs differential correction, later-phase); (b) Orekit's `IodLambert` picks a poor
  branch at some arrival times (tens of km/s) — caught by the ΔV warning. A seeded
  "Demo — rendezvous (co-orbit approach)" (chaser ~120 km behind in the same orbit;
  arrival ≈ 01:50Z → ~65 m/s, gap 120→~40 km) shows the concept honestly.

---

## 24. Sensors & FOV: modeled backend attitude + cone/rect FOV + sampled occlusion-aware events (Phase 7)

**Context.** Phase 7 (SRS §3.6 / §3.9.4 / §3.12.2; US-SENSE-01..05, US-EVT-01) makes
**sensors** first-class scenario objects and visualizes/analyzes them — Gita's UC-4
("when is the deputy inside my imager's FOV with a clear line of sight?"). A sensor's
FOV only has meaning relative to the host's **attitude**, which Phase 6 left as a
frontend-only *estimate* (R17). Two long-standing forks came due: how real attitude
should be, and the occlusion technique (a Deferred item).

**Decision.**
- **Sensor model: cone + rectangular, body-fixed.** `ScenarioBody` schema **v3** adds a
  per-role (chief OR deputy) `List<Sensor>` + `AttitudeProfile`, forward-additive exactly
  like the v2 maneuver list (null-coalesced; `parse()` re-stamps v3; no DB migration). A
  `Sensor` is `{id, kind, Fov{type: cone|rect, halfAngleDeg | hDeg/vDeg}, minRangeM,
  maxRangeM, Mount{boresightBody, clockDeg}}`. Gimbaled pointing and frustum/polygonal
  FOV are deferred (the records leave room). All edits go through the single audited
  `ScenarioService` path (`addSensor`/`removeSensor`/`setAttitude`, new audit actions).
- **Attitude: backend-authoritative + modeled.** A craft's body orientation is computed
  on the backend (the Phase-6 frontend estimate is retired) and streamed, so the FOV
  volume the client draws and the acquisition events the backend computes share ONE
  source of truth (Decision 9). `mode="lvlh"` (default) builds an LVLH-aligned frame from
  the orbital state (nose +Y = velocity/ram, top +Z = radial-out, +X = Y×Z — the same
  convention as the Phase-6 estimate, now authoritative); `mode="fixed"` holds a constant
  ECI orientation. Streamed as a **three.js-convention quaternion** — the basis→quaternion
  conversion is implemented explicitly in `FrameService` (NOT via Hipparchus `Rotation`)
  and pinned to three.js by a signed-axis test (R15), so SLERP/apply on the client matches
  exactly. CCSDS AEM (measured) attitude is deferred (a future `mode`).
- **Events: from the sampled trajectory, occlusion-aware, deterministic.**
  `analysis/SensorEventComputer` detects acquisition/loss by evaluating the predicate
  *in-FOV ∧ in-range ∧ line-of-sight unobstructed* over the **already-sampled** per-craft
  position + body-attitude (a `SampledCraft` carrying the same arrays that drive the
  rendered scene), in the **chief-LVLH scene frame**, on the sample grid + a fixed-iteration
  bisection refine (mirroring the golden-section TCA refine). It does **not** re-propagate —
  reusing the samples makes events consistent <em>by construction</em> with the drawn FOV
  and the closest approach, and is cheaper. (An earlier version re-propagated independently;
  for a maneuvered deputy — a stateful Orekit numerical+`ImpulseManeuver` propagator — the
  out-of-order grid+bisection access returned a trajectory inconsistent with the sampled
  one, so events disagreed with the drawn cone by minutes. Fixed by reading the samples; the
  guard is a maneuvered-scenario test asserting event range == sampled range at each epoch.)
  **v1 simplifications:** the FOV containment test treats both shapes as a circular cone (a
  rectangle uses its larger half-angle as a bounding cone); occlusion is **Earth-only** (ray
  vs the WGS84 sphere in the LVLH scene — inter-spacecraft blocking is negligible for
  point-like targets at these ranges); the **Sun** (occlusion + sun-keep-out) is **Phase 8**.
- **Stream: additive, `VERSION="1"` (R12).** The `scenario-relative` envelope gains a
  top-level `chief` block (`{noradId, att, sensors}` — the chief is the LVLH origin but
  has attitude + sensors that must be drawn), per-deputy `att` (stride-5 quaternion
  samples) + `sensors` descriptors, and a top-level `events` array. All optional; older
  clients ignore them. Determinism (R11) holds — no wall-clock/RNG, fixed iteration counts.
- **Frontend.** `proximity/sensors.ts` builds translucent cone/rect FOV volumes attached
  under each craft's `root` (true-metre, ride the body frame); `orientation.ts`
  `bodyOrientationAt` consumes the streamed quaternion (SLERP) and falls back to the
  derived estimate, flipping the legend "estimated"→"modeled"; `cameraModes.ts` gains a
  `sensor` mode (anchor behind the apex, look along the boresight); `Timeline.tsx` draws
  AOS/LOS windows; `SensorPanel.tsx` adds/removes sensors + sets attitude through the
  audited store actions, with **type presets** (narrow imager / wide 20°×15° / rendezvous
  lidar) so authoring stays fast (personas "<2 min").

**Why.** Backend-authoritative modeled attitude is the smallest change that makes sensor
events trustworthy without an AEM IO pipeline, and keeping it the SAME convention as the
Phase-6 estimate means the visual doesn't jump. A circular-cone event test + Earth-only
occlusion is the bounded, honest v1 (the drawn volume is the exact shape; the AOS/LOS uses
a representative cone — documented). Reusing the sample-grid + bisection refine keeps it
inside the precompute-once architecture and deterministic. Sensor mounting is a *design
input* (TLEs carry no payload data), so the user specifies it — presets keep that quick.

**Alternatives considered.** Frontend-only attitude with FOV mounted on it (rejected — the
backend then can't compute events consistently with what's drawn, breaking the decoupling).
Orekit `FieldOfViewDetector` + `AttitudeProvider` for events (viable, but the Hipparchus
attitude/quaternion conventions are error-prone to match against the rendered volume, and
event detectors don't cover Earth/inter-spacecraft occlusion uniformly — the sampled
predicate does). Exact rectangular containment for events (deferred — needs a pinned
sensor-frame roll matching the renderer's `setFromUnitVectors`; the bounding cone is the
v1). Pulling the Sun vector forward (rejected — it belongs with Phase 8 lighting/eclipse).

**Consequences.** New `backend/.../analysis/` package (`SensorEventComputer`, `SensorEvent`,
`SampledCraft`); `FrameService` grows `bodyQuaternionInLvlh`; `ScenarioBody`
schema v3; new REST endpoints (`gen:api` regenerated). New
`frontend/src/proximity/sensors.ts` + `scenario/SensorPanel.tsx`; `relativeBuffer`,
`orientation`, `cameraModes`, `ProximityView`, `Timeline`, `useStore`, `App` extended.
This closes the Deferred "Sensor occlusion technique" item (sampled ray-vs-sphere for now).
R17 is partially resolved: orientation is now modeled + authoritative (lighting stays flat
until Phase 8). Backend 113 tests green; frontend type-check + build green; verified on the
dev stack (sensor add → 200, bad FOV → 422, WS frame carries chief/attitude/sensors, a wide
cone yields an acquisition at 565 m).

**Addendum (scenario info-panel orbital elements).** A catalog satellite's info panel showed
inclination + period (mean elements from its TLE), but a scenario chief/deputy showed only
`—`: the scenario CZML packet carried just `noradId` + `role`, so the frontend's
`readNumberProp` returned null. Closed by emitting the seed-orbit `inclinationDeg` +
`periodMinutes` (computed once from each role's frozen TLE — `Math.toDegrees(tle.getI())` and
the mean-motion period — mirroring the catalog packet) on the scenario packet's `properties`.
These are static **TLE-epoch** values, not re-derived per sample: inclination is near-constant
and period drifts only slowly (drag), so a snapshot is honest for the chief + un-maneuvered
deputies. A **maneuvered** deputy's orbit changes discontinuously at the burn, so its packet
also carries a `maneuvered` flag and the info panel labels the values "seed orbit at the TLE
epoch … doesn't reflect the post-burn orbit" rather than silently hiding them (the plane is
still an informative feasibility tell — cross-plane rendezvous is ΔV-dominated). Live
*osculating* elements (update on scrub, reflect post-burn) were rejected as not worth the
per-frame cost for an RPO tool whose working surface is the relative frame (Frank exports OEM
for that precision). Additive WebSocket-payload fields only — `VERSION` stays `"1"` (R12),
determinism intact (R11), no REST/OpenAPI change (`gen:api` is a no-op), exactly like
`chiefRadiusM` (Decision 23).

---

## 25. Sun/Moon + lighting, eclipse, conjunctions, constraints (Phase 8)

**Context.** Phase 8 (roadmap §7; SRS §3.7 / §3.11.3 / §3.12.1 / §3.12.3; US-ENV-01/02/03,
US-EVT-02/03/04; UC-5, UC-7) makes the scene *environmental*. Phase 7 left two explicit
holes (Decision 24, [R17](./risks.md)): the proximity view's lighting was **flat /
non-physical** (no Sun vector), and sensor **sun-keep-out** + Sun occlusion were deferred
"to Phase 8 with the Sun vector." Phase 8 closes those and adds the rest of §7: Sun/Moon
positions, per-spacecraft eclipse, Sun-consistent illumination, conjunction detection,
constraint checks, and the timeline annotations (eclipse bands, conjunction ticks,
violation marks) — Frank's UC-5 (eclipse/lighting) and UC-7 (conjunction screening), and
the rest of Gita's UC-4 (sun-keep-out).

**Decision.** Ride the Phase-7 architecture exactly — new sampled-trajectory computers in
`analysis/` (no re-propagation), additive `scenario-relative` envelope fields
(`VERSION` stays `"1"`), a forward-additive `ScenarioBody` schema bump (no DB migration),
all scenario edits through the single audited `ScenarioService`. Sliced 8A / 8B / 8C.
- **Sun/Moon (8A):** reuse the Orekit `CelestialBodyFactory` Sun/Moon already driving the
  numerical force model (no new dependency); sample each body's **direction in the
  chief-LVLH scene** on the render grid (`FrameService.directionInLvlh` →
  `Transform.transformVector`, rotation only — R15) and stream it (stride-4 unit vectors,
  additive). The frontend drives a real `DirectionalLight` from it, so the
  `MeshStandardMaterial` Earth shows a correct day/night terminator and the ambient/
  hemisphere fill drops low. Resolves the R17 lighting hole.
- **Eclipse (8A):** conical umbra + penumbra in **geocentric ECI**, computed by
  `EclipseEventComputer` from per-craft geocentric ECI position arrays **captured for free
  inside the existing per-step loop** (`depEci.getPosition()` for deputies; the chief's own
  state threaded out of `sampleAttitude`) — no extra propagation. A distinct
  `SampledGeocentricCraft` (field `posEci`) vs the LVLH `SampledCraft` (field `pos`)
  enforces the frame split at the type level (R15). Earth-shadow only (a satellite's lunar
  eclipse is negligible). Streamed as a top-level `eclipses` array; the frontend pairs
  ingress/egress into umbra/penumbra bands (proximity dimming via per-craft material
  `setEclipse`; timeline bands).
- **Conjunctions: both (8B + 8C).** **Intra-scenario** pairwise closest-approach in the live
  stream (`ConjunctionEventComputer`, cheap, formation safety) — every unordered pair's
  LVLH range (frame-invariant) on the grid + a golden-section refine **on the sampled
  arrays** (NOT the live propagators — consistent with a maneuvered deputy, Decision 24).
  **Catalog screening** (UC-7) as a separate request→response REST endpoint
  (`ScreeningService`, `POST /scenarios/{id}/screening?thresholdKm=…`): propagate the
  scenario craft over the window, screen vs the full live SGP4 catalog with a **two-stage
  prune** (coarse radial-shell band test — `|p1−p2| ≥ ||p1|−|p2||` — then fine sampled
  closest-approach + golden-section on survivors, parallelised), returning a sorted list +
  CSV. A **snapshot**, not a reproducible artifact (the catalog refreshes ~6 h — tagged
  with the run instant; R11 caveat).
- **Constraints: sun-keep-out + approach-corridor (8B).** `ScenarioBody` schema **v5** (v4
  was taken by measured ephemeris) adds a per-role `List<Constraint>` + an optional
  top-level `missDistanceThresholdM`, forward-additive (v1–v4 bodies coalesce to empty +
  null, re-stamped on save; no migration). `ConstraintChecker` runs on the sampled
  trajectory + attitude + Sun vector: **sun-keep-out** = angle(sensor boresight, Sun) <
  limit (completes UC-4 step 7; on a measured chief it evaluates the craft's real attitude —
  a free win from measured-data slice 2); **approach-corridor** = the target outside a
  limit-half-angle cone about the host's body +Y (ram) axis while within range (the
  `SensorEventComputer` bearing test, inverted). Emits violation-start/end events. Plume
  impingement is deferred (needs per-burn plume geometry — the `kind` record leaves room).
- **Determinism (R11):** every new pass runs in increasing-time order over the existing
  grid with fixed iteration counts (bisection 24, golden-section 60); the Sun/Moon
  ephemeris is deterministic. Catalog screening is the one documented exception (live
  catalog).

**Why.** The Phase-7 sampled-trajectory pattern already gives consistency-by-construction
with the drawn scene + cheapness; eclipse/conjunction/constraints are the same shape.
Reusing the Orekit Sun/Moon avoids a new dependency. Backend-authoritative Sun direction
means the lit FOV/keep-out the client draws and the events the backend computes share one
source of truth (Decision 9). A radial-shell prune is the cheap necessary condition that
keeps ~14,500-satellite screening tractable. Schema v5 forward-additivity matches every
prior bump (v2→v3→v4) — no migration.

**Alternatives considered.** Orekit `EclipseDetector`/`EventDetector` on the live
propagators (rejected — event detectors don't share the rendered samples, reintroducing
the maneuvered-deputy inconsistency; the sampled predicate is consistent + cheaper).
A scenario-wide single conjunction model (rejected — intra-scenario formation safety and
catalog screening are different load profiles, Decision 13; both are wanted). Reconstructing
geocentric positions from the LVLH samples for eclipse (rejected — strictly more work
re-deriving raw data already held; capture it for free). A configurable corridor axis
(deferred — v1 fixes it to body +Y / V-bar, the canonical corridor; the record can grow).
Streaming the Sun position un-normalised for the client to light from (rejected — the
direction is what lighting needs; |S| is only needed server-side for the eclipse cone).

**Consequences.** New `analysis/` classes: `EclipseEvent(Computer)`, `SampledGeocentricCraft`,
`ConjunctionEvent(Computer)`, `ConstraintViolationEvent`, `ConstraintChecker`,
`ConjunctionResult`/`ScreeningResult`/`ScreeningService`. `FrameService` grows
`sunPosition`/`moonPosition`/`directionInLvlh`; `QuaternionSamples.rotate` is promoted to a
shared util (`SensorEventComputer` + `ConstraintChecker` share it). `ScenarioBody` schema
v5 (`Constraint` + `missDistanceThresholdM`); `ScenarioService` gains
`addConstraint`/`removeConstraint`/`setMissDistanceThreshold` (audit actions
`CONSTRAINT_ADD`/`CONSTRAINT_REMOVE`/`MISS_DISTANCE_SET`). New REST: constraints +
miss-distance + screening (`gen:api` regenerated; stream fields stay WebSocket-only). The
`encodeRelative` signature grows by five additive trailing params (sun/moon/eclipses +
conjunctions/violations). Frontend: `relativeBuffer` parses the new fields + builds eclipse
intervals; `ProximityView` Sun-driven light rig + per-craft eclipse dimming;
`spacecraftModel.setEclipse` + `earthBackdrop.setEmissiveIntensity`; `Timeline` eclipse
bands + conjunction/violation marks; new `EnvironmentPanel` (constraints + threshold +
catalog-screening table/CSV). Backend **152 tests green** (was 126); frontend type-check +
build green; verified end-to-end on the dev stack (WS frame carries unit `sunVector`/
`moonVector` + eclipses + a conjunction + violations on `VERSION "1"`; constraint add → 200,
bad angle / missing sensor → 422; screening 64919 → 61 conjunctions below 50 km, sorted).
This resolves the Deferred "flat lighting" and "Sun occlusion + sun-keep-out (Phase 8)"
items (R17 lighting closed; sun-keep-out shipped). **Deferred:** plume impingement;
gimbaled sensors / frustum FOV (from Phase 7); GPU-depth occlusion; eclipse/conjunction
annotations on the **global-view** CZML (Phase 8 emits them in the relative envelope).

---

## 26. Measured-data ingestion: WOD CSV as an ephemeris-role scenario

> (Decision 25 — Phase 8, Environment & Events — is now written above; this
> measured-data feature track stays numbered 26.)

**Context.** Real flight telemetry arrived mid-Phase-8: "Whole-Orbit Data" (WOD) CSV
dumps from TELEOS-2 (1–7 Jan 2026, ~570 MB) carrying measured GNSS ECI position/
velocity and ADCS attitude quaternions. Until now every scenario role was a *modeled*
source (a frozen TLE propagated by SGP4/numerical/CW). We want to ingest measured data
as a scenario so the real craft becomes a reference to compose RPO around — and to
validate the propagators against truth (Frank, §5.2). This generalizes two deferred
items: CCSDS OEM import (Decision 19 / US-SCN-06) and CCSDS AEM measured attitude
(Decision 24 / R17).

**Decision.** Sliced (see [measured-data-plan.md](./measured-data-plan.md)); slice 1
landed the spine + measured position.
- **One internal artifact, many readers.** A reader normalizes a measured file into a
  frame-tagged `MeasuredEphemeris` (EME2000 pos/vel samples). `WodCsvReader` is the
  first reader (streaming single-pass over the stacked-block WOD format; extracts the 6
  ECI pos/vel channels, km→m, drops invalid `(0,0,0)` GNSS fixes, aligns by onboard
  timestamp); CCSDS OEM/AEM readers can later feed the same artifact. Nothing downstream
  sees the WOD format.
- **Stored outside the jsonb body.** Samples are frozen into an immutable, content-hashed
  `MeasuredDataset` (a gzipped `bytea` blob in a new `measured_dataset` table, V5); the
  scenario role references it by id via `InitialState{kind:"ephemeris", datasetId}`
  (schema **v4**, forward-additive). Deterministic blob → reproducible (R11), the
  larger-artifact analogue of the frozen-TLE snapshot (Decision 19).
- **Per-role source, not a new fidelity.** "Measured" is a property of a *role*
  (`initialState.kind`), so a measured chief coexists with TLE/numerical deputies; the
  `Fidelity` enum is untouched. `ScenarioStreamService.prepareRole` branches on `kind`
  and serves the dataset through an Orekit tabulated **`Ephemeris`** (a
  `PVCoordinatesProvider`) — the sampling/encoding/streaming pipeline is unchanged.
- **Server-path import first.** Files already land on the server; a `POST /scenarios/
  import/measured {path, noradId?}` reads server-side (path constrained to
  `orbit.import.allowed-root`). Plain JSON → the generated client works, no multipart.
  Browser upload is deferred (slice 3). NORAD is auto-resolved from the file's satellite
  name via the catalog, with an optional override.
- **Audited + edit-safe.** Import goes through the single `ScenarioService` path
  (`IMPORT_MEASURED`). `update()` now MERGES against the current body so editing a
  measured scenario preserves the ephemeris chief instead of rebuilding it from the
  catalog (which would clobber it).

**Why.** The internal artifact decouples format from use (WOD now, OEM/AEM later). A
tabulated `Ephemeris` is the cheapest correct way to serve measured states through the
existing decoupled stream (Decision 9 — the frontend still never propagates). Per-role
`kind` (vs a scenario-wide fidelity) is what lets a real chief carry hypothetical or
measured deputies. Freezing the samples (content-hashed) keeps a referencing scenario
reproducible (R11). Server-path keeps slice 1 small and avoids 570 MB multipart plumbing.

**Alternatives considered.** A scenario-wide `MEASURED` fidelity (rejected — a measured
chief must coexist with non-measured deputies). Samples in the jsonb body (rejected —
~55k samples; the body is small reproducible metadata). Convert WOD→OEM first and import
via OEM (viable and still planned as a *second reader*, but the bespoke WOD format is the
data we have now; the internal artifact means OEM is additive). Browser upload now
(deferred — multipart limits + progress + async for a 570 MB file; the reader takes an
`InputStream`, so it drops in later). Re-propagating instead of interpolating (rejected —
the measured track IS the truth; within the window we interpolate, not propagate).

**Consequences.** New `io/{WodCsvReader,MeasuredEphemeris}`, `scenario/{MeasuredDataset,
MeasuredDatasetRepository,MeasuredDatasetCodec}`, `V5__measured_dataset.sql`, a REST
endpoint (`gen:api` regenerated). `ScenarioBody` schema v4; `VERSION` stays `"1"` (R12,
WebSocket payload unchanged). Partially resolves the Deferred "CCSDS OEM import" item
(measured ephemeris generalizes it; standard OEM/AEM readers are slice 3) and sets up R17's
final resolution (measured attitude is slice 2). The dev `docker-compose` bind-mounts the
shared data folder read-only.

**Addendum (the interpolation-degree bug — a 977-billion-km orbit).** Orekit's
`Ephemeris(states, N)` fits a degree-(2N−1) Hermite (each state carries velocity) through N
nodes. At the WOD's ~5-min / ~22°-of-arc spacing, N=4 (degree-7) **overshoots violently
between nodes** (Runge phenomenon) — the nodes are exact (so `chiefRadiusM` and node-sampled
checks looked right) but interpolated points flew to ~1e11 km, drawing the orbit as huge
crossing lines with a tiny Earth. Fixed by **`EPHEMERIS_INTERP_POINTS = 2`** (cubic Hermite
per segment: position + velocity at the two bracketing samples — stable and accurate for
dense ephemeris). Pinned by a regression test (`interpolatesStablyBetweenNodes`) that samples
a circular orbit BETWEEN nodes and fails on the overshoot. Also fixed the per-sample HOLD so a
*leading* gap (the path margin before the first measured sample) retries until data starts,
while a *trailing* decay still holds (the `firstValid >= 0` guard).

**Addendum (illustrative catalog deputies).** Deputies added from the catalog onto a measured
chief use current-epoch TLEs (months from the data window), so SGP4 in the window gives a
co-planar but phase-approximate orbit — fine as an example (LUMELITE-4 / POEM-2 share TELEOS-2's
PSLV-C55 plane; LUMELITE-4 makes a real ~76 km closest approach), but a genuine measured RPO
pair needs two datasets (slice 3, R19).

**Addendum (slice 2 — measured attitude, 2026-06-22).** The measured craft now flies its **real
orientation**, not the modeled LVLH estimate (resolves R17 for measured craft; lighting stays
Phase 8). `WodCsvReader` extracts `EST_ATTD_Q1..Q4` as a **parallel attitude series** (its own
timestamps — the ADCS cadence differs from GNSS — kept raw, non-unit rows dropped);
`MeasuredDatasetCodec` stores it behind a **backward-compatible version sentinel** (leading
negative int = v2; legacy position-only blobs still decode), opaque `bytea`, no migration.
`AttitudeProfile.mode = "measured"` (set on the chief at import; resolves to the role's dataset, no
new id; shape unchanged so `schemaVersion` stays 4; `resolveRole` preserves it on edit). Streaming
**reuses the existing `"fixed"` attitude path**: `prepareEphemerisRole` converts the raw quaternions
to a body→ECI three.js series, and a shared `bodyAttitude` helper **SLERPs** it (via the extracted
`prop/QuaternionSamples`, also used by `SensorEventComputer`) and feeds it as a per-step `fixedQuat`
— so measured and modeled attitude share one code path; additive (`VERSION="1"`, R12) and
deterministic (R11).

*The convention (R15/R20) was resolved empirically*, not from a spec (none existed — only the CSV):
the quaternion is **unit and ECI-referenced** (the recurring "home" value is held at many orbit
positions = a fixed inertial attitude), `EST_ATTD` ≡ the star-tracker `STS_BF` (one convention), and
**scalar-last (Q4 = scalar), body→ECI** is favored by both the pointing-geometry analysis and the
only positive correlation in the `FOG_RATE_BF` angular-velocity cross-check. Because three.js
`(x,y,z,w)` is also scalar-last, the converter (`prop/MeasuredAttitude`) is the identity reorder
`(Q1,Q2,Q3,Q4)`, **pinned by a signed-axis test** and **flippable via two named constants** if the
dev-stack visual shows a mirror/inverse. (The gyro *magnitude* test was inconclusive — attitude is
5-min sampled while slews are fast; a data limitation, not the convention — so the final physical
confirmation is visual.) Frontend: orientation already SLERP-consumes the streamed `att`; added a
toggleable **body-axis triad** (the model is too symmetric and a far craft is just a marker dot, so
attitude was effectively invisible without it) and a "measured" legend label. Verified on the dev
stack (re-import → chief `attitude.mode=measured`; the relative frame carries the chief's varying
measured `att`).

---

## 27. Advanced maneuvers & analysis: corrector, CW templates, Monte Carlo, link budget (Phase 9)

**Context.** Phase 9 (roadmap §9; SRS §3.5 / §3.12.4–5 / §3.6; US-MAN-06..11, US-MC-01/02,
US-EVT-05; UC-6) deepens the *analysis* layer on top of the Phase 4–8 streaming/scenario
architecture. Four threads came due: (9A) the Phase-5C two-impulse rendezvous was an
open-loop two-body **sketch** that missed by the model difference (R16); (9B) Maya's
close-range maneuver templates (NMC, V-bar/R-bar hold) had no implementation; (9C) Frank's
Monte Carlo dispersion + covariance (UC-6) needed seeded RNG — the **first** randomness in
a system whose reproducibility bar is bit-identical reruns (R11, §5.4.1); (9D) Gita's link
budget / SNR (US-EVT-05) needed a per-sensor model on the rendered trajectory.

**Decision.** Ride the established patterns exactly — `analysis/` computers on the
**already-sampled** trajectory (no re-propagation; Decision 24), additive `scenario-relative`
fields (`VERSION` stays `"1"`, R12), a forward-additive `ScenarioBody` schema bump (no DB
migration), all scenario edits through the single audited `ScenarioService`. Sliced 9A/9B/9C/9D.

- **9A — flight-ready rendezvous (closes R16).** A `RendezvousCorrector` (a **differential
  corrector** against the real propagators — numerical deputy + SGP4/numerical chief) replaces
  the open-loop Lambert plan: damped Gauss-Newton (Levenberg-Marquardt `JᵀJ+λI`) + backtracking
  line search, domain-exit (`OrekitException`) fallback, and ΔV/iteration caps. The two-impulse
  rendezvous defaults to `corrected=true`; non-convergence **falls back to the open-loop seed +
  a warning in the audit summary** (not a hard 422). A `RendezvousSearchService` provides an
  **arrival × revolution** two-body Lambert ΔV grid (a serial chief-grid precompute + pure
  parallel cells) so a cheap/feasible transfer isn't trial-and-error. A `phasing(dep, revs)`
  template (two-body in-track sketch, window-guarded) covers the realistic co-elliptic approach.
- **9B — CW close-range templates + finite burns.** `prop/CwTargeting` adds analytic CW STM blocks
  (matching `CwPropagation.advance`) + `twoImpulse(r0,v0,rT,vT,n,dt)` (null at the integer-rev
  singularity). On it: `nmc(dep)` (in-track drift-cancel `vy = −2·n·x` → a closed natural-motion
  ellipse) and `hold(dep, axis, distanceM, arrival)` (CW two-impulse to a V-bar/R-bar hold point,
  zero arrival velocity). **Finite burns (US-MAN-11):** optional `thrustN`/`ispSec` on
  `Impulse`/`Maneuver` (v6-additive; null → impulsive); `PropagationService.buildManeuvered` realises
  a finite burn as an Orekit `ConstantThrustManeuver` force model of the Tsiolkovsky duration that
  achieves the requested ΔV (centred on the epoch → it collapses to the impulsive case as thrust → ∞;
  mass depleted via the rocket equation), while the impulsive case stays an `ImpulseManeuver` detector.
  CW and the impulse-equivalent treat a finite burn as impulsive at the epoch (= the centred midpoint).
  **Glideslope** (`glideslope`) discretizes a constant-closing-rate V-bar/R-bar approach into chained
  `CwTargeting.twoImpulse` legs (one corrective burn per waypoint, constant closing speed → leg
  duration scales with length) + a final park burn. **Closed-loop station-keeping** (`stationKeep`)
  holds a V-bar/R-bar point with a corrective burn each interval; uniquely among the templates it is
  genuinely closed-loop — each correction rebuilds the deputy's *real* (numerical, corrections-so-far)
  propagator, reads back its drifted relative state, and solves the CW re-aiming burn, so the burns
  counter whatever the real model (and the forced point) actually do.
- **9C — Monte Carlo + covariance (UC-6).** `analysis/MonteCarloService` perturbs the deputy's
  ECI seed (Gaussian pos/vel) + maneuver ΔV (magnitude + pointing tilt), propagates each sample,
  expresses it chief-LVLH, and aggregates the cloud + per-epoch covariance ellipsoids (Hipparchus
  `EigenDecompositionSymmetric` → ordered, sign-canonicalized, right-handed → a three.js
  quaternion via the extracted `FrameService.matrixToQuaternionXyzw`). Determinism is **preserved
  despite RNG** (R11): a **per-sample** `SplittableRandom` seeded from `mix(configuredSeed, i)`
  (SplitMix64), a **fixed intra-sample draw order**, an **index-ordered collect**, and
  canonicalized eigenvectors — so a run is byte-identical and **independent of thread/pool
  scheduling**. Default 100 samples (cap 500); the propagation pool is **bounded to ≤6** to cap
  peak memory (an unbounded common-pool run crashed the test JVM; each sample is a full numerical
  propagation, R18).
- **9D — link budget / SNR (US-EVT-05).** `ScenarioBody` schema **v6** adds an optional
  `LinkBudget` sub-record on a `Sensor` (forward-additive; v1–v5 bodies deserialize with a null
  budget; re-stamped on save; no migration). `analysis/LinkBudgetComputer` runs on the sampled
  trajectory (like `SensorEventComputer`): per (link-budget sensor ↔ target) sample, a concise
  Friis model `SNR(r) = EIRP + G/T − Lfs(r) + 228.6 − 10·log10(B)` with `Lfs = 20·log10(4π r f/c)`
  (SNR drops ~6 dB per range-doubling). Streamed additively (`linkBudgets` series, strided to a
  bounded point count). Authored via the audited `setLinkBudget` path.

**Why.** The sampled-trajectory `analysis/` pattern (Decision 24) already gives
consistency-by-construction with the rendered scene and is cheap — Monte Carlo and link budget are
the same shape. A differential corrector against the *real* propagators is the only honest fix for
R16 (a two-body plan executed under full perturbations must close the loop). Per-sample seeding +
ordered collect is what lets Monte Carlo introduce RNG without surrendering reproducibility. Schema
v6 forward-additivity matches every prior bump (v2→v3→v4→v5).

**Alternatives considered.** *Keep the open-loop Lambert rendezvous + a ΔV warning* (rejected — R16
is a correctness gap for an RPO tool; the corrector is the proper fix). *A shared RNG across MC
samples / the unbounded common ForkJoinPool* (rejected — non-deterministic collect order and a JVM
memory blow-up; per-sample seed + bounded pool fixes both). *Hard 422 on corrector non-convergence*
(rejected — the open-loop seed + audit warning is more useful than a dead end). *A user-specified
burn duration (vs deriving it from the intended ΔV)* (rejected — Maya thinks in ΔV; deriving the
Tsiolkovsky duration from ΔV+thrust+Isp keeps the existing ΔV-centric form, and centring the burn on
the epoch makes a finite burn collapse cleanly to the impulse). *Detector-specific optical NEP/QE link
model* (deferred — the Friis form with optical-band parameters is the v1; the `kind` field leaves room).

**Consequences.** New `analysis/` classes: `RendezvousSearchService`/`DvCell`/
`RendezvousSearchResult`, `MonteCarloService`/`MonteCarloResult`/`EllipsoidSample`,
`LinkBudgetComputer`/`LinkBudgetSeries`; new `scenario/RendezvousCorrector` + `LinkBudgetDraft`;
new `prop/CwTargeting`. `ManeuverTemplateService` grows `rendezvous(corrected,nRev)` / `phasing` /
`nmc` / `hold` / `glideslope` / `stationKeep` / `relativeStateLvlh`; `PropagationService` extracts `buildManeuvered` +
`propagatorFor(seed, impulses)` + the static `finiteDuration` (Tsiolkovsky) and branches it on
`Impulse.finite()`; `FrameService` extracts public `matrixToQuaternionXyzw`. `Impulse` /
`ScenarioBody.Maneuver` / `ManeuverDraft` / `ManeuverRequest` grow optional `thrustN`/`ispSec` (with
impulsive convenience ctors that keep every Phase-5B/5C call site). New REST:
`POST /maneuvers/rendezvous/search`, `/maneuvers/phasing`, `/maneuvers/nmc`, `/maneuvers/hold`,
`/maneuvers/glideslope`, `/maneuvers/station-keep`, `/scenarios/{id}/monte-carlo`, + `setLinkBudget`
+ finite-burn fields on `/maneuvers` (`gen:api` regenerated; stream `linkBudgets` stays
WebSocket-only). `ScenarioBody` schema v6. Frontend: `ManeuverPanel` (ΔV-map table + corrected
insert + phasing + close-range NMC/hold/glideslope/station-keep + a finite-burn toggle), new
`MonteCarloPanel` + `proximity/montecarlo.ts` (cloud + 3σ ellipsoid shells), `SensorPanel`
link-budget fields, `Timeline` SNR band, `relativeBuffer` parsing. **This resolves R16**
(differential corrector) and introduces the **first seeded RNG** (determinism held per above). Backend
test count **152 → 187**. **Deferred:** optical detector NEP/QE link detail; the finite-burn
ΔV-glyph burn-window animation.

---

# Superseded decisions (pre-SRS pivot)

Retained for the record. These were sound for the *public satellite tracker*
scope; the 2026-05-28 SRS pivot reversed them.

- **A. Client-only, no backend** → **superseded by Decisions 6, 9, 17.** The
  SRS mandates a backend propagation/analysis service with REST/WebSocket and
  auth. Client-only cannot meet fidelity, validation, persistence, or security
  requirements.
- **B. satellite.js as the propagator** → **superseded by Decision 7.** Orekit
  provides SGP4 plus high-fidelity propagation; one engine, no second SGP4.
- **C. Web Worker for propagation + worker boundary contract** → **superseded
  by Decisions 9, 10.** Propagation moved to the backend; the worker contract
  is replaced by the network streaming contract. (Client-side workers may
  still interpolate streamed samples.)
- **D. IndexedDB catalog cache (blob-per-group) + stale-while-revalidate TTL**
  → **superseded by Decisions 8, 13, 14.** Data is scenario-based and
  server-persisted; the catalog now flows through a shared backend stream
  (Decision 13), not a client cache.
- **E. PointPrimitiveCollection for ~15k objects** → **revised, not retired.**
  The render technique is still right — Cesium's `PointPrimitiveCollection`
  remains the way to draw the catalog efficiently. What changed: the points
  are fed by a backend stream (Decision 13), not client-side propagation;
  scenarios on top use the dual-engine model (Decision 4).
- **F. Two-body Keplerian model** → **superseded by Decision 7.** The SRS
  requires high-fidelity (DP8(7), J4+, drag, SRP, third-body) validated to
  sub-km/24h; two-body is insufficient. CW covers the close-range linearized
  case.
- **G. Relative analysis by client-side sampling** → **revised into Decisions
  7, 9, 10.** Still sampling-based conceptually, but computed on the backend
  (Orekit) and streamed; CW provides a closed-form close-range option.

The still-valid earlier decisions (single time source, RIC/LVLH frame, Cesium
for the globe, React/Vite/Zustand, CelesTrak ingestion) are carried forward and
restated above as Decisions 1–5, 11, 12.

---

# Deferred decisions

Explicitly not decided yet; each has a tracked reason.

- **§5.2 validation test suite.** Orekit is validated, but our conformance
  tests (compare to reference solutions, document per AIAA 2006-6753) are
  written when the propagation service stabilizes.
- **Real identity provider for SSO/RBAC (§5.5.1–2).** Architecture/seam built
  now (Decision 16); concrete OIDC/SAML integration chosen with deployment.
- **Monte Carlo execution model (§3.12.4).** In-process Java vs a worker pool
  vs delegated compute — decide when the analysis phase arrives.
- ~~**Sensor occlusion technique (§3.6.5).** Ray casting vs GPU depth methods —
  decide in the sensor-modeling phase.~~ **Resolved (Phase 7, Decision 24):** analytic
  ray-vs-sphere (Earth) on the backend, evaluated on the sample grid + bisection refine,
  for acquisition/loss events. Inter-spacecraft blocking is negligible (point-like
  targets); the Sun is Phase 8. GPU-depth occlusion of the drawn FOV volume can come
  later if needed.
- ~~**Spacecraft 3D model asset pipeline (§3.9.3).** GLTF sourcing, articulation
  rig conventions — decide with the proximity view.~~ **Resolved (Phase 6,
  Decision 23):** procedural placeholder craft + a `GLTFLoader` swap seam
  (`/public/models/spacecraft.glb`); articulation = named joints at a static
  deployed pose. Real model sourcing / rig conventions can land later through the
  seam without rework (R6).
- **Media export implementation (§4.2.3).** Client-side canvas capture
  (WebCodecs/MediaRecorder) vs server-side render — decide at the export phase.
- **Self-hosting Cesium imagery tiles.** Ion for now; switch at the 5 GB/mo
  ceiling.
- **Catalog refresh cadence + sample density.** Phase 2 baseline (measured,
  dev hardware): propagation pass every **30 s**, window **180 s**, step
  **60 s** → 4 samples/sat, `interpolationDegree` clamped to 3. A full pass of
  **15,501 satellites** propagates in **~100–140 ms** (warm) and produces a
  **~7.36 MB** uncompressed CZML message. Backend compute is a non-issue;
  message size is the lever. **Implemented:** the backend gzips each message
  and sends it as a binary WebSocket frame; the client inflates with the native
  `DecompressionStream`. Measured **7.36 MB → 1.15 MB (6.4×)**. This was not
  just bandwidth — the uncompressed frame could not drain to a *remote* browser
  within the send-time limit (worked over loopback, reset over the network), so
  the catalog never appeared in the browser until compression landed. Secondary
  levers if needed later: fewer samples / shorter window, or delta-encoding.
  **Resolved (Phase 2):** ~15.5k CZML Entity dots render and animate smoothly
  in-browser — confirmed by observation, not by an FPS counter — so the
  `PointPrimitiveCollection` fallback (R7) was **not** needed. Revisit with an
  actual FPS counter only if performance degrades (e.g. once the scenario layer
  is added on top).
- ~~**Earth backdrop in the proximity view.** Default yes (orientation context)
  or no (pure space) — design call when building the proximity scene
  (Decision 4).~~ **Resolved (Phase 6, Decision 23):** yes — a true-scale Earth
  sphere placed along −R from the chief's geocentric radius (a new additive
  `chiefRadiusM` stream field), with an Earth/Stars/Off toggle ("Off" = pure
  space). Flat non-physical lighting until the Phase 8 Sun vector.
- **Two-body + J2 quick model.** Not needed — Orekit's fidelity modes cover the
  range.
