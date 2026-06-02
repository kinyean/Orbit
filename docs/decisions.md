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

**Architecture & contracts**
9. [Four-component architecture, decoupled visualization](#9-four-component-architecture-decoupled-visualization)
10. [State-streaming contract: REST + WebSocket + CZML](#10-state-streaming-contract-rest--websocket--czml)
11. [Single authoritative simulation clock](#11-single-authoritative-simulation-clock)
12. [Frame management: canonical utility, frame-tagged states](#12-frame-management-canonical-utility-frame-tagged-states)
13. [Two propagation/streaming modes: shared catalog + per-user scenarios](#13-two-propagationstreaming-modes-shared-catalog--per-user-scenarios)

**Data**
14. [Scenario data model: chief + deputies](#14-scenario-data-model-chief--deputies)
15. [Data formats: TLE, CCSDS, Keplerian, CZML](#15-data-formats-tle-ccsds-keplerian-czml)

**Cross-cutting / enterprise**
16. [Enterprise posture: professional-grade from the start](#16-enterprise-posture-professional-grade-from-the-start)
17. [Deployment: containerized, cloud + on-prem](#17-deployment-containerized-cloud--on-prem)

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
- **Sensor occlusion technique (§3.6.5).** Ray casting vs GPU depth methods —
  decide in the sensor-modeling phase.
- **Spacecraft 3D model asset pipeline (§3.9.3).** GLTF sourcing, articulation
  rig conventions — decide with the proximity view.
- **Media export implementation (§4.2.3).** Client-side canvas capture
  (WebCodecs/MediaRecorder) vs server-side render — decide at the export phase.
- **Self-hosting Cesium imagery tiles.** Ion for now; switch at the 5 GB/mo
  ceiling.
- **Catalog refresh cadence + sample density.** Phase 2 baseline (measured,
  dev hardware): propagation pass every **30 s**, window **180 s**, step
  **60 s** → 4 samples/sat, `interpolationDegree` clamped to 3. A full pass of
  **15,501 satellites** propagates in **~100–140 ms** (warm) and produces a
  **~7.36 MB** uncompressed CZML message. Backend compute is a non-issue;
  message size is the lever. **Open optimization:** enable WebSocket
  `permessage-deflate` (Tomcat doesn't by default) — CZML is highly repetitive
  and should compress ~10×; biggest bandwidth win before more clients connect.
  Secondary levers: fewer samples / shorter window, or delta-encoding. Revisit
  when client count or bandwidth matters. Browser render FPS at 15.5k Entity
  dots still needs measurement (R7) — if under 30 fps, fall back from the CZML
  Entity layer to a `PointPrimitiveCollection` fed from the same samples.
- **Earth backdrop in the proximity view.** Default yes (orientation context)
  or no (pure space) — design call when building the proximity scene
  (Decision 4).
- **Two-body + J2 quick model.** Not needed — Orekit's fidelity modes cover the
  range.
