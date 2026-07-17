# User stories

The actual backlog. Each story is a discrete chunk of user-visible (or
infrastructure-visible) value, grouped by build phase from
[architecture-and-roadmap.md §7](./architecture-and-roadmap.md). Format:

> **ID — As a [persona], I want [feature], so that [benefit].**
> *Acceptance:* concrete checks for "done."
> *Maps to:* relevant use cases and SRS clauses.

**ID prefixes:**
- `US-INFRA-*` — infrastructure, dev environment, deployment
- `US-AUTH-*` — auth pipeline, RBAC, security
- `US-CAT-*` — catalog (the live shared view of active satellites)
- `US-SCN-*` — scenario CRUD, persistence, versioning
- `US-PROP-*` — propagation engine, fidelity, frames
- `US-STREAM-*` — streaming contract (REST + WebSocket + CZML)
- `US-VIEW-*` — viewports, clock, time controls, dual-view sync
- `US-REL-*` — relative motion analysis
- `US-MAN-*` — maneuvers (templates, ΔV, finite burns)
- `US-SENSE-*` — sensors, FOV, occlusion
- `US-ENV-*` — environment (Sun, Moon, eclipse, lighting)
- `US-EVT-*` — events, conjunctions, constraints, analysis
- `US-MC-*` — Monte Carlo, covariance, dispersion
- `US-IO-*` — import / export (CCSDS, OEM, OPM, AEM, CZML, CSV, PNG, MP4)
- `US-UX-*` — UI polish, tooltips, accessibility, sample scenarios

Phase 1–5 stories are written in detail. Phase 6–11 are outlined; they'll
be expanded as we approach them.

---

# Phase 1 — Project structure & dual-container dev env

### US-INFRA-01 — As a developer, I want the backend to start via Docker Compose, so that I can run the whole stack with one command.
*Acceptance:*
- `docker compose up` from project root brings up backend + Postgres +
  frontend.
- Backend exposes port 8080 inside the network.
- Frontend dev server reaches the backend by service name.
- Healthy logs; no errors at startup.

*Maps to:* SRS §6.2.1; [Decision 17](./decisions.md).

### US-INFRA-02 — As a developer, I want PostgreSQL with migrated schema available, so that the backend has a working database.
*Acceptance:*
- Postgres container starts.
- Flyway (or equivalent) runs migrations on backend startup.
- `users`, `scenarios`, `scenario_versions`, `audit_log` tables exist.
- `scenarios.owner_id` and `users.roles` columns exist (RBAC seam,
  [Decision 16](./decisions.md)).

*Maps to:* SRS §3.10, §5.4.2; [Decision 8](./decisions.md).

### US-INFRA-03 — As a developer, I want the frontend to consume an OpenAPI-generated client, so that API calls are typed and the contract is enforced.
*Acceptance:*
- Backend exposes `/v3/api-docs` (OpenAPI 3.x).
- A frontend build step generates a typed client from the spec.
- The generated client is imported and used (no hand-rolled `fetch` to backend
  endpoints in scenario/auth code).

*Maps to:* SRS §4.3.3.

### US-INFRA-04 — As a developer, I want a `GET /health` round-trip wired end-to-end, so that I can verify the stack works before adding features.
*Acceptance:*
- Backend `GET /health` returns 200 with a small JSON payload (version,
  build time).
- Frontend renders the health response on startup (small status chip in the
  top bar or footer).

### US-AUTH-01 — As a developer, I want every backend request to flow through a Spring Security pipeline, so that adding real auth later is additive, not a rewrite.
*Acceptance:*
- Spring Security filter chain is configured.
- Default policy: permit-all (no actual auth) for now.
- A `User` principal exists; in stub mode it's a fixed dev user with all
  roles. Requests have `request.userPrincipal` populated.
- Configuration is structured so swapping the stub for a real OIDC provider
  is a single configuration change.

*Maps to:* SRS §5.5.1–2; [Decision 16](./decisions.md).

### US-SCN-01 — As Maya, I want to see an empty scenario panel and composer card on first load, so that the UI shape matches the eventual workflow.
*Acceptance:*
- Scenario panel renders in the left sidebar (or chosen position) showing
  "No saved scenarios."
- Composer card renders showing "No chief designated."
- Both panels show even when no scenario is loaded; they're load-bearing
  UI primitives.

*Maps to:* [UC-1](./use-cases.md), [UC-2](./use-cases.md).

### US-SCN-02 — As a developer, I want the scenario composer state slice in Zustand, so that catalog clicks and scenario actions can update it.
*Acceptance:*
- A `composer` slice exists with shape: `{ chiefId: number | null,
  deputyIds: number[], scenarioId: string | null, isDirty: boolean }`.
- Setters: `setChief`, `addDeputy`, `removeFromScenario`, `clear`.
- Per-slice subscription pattern followed ([Decision 5](./decisions.md)).

### US-CAT-00 — As Maya, I want the existing catalog UI to keep working through Phase 1, so that I'm not staring at a broken screen during infrastructure work.
*Acceptance:*
- The Phase-0 catalog view (filter panel, stats overlay) continues to
  function against CelesTrak directly.
- No regression in visual or interactive behavior compared to Phase 0.

*Notes:* the data source moves to the backend in Phase 2 ([US-CAT-04](./user-stories.md#us-cat-04--as-a-developer-i-want-the-globe-view-to-render-the-catalog-from-the-backend-stream-replacing-the-direct-celestrak-fetch)).

---

# Phase 2 — Propagation pipeline + shared catalog stream

### US-PROP-01 — As a developer, I want the backend to propagate one TLE via SGP4 (Orekit), so that the engine is wired before we scale up.
*Acceptance:*
- A backend module loads Orekit's data files and time scales.
- Given a TLE pair, returns the satellite's state at a specified time as
  `(position, velocity, frame=TEME/ECI)`.
- Unit test compares the result to a known reference for at least one
  satellite + epoch.

*Maps to:* SRS §3.1.1; [Decision 7](./decisions.md).

### US-FRAME-01 — As a developer, I want a frame-conversion utility for ECI / ECEF / geodetic, so that all transforms go through one audited path.
*Acceptance:*
- A `FrameService` exposes: ECI↔ECEF (Orekit IERS-corrected), ECEF↔geodetic.
- Every emitted state is tagged with its frame (`@RecordFrame` annotation
  or equivalent).
- Unit tests assert known transforms (ground station coordinates round-trip).

*Maps to:* SRS §3.2; [Decision 12](./decisions.md).

### US-STREAM-01 — As a developer, I want a versioned WebSocket streaming contract defined, so that backend and frontend agree on shape.
*Acceptance:*
- Contract documented (OpenAPI/AsyncAPI or markdown spec).
- Message types: `init`, `czml-chunk`, `event`, `error`.
- CZML format used for time-dynamic positions ([Decision 10](./decisions.md)).
- Version header in every message.

*Maps to:* SRS §4.3.2, §3.8.5.

### US-CAT-01 — As a developer, I want the backend catalog service to run a periodic SGP4 pass over active TLEs, so that the shared stream has fresh state.
*Acceptance:*
- Catalog service loads TLEs from CelesTrak (or a local TLE file in dev).
- Refreshes TLEs every N hours (configurable, default 6).
- Runs an SGP4 pass over the full catalog every K seconds (configurable,
  default 30), producing a CZML chunk covering the next K + buffer seconds.

*Maps to:* SRS §4.1.1; [Decision 13](./decisions.md).

### US-CAT-02 — As a developer, I want the catalog CZML stream broadcast over WebSocket to every connected viewer, so that catalog rendering is one shared computation.
*Acceptance:*
- WebSocket endpoint `/stream/catalog` accepts connections.
- New connections receive the most recent CZML chunk on join.
- Subsequent chunks are broadcast to all connected clients.

*Maps to:* [Decision 13](./decisions.md).

### US-CAT-03 — As Maya, I want the global view to render the catalog from the backend stream (replacing the direct CelesTrak fetch), so that we have a single authoritative data source.
*Acceptance:*
- Frontend connects to `/stream/catalog` on app load.
- Cesium ingests the CZML chunks as a `SampledPositionProperty` collection.
- All ~14,500 active satellites render as dots, animating smoothly (Cesium
  interpolates between samples).
- The old `lib/celestrak.ts` client-side fetch is no longer the source of
  catalog data.

*Maps to:* [UC-1](./use-cases.md); [Decision 13](./decisions.md).

### US-CAT-04 — As Maya, I want to click a satellite in the catalog and see its details in the info panel, so that I can inspect before committing.
*Acceptance:*
- Clicking a catalog dot resolves the picked NORAD ID and updates the
  info panel.
- Info panel shows: name, NORAD ID, country (if known), launch date (if
  known), current lat/lon/alt, altitude, period, inclination.
- The click is inspection-only; composer state is unchanged.
- Hit-padding works in dense regions (click forgiveness ±5 px).

*Maps to:* [UC-1](./use-cases.md) step 3, [UX patterns](./use-cases.md#ux-patterns-referenced-throughout).

### US-CAT-05 — As Maya, I want to search the catalog by name or NORAD ID, so that I can find specific satellites quickly.
*Acceptance:*
- Search box in the catalog panel.
- Substring match on name; exact match on NORAD ID.
- Top result highlighted; pressing Enter centers the globe view on it.
- Search executes against the in-memory catalog (no network round-trip).

*Maps to:* [UC-1](./use-cases.md) step 2.

### US-CAT-06 — As Maya, I want to filter the catalog by constellation, so that I can focus on relevant objects without visual clutter.
*Acceptance:*
- Filter checkboxes for the supported constellations (Starlink, OneWeb, GPS,
  Galileo, BeiDou, Iridium).
- Toggling a constellation hides/shows its satellites within ≤100 ms.
- Filter state persists across page reloads (local storage).
- Search results are scoped by active filters.

*Maps to:* [UC-1](./use-cases.md) step 5; SRS §3.10.3.

---

# Phase 3 — High-fidelity propagation + scenario CRUD

### US-PROP-02 — As Frank, I want the backend to support high-fidelity numerical propagation, so that I can validate scenarios under realistic perturbations.
*Acceptance:*
- Backend exposes a numerical propagator using Orekit: DP8(7) integrator,
  gravity field ≥J4, NRLMSISE-00 drag, SRP, Sun + Moon third-body.
- Settings exposed for tuning: integrator tolerance, gravity field degree
  and order, drag coefficient, spacecraft area-to-mass + reflectivity.
- Propagation is deterministic with pinned settings and seeded dispersions.

*Maps to:* SRS §3.1.2–6, §5.4.1; [Decision 7](./decisions.md).

### US-PROP-03 — As Maya, I want to select propagator fidelity per scenario, so that I can pick the right speed/accuracy trade-off.
*Acceptance:*
- Scenario body carries a `fidelity` field: one of `sgp4`, `numerical`,
  `cw`.
- Scenario propagation honors the field.
- Default fidelity for a new scenario is `numerical` (Frank's instinct;
  Maya can override).

*Maps to:* SRS §3.1.8.

### US-FRAME-02 — As a developer, I want full LVLH/RIC and per-spacecraft body frames available, so that relative analysis and sensor work are unambiguous.
*Acceptance:*
- `FrameService` adds: ECI↔LVLH (chief-centered), ECI↔RIC (chief-centered),
  ECI↔body (per-spacecraft via attitude quaternion).
- LVLH/RIC computation requires a chief reference; service handles missing
  chief with a clear error.
- Unit tests assert known relative-motion examples (a deputy in a circular
  NMC ellipse appears as a closed loop in LVLH).

*Maps to:* SRS §3.2.3–4; [Decision 12](./decisions.md).

### US-SCN-03 — As Maya, I want REST endpoints to create, read, update, and delete scenarios, so that I can manage my work.
*Acceptance:*
- `POST /scenarios` creates a scenario, returns its id and v1 version.
- `GET /scenarios/{id}` returns metadata + latest version.
- `GET /scenarios/{id}/versions/{v}` returns a specific version.
- `PUT /scenarios/{id}` creates a new version (immutable history).
- `DELETE /scenarios/{id}` archives (soft-deletes).
- All mutations go through one service layer (audit hook point —
  [Decision 16](./decisions.md)).
- All endpoints documented in the OpenAPI spec.

*Maps to:* SRS §3.10.1–2; [UC-2](./use-cases.md), [UC-8](./use-cases.md).

### US-SCN-04 — As Frank, I want every scenario change versioned with author and timestamp, so that I have a complete audit trail.
*Acceptance:*
- Each mutation creates a row in `scenario_versions` with `version_no`,
  `author_id`, `created_at`.
- An entry is added to `audit_log` with action, actor, scenario+version
  ids, and a diff summary.
- Version history is queryable via `GET /scenarios/{id}/versions`.

*Maps to:* SRS §3.10.5, §5.4.2.

### US-SCN-05 — As Maya, I want to add a chief or deputy from a CelesTrak TLE, so that the catalog-composition UX works end-to-end.
*Acceptance:*
- Catalog click → info panel → "Set as chief" / "Add as deputy" button.
- Backend resolves the NORAD ID to its TLE, creates a scenario role with
  the TLE as initial state.
- Composer card updates immediately; scenario is marked dirty.

*Maps to:* [UC-1](./use-cases.md).

### US-SCN-06 — As Frank, I want to import a scenario from a CCSDS OEM file, so that I can validate scenarios handed to me by tools or colleagues.
*Acceptance:*
- "Import from file" action in the scenario panel.
- Backend parses OEM via Orekit; constructs a scenario with the implied
  chief/deputies and initial states.
- Sensible defaults for missing fields (no attitude profile → identity,
  no maneuver plan → empty).
- Errors reported clearly if the file is malformed.

*Maps to:* [UC-3](./use-cases.md) step 1; SRS §3.10.3, §4.1.2.

### US-SCN-07 — As Maya, I want to create a chief or deputy from Keplerian elements, so that I can define hypothetical spacecraft.
*Acceptance:*
- Composer offers "Add from Keplerian elements" with a form: semi-major
  axis (km), eccentricity, inclination (deg), RAAN (deg), argument of
  perigee (deg), true anomaly (deg), epoch (ISO 8601).
- Units shown explicitly; bounds validated (e ∈ [0, 1), i ∈ [0°, 180°],
  etc.).
- Backend converts to state vector and persists in the scenario.

*Maps to:* [UC-2](./use-cases.md) step 3; SRS §3.10.4.

### US-SCN-08 — As Maya, I want to set a satellite as chief from the info panel, so that the action is explicit and inspection-first.
*Acceptance:*
- "Set as chief" button visible in info panel when a satellite is selected.
- Click confirms the role; composer card updates.
- If a chief already exists, prompt to confirm replacement ("existing
  deputies will be re-expressed in the new chief's LVLH frame").

*Maps to:* [UC-1](./use-cases.md), [UX patterns](./use-cases.md).

### US-SCN-09 — As Maya, I want to add a selected satellite as a deputy from the info panel.
*Acceptance:*
- "Add as deputy" button visible when a chief is designated and the selected
  satellite is not already a deputy.
- Disabled with tooltip "Designate a chief first" when no chief.
- Click adds the deputy; composer card updates.

*Maps to:* [UC-1](./use-cases.md).

### US-SCN-10 — As Maya, I want to remove a satellite from the scenario from the info panel, so I can correct mistakes without re-creating the scenario.
*Acceptance:*
- "Remove from scenario" button visible when the selected satellite is part
  of the current scenario.
- Click removes; if removed satellite is the chief and deputies exist,
  prompt to confirm (deputies will lose their reference).

### US-SCN-11 — As Maya, I want to save a scenario with a name, so I can return to it later.
*Acceptance:*
- "Save" button in the composer; enabled when scenario is dirty.
- First save prompts for a name; subsequent saves create new versions.
- Saved scenarios appear in the scenario panel list.

*Maps to:* [UC-8](./use-cases.md).

### US-SCN-12 — As Maya, I want to load a saved scenario, so I can continue earlier work.
*Acceptance:*
- Clicking a scenario in the list loads its latest version.
- Composer + global + proximity views populate with the scenario's state.
- A scenario stream opens for it (Phase 4 wires playback).

*Maps to:* [UC-8](./use-cases.md).

---

# Phase 4 — Dual viewports + shared clock

### US-VIEW-01 — As Maya, I want the proximity view to render the chief at the LVLH origin with deputies at their relative positions, so I can see the close-range geometry.
*Acceptance:*
- three.js scene initialized in a dedicated panel.
- Chief rendered as a placeholder marker at the origin.
- Deputies rendered at their LVLH positions, updated each frame from the
  relative-state stream.
- Adjustable scale: zoom from 1 m to 100 km between objects.

*Maps to:* SRS §3.9.

### US-STREAM-02 — As a developer, I want a per-user relative-state stream for the proximity view, so that it doesn't depend on CZML.
*Acceptance:*
- Per-scenario WebSocket: `/stream/scenario/{id}`.
- Sends both CZML (for the global scenario layer) and compact LVLH
  relative-state samples (for the proximity view).
- One scenario stream per user connection.

*Maps to:* [Decision 10](./decisions.md), [Decision 13](./decisions.md).

### US-VIEW-02 — As Omar, I want both views to share one clock, so they stay in lockstep at all times.
*Acceptance:*
- Single Zustand `clock` slice.
- Both views render at the clock's current epoch.
- Any rate/seek change in either view's controls propagates instantly.
- Sync verified with a test that scrubs and checks both views render the
  same epoch.

*Maps to:* SRS §3.11.4, §6.1.3.

### US-VIEW-03 — As Omar, I want time controls covering 0.01x to 10000x including reverse, so I can rehearse and analyze at any pace.
*Acceptance:*
- Play / pause / step-forward / step-backward / reset / rate-slider /
  reverse-toggle controls.
- Rate range: 0.01x to 10000x (logarithmic slider).
- Scrub bar with input-to-frame latency ≤200 ms.
- Reverse playback streams state backward correctly.

*Maps to:* SRS §3.3.4, §3.11.1–2, §5.1.3.

### US-VIEW-04 — As Omar, I want a timeline scrub bar showing the scenario extent and current time, so I can navigate large scenarios.
*Acceptance:*
- Horizontal scrub bar at the bottom of the layout.
- Shows scenario start / end / current epoch labels.
- Click+drag scrubs; current epoch updates live.
- Annotations come later (US-EVT-*).

---

# Phase 5 — Relative motion + initial maneuvers

### US-REL-01 — As Maya, I want a real-time relative-state readout for selected deputies, so I can monitor proximity geometry.
*Acceptance:*
- Panel shows: distance, range-rate, current relative position in LVLH
  (R, I, C components), relative velocity components.
- Updates every frame from the stream.
- Per-deputy (one readout per deputy, max 10).

*Maps to:* SRS §3.4.1; [UC-1](./use-cases.md) step 6.

### US-REL-02 — As Maya, I want to see when the next closest approach occurs, so I can plan around it.
*Acceptance:*
- Closest-approach time + distance computed for each deputy across the
  scenario time range.
- Displayed in the relative-state panel and annotated on the timeline.
- Re-computed when the scenario is re-propagated.

*Maps to:* SRS §3.12.1.

### US-REL-03 — As Frank, I want to switch a close-range scenario to CW propagation, so I can use a faster closed-form model when separations are small.
*Acceptance:*
- When fidelity is `cw`, backend uses Clohessy-Wiltshire equations.
- CW propagation valid for separations under ~10 km; UI warns above that
  separation.

*Maps to:* SRS §3.1.7, §5.2.3.

### US-MAN-01 — As Maya, I want to add an impulsive ΔV maneuver to a deputy at a specified epoch, so I can plan a burn.
*Acceptance:*
- Maneuver panel allows adding a `delta_v` maneuver: deputy id, epoch, ΔV
  vector in chosen frame (RIC or body).
- Adding the maneuver creates a new scenario version (audit).
- Backend re-propagates the deputy from that epoch with the new state
  vector applied (ECI velocity = previous + ΔV in chosen frame, transformed).

*Maps to:* SRS §3.5.1.

### US-MAN-02 — As Maya, I want a Hohmann transfer template that fills in ΔV from a target altitude, so I can sketch altitude changes quickly.
*Acceptance:*
- Template UI: target altitude (km).
- Computes the two-burn Hohmann ΔV for the deputy from its current orbit
  to the target altitude (coplanar assumption).
- Inserts both impulsive maneuvers into the scenario.

*Maps to:* SRS §3.5.3.

### US-MAN-03 — As Maya, I want a two-impulse rendezvous template, so I can sketch how the deputy meets the chief at a target time.
*Acceptance:*
- Template UI: target arrival epoch.
- Computes a Lambert-based two-impulse transfer.
- Inserts both maneuvers.
- Shows the resulting transfer trajectory in both views.

*Maps to:* SRS §3.5.3.

### US-MAN-04 — As Maya, I want ΔV vectors annotated in the proximity view at maneuver epochs, so I can see the burns visually.
*Acceptance:*
- At each maneuver epoch, a vector glyph appears at the deputy's position
  in the proximity view, scaled to the ΔV magnitude with a label.
- Glyphs appear/disappear consistently with the timeline scrub.

*Maps to:* SRS §3.9.6.

### US-MAN-05 — As Maya, I want a cumulative ΔV budget per spacecraft, so I can read the cost of the plan at a glance.
*Acceptance:*
- Composer card (or a dedicated maneuver panel) shows total ΔV per deputy.
- Updates immediately on maneuver edits.
- Includes a unit-clear display (m/s).

*Maps to:* SRS §3.5.5.

---

# Phase 6 — Proximity visualization *(outline)*

- US-PROX-01 — Render spacecraft with GLTF models.
- US-PROX-02 — Articulated parts (solar arrays, antennas).
- US-PROX-03 — Past / predicted trajectory ribbons with distinct styling.
- US-PROX-04 — Camera modes: chief-body, deputy-body, fixed external.
- US-PROX-05 — Earth backdrop in the proximity view (per pending design
  decision).

# Phase 7 — Sensors & FOV *(outline)*

- US-SENSE-01 — Sensor objects as first-class scenario entities.
- US-SENSE-02 — FOV rendered as translucent geometry (cone / frustum /
  polygonal).
- US-SENSE-03 — Pointing model (body-fixed and gimbaled).
- US-SENSE-04 — Occlusion against other spacecraft, Earth, Sun.
- US-SENSE-05 — Sensor-frame proximity-view camera mode.
- US-EVT-01 — Acquisition / loss-of-sight events on the timeline.

# Phase 8 — Environment & events ✅ (done — Decision 25, [build-history.md](./build-history.md))

- US-ENV-01 ✅ — Sun and Moon positions at sim time (Orekit `CelestialBodyFactory`),
  streamed as LVLH unit directions (`sunVector`/`moonVector`).
- US-ENV-02 ✅ — Eclipse umbra / penumbra periods per spacecraft (`EclipseEventComputer`,
  conical dual-cone in geocentric ECI) → timeline bands.
- US-ENV-03 ✅ — Spacecraft illumination consistent with the Sun vector (real
  `DirectionalLight` + Earth terminator; eclipse dimming). Resolves R17 flat lighting.
- US-EVT-02 ✅ — Conjunction detection with a configurable miss-distance threshold —
  intra-scenario (`ConjunctionEventComputer`) + catalog screening (`ScreeningService`, UC-7).
- US-EVT-03 ✅ — Constraint checks: approach corridor + sun keep-out (`ConstraintChecker`).
  *(Plume impingement deferred — needs per-burn plume geometry.)*
- US-EVT-04 ✅ — Timeline event annotations (eclipse bands, conjunction ticks, violation marks)
  + `EnvironmentPanel`.

# Phase 9 — Advanced maneuvers & analysis ✅ (done — Decision 27, [build-history.md](./build-history.md))

### US-MAN-06 — As Maya, I want a converged two-impulse rendezvous (not an open-loop sketch), so the deputy actually arrives at the chief. ✅ (9A)
*Acceptance:* the two-impulse rendezvous defaults to a **differential corrector**
(`RendezvousCorrector`) against the real propagators (numerical deputy + SGP4/numerical chief);
corrected miss collapses to <1 m where the raw two-body plan missed by km (`RendezvousCorrectorTests`);
byte-identical rerun; non-convergence falls back to the open-loop seed + an audit-summary warning.
An arrival × revolution ΔV **search** (`POST /maneuvers/rendezvous/search`) surfaces the cheapest
feasible transfer; a **phasing** template (`POST /maneuvers/phasing`) sketches the co-elliptic approach.
*Maps to:* [UC-1](./use-cases.md); SRS §3.5.3; **resolves R16**.

### US-MAN-07 — As Maya, I want an NMC-ellipse template, so I can drop a deputy onto a passively-safe natural circumnavigation. ✅ (9B)
*Acceptance:* `POST /maneuvers/nmc` inserts the in-track drift-cancel burn (`vy = −2·n·x`) so the
deputy traces a closed natural-motion ellipse in LVLH (`CwTargeting` + `ManeuverTemplateService.nmc`,
`CwTargetingTest` closed-loop). *Maps to:* SRS §3.5.3.

### US-MAN-08 — As Maya, I want V-bar / R-bar hold templates, so I can park a deputy at a hold point. ✅ (9B)
*Acceptance:* `POST /maneuvers/hold` computes a CW two-impulse transfer to a V-bar/R-bar point at a
given distance + arrival epoch, with zero arrival velocity (`ManeuverTemplateService.hold`).
*Maps to:* SRS §3.5.3; RPO approach geometries.

### US-MAN-09 — As Maya, I want a glideslope template, so I can sketch a controlled constant-rate approach. ✅ (9B)
*Acceptance:* `POST /maneuvers/glideslope` discretizes a constant-closing-rate approach along V-bar/R-bar from `startRangeM` to `endRangeM` into `segments` chained `CwTargeting.twoImpulse` legs (a corrective burn at each waypoint) + a final burn parking at rest; constant closing speed (leg duration scales with length). Rejects non-closing ranges / CW-singular legs / window overrun. `ManeuverTemplateServiceTests` (park-burn chain, strictly-increasing epochs). *Maps to:* RPO glideslope; SRS §3.5.3.

### US-MAN-10 — As Frank, I want closed-loop station-keeping, so a deputy holds a point against real drift. ✅ (9B)
*Acceptance:* `POST /maneuvers/station-keep` holds a V-bar/R-bar point with a corrective burn every `intervalSec` for `corrections` corrections (bounded by the window). Genuinely closed-loop: each correction rebuilds the deputy's real (numerical, corrections-so-far) propagator, reads back its drifted relative state, and solves the CW re-aiming burn — so each correction counters the prior drift. `ManeuverTemplateServiceTests` (one burn per interval; reject window overrun). *Maps to:* station-keeping; SRS §3.5.3.

### US-MAN-11 — As Maya, I want finite-burn maneuvers (thrust, Isp), so my ΔV plan reflects burns that take real time (and real propellant). ✅ (9B)
*Acceptance:* a maneuver carries optional `thrustN` (N) + `ispSec` (s) (v6-additive on `Maneuver`/`Impulse`; null → impulsive). `PropagationService.buildManeuvered` realises a finite burn as an Orekit `ConstantThrustManeuver` of the Tsiolkovsky duration that achieves the intended ΔV, centred on the epoch (collapses to the impulse as thrust→∞; mass depleted via the rocket equation). `PropagationServiceTests` (finite ≈ equivalent impulse but ≫ the un-maneuvered track; duration achieves the target ΔV); thrust+Isp required together + positive, else 422. `ManeuverPanel` finite toggle. *Maps to:* SRS §3.5.2. *(Burn-window glyph animation deferred — the glyph sits at the centred midpoint.)*

### US-MAN-12 — As Frank/Maya, I want a collision-avoidance maneuver (the inverse of rendezvous), so a deputy dodges a predicted conjunction while staying near its orbit. ✅ (Decision 30)
*Acceptance:* `POST /scenarios/{id}/maneuvers/collision-avoidance` inserts one audited RIC impulse that
raises the miss distance from the maneuvering deputy to a threat (the chief or another deputy) at a
predicted conjunction TCA up to a target, along a chosen axis — **cross-track** (out-of-plane, keeps
altitude; the default), **radial**, or **in-track** (cheapest ΔV but changes altitude, labeled); the
sign is auto-chosen to push away from the threat. A read-only `.../preview` returns the ΔV + achieved
miss first (like the rendezvous ΔV search). Solved against the **real** propagators (FD sensitivity +
quadratic seed + secant refine on the **windowed-minimum** separation — not the fixed TCA), with a
per-axis default burn epoch. **Never returns a burn that worsens the miss** (tracks the best across
magnitudes; refuses with a note for a recurring approach that one burn can't dodge → 422).
Byte-identical reruns (R11); the maneuvering craft must be a deputy (chief → 422).
`CollisionAvoidancePlannerTests` (fast crossing converges to target; cross-track keeps SMA while
in-track changes it; determinism; unreachable target falls back) + `ManeuverTemplateServiceTests`
(orchestration + 422s). Frontend: `ManeuverPanel` "Collision avoidance" block driven by the detected
conjunctions, Preview/Insert. *Maps to:* SRS §3.5.3, §3.12.1; the inverse of [UC-1](./use-cases.md); Frank/Maya.
*(Deferred: catalog-debris threats; multi-burn/composable CAM; a collision-probability `Pc` model.)*

### US-MC-01 — As Frank, I want Monte Carlo dispersion on initial state + maneuver execution error, so I can quantify trajectory uncertainty. ✅ (9C)
*Acceptance:* `POST /scenarios/{id}/monte-carlo` perturbs the deputy ECI seed (Gaussian pos/vel) +
maneuver ΔV (magnitude + pointing tilt) over a seeded sample set (default 100, cap 500), returns the
chief-LVLH cloud. **Bit-identical on the same seed, independent of pool scheduling** (per-sample
`SplittableRandom(mix(seed,i))`, ordered collect — `MonteCarloServiceTests`); zero-uncertainty →
nominal; recovers the input σ. *Maps to:* [UC-6](./use-cases.md); SRS §3.12.4, §5.4.1; **R21**.

### US-MC-02 — As Frank, I want covariance ellipsoids in the relative frame, so I can check the 3σ envelope against the corridor. ✅ (9C)
*Acceptance:* per-epoch covariance ellipsoids (Hipparchus `EigenDecompositionSymmetric` →
canonicalized → three.js quaternion via `FrameService.matrixToQuaternionXyzw`) rendered as 3σ shells
in the proximity view (`proximity/montecarlo.ts`, `MonteCarloPanel.tsx`). *Maps to:* [UC-6](./use-cases.md);
SRS §3.12.5.

### US-EVT-05 — As Gita, I want link-budget / SNR overlays for RF and optical sensors, so I can see when a link is viable. ✅ (9D)
*Acceptance:* a sensor carries an optional `LinkBudget` (`ScenarioBody` schema v6); `LinkBudgetComputer`
computes per (sensor↔target) SNR over the sampled trajectory (Friis, ~6 dB per range-doubling,
`LinkBudgetComputerTests`); streamed additively (`linkBudgets`); `SensorPanel` fields + `Timeline` SNR
band (red below threshold). *Maps to:* [UC-4](./use-cases.md); SRS §3.6. *(Optical detector NEP/QE
detail deferred.)*

# Phase 10 — Enterprise hardening ✅ (done — Decision 28, [build-history.md](./build-history.md))

- US-AUTH-02 ✅ — Real OIDC integration (OAuth2 resource-server, stateless bearer JWT; self-hosted
  Keycloak IdP; SPA auth-code + PKCE). Gated by `orbit.auth.mode` (`stub` default / `oidc`).
  *(SAML2 deferred — OIDC satisfies the SRS's "OIDC/SAML".)*
- US-AUTH-03 ✅ — RBAC activated: scenario ownership enforced (non-owner → 404, no enumeration) +
  capability role rules (`ROLE_*` from the Keycloak realm-role claim).
- US-INFRA-05 ✅ — §5.2 validation suite (`ValidationConformanceTest`) + [validation-conformance.md](./validation-conformance.md);
  Orekit-reference posture (AIAA 2006-6753 inherited from Orekit, R2).
- US-INFRA-06 ✅ — Audit-log + version-history UI (`GET /scenarios/{id}/audit`, `/versions`; `AuditLogPanel`).
- US-INFRA-07 ✅ — Reproducibility tests: byte-identical `loadAndEncode` reruns (SGP4 / numerical /
  maneuvered / finite-burn) + Monte-Carlo same-seed (§5.4.1, R11/R21).
- US-INFRA-08 ✅ — TLS at the ingress (cert-manager) + k8s Secrets; CORS/WS origins locked in prod.
- US-INFRA-09 ✅ — On-prem packaging: Helm chart (`deploy/helm/orbit`) + offline image bundle
  ([scripts/bundle.sh](../scripts/bundle.sh)) + [deployment.md](./deployment.md).

# Phase 11 — Polish & ship ✅ (done — Decision 29, [phase-11-plan.md](./phase-11-plan.md))

### US-UX-01 — As a new user, I want sample scenarios I can load and play immediately, so I need no training to see the tool work. ✅ (11A)
*Acceptance:* **five** seeded demos (NMC formation, rendezvous, sensor/link-budget
inspection, eclipse, V-bar station), each geometry-validated
(`SampleScenarioFormationTests`); seeded for the dev user at startup AND for **every
newly provisioned user** (`UserProvisioner` → `UserProvisionedEvent` → seeder,
`AFTER_COMMIT`; `UserProvisioningSeedTests`) — so real OIDC users see them too
(the scenario list is owner-scoped). A one-time first-run hint points at them.
*Maps to:* SRS §5.6.1; personas (Eli, Maya).

### US-UX-02 — As any user, I want contextual help and tooltips on the controls, so the UI explains itself. ✅ (11A)
*Acceptance:* every interactive control carries a `title`/`aria-label` (scripted audit,
76 added; units + meaning on numeric fields); a `?` Help overlay (quick start / controls
reference / mini-glossary from the glossary) + first-run hint. *Maps to:* SRS §5.6.2.

### US-UX-03 — As the team, I want the §5.1 performance targets instrumented and checked, so "smooth" is measured, not eyeballed. ✅ instrumented + measured (11C)
*Acceptance:* `lib/perf.ts` + `PerfHud` (⏱ / `?perf=1`) show live globe/proximity FPS,
scrub latency (seek → rendered frame, last + p95), and scenario-load time, with the
§5.1 thresholds highlighted when missed (closes the R7 FPS-counter caveat). Readings
**recorded** (2026-07-07, browser on an RTX 4090; table in [phase-11-plan.md](./phase-11-plan.md)):
proximity 60 fps at 1–4 craft, globe 30 fps (its cap) with the scenario layer, scrub p95 69 ms,
10-craft SGP4 load 1.79 s. **Two documented misses** (tracked follow-ups): the full ~15.5k-dot
catalog *overlay* drops the globe to ~10 fps (R7 — CZML-Entity CPU path; LOD/`PointPrimitiveCollection`
mitigation pre-scoped), and a 10-craft proximity scene (the SRS ceiling) sits at ~30 fps; 2–4-craft
scenarios hold 60. *Maps to:* SRS §5.1.1–4.

### US-IO-01 — As Omar, I want PNG snapshots of the rendered views, so I can drop them into briefings. ✅ (11B)
*Acceptance:* Export panel captures the global view, proximity view, or both
side-by-side (scenario + sim-time caption) via same-task canvas reads — no
`preserveDrawingBuffer` (Decision 29). Works catalog-only too. *Maps to:* SRS §4.2.3;
[UC-8](./use-cases.md).

### US-IO-02 — As Omar, I want MP4 sequence export, so I can replay a scenario in a briefing without the app. ✅ (11B)
*Acceptance:* a deterministic frame-stepped offline render (pause → step sim time →
render → encode) through WebCodecs H.264 + `mp4-muxer` → a real .mp4 with a sim-time
chip; source/range/speed/fps options, ≤1800-frame cap, progress + cancel with full
restore; smoothness independent of live FPS. Feature-gated (`isConfigSupported`) with a
disabled-tooltip fallback on non-WebCodecs browsers. *Maps to:* SRS §4.2.3.

### US-IO-06 — As Frank, I want to export the propagated ephemerides as CCSDS OEM, so downstream tools consume my scenario. ✅ (11B)
*Acceptance:* `GET /scenarios/{id}/export/oem` → one KVN OEM message, a segment per
craft (EME2000/UTC, the stream's sampling grid); maneuvered deputies carry the real
post-burn numerical trajectory (finite burns included); measured roles are clipped to
their data span; owner-gated; recorded in the audit trail (`EXPORT_OEM`).
**Byte-identical on re-export** of the same version (R11) and round-trips through
Orekit's `OemParser` (`OemExportServiceTests`). *Maps to:* SRS §4.2.1;
[UC-3](./use-cases.md) step 8, [UC-8](./use-cases.md) step 3; Frank persona.

### US-IO-07 — As Frank, I want scenario events exported as JSON and CSV, so I can post-process them. ✅ (11B)
*Acceptance:* client-side export of every event class over the window — sensor AOS/LOS,
eclipse ingress/egress, conjunctions, constraint violations, per-deputy closest
approach — as `orbit.scenario-events.v1` JSON or flat CSV, epoch-sorted with resolved
names (`export/eventsExport.ts`, pure builders). *Maps to:* SRS §4.2.2.

### US-UX-04 — As the team, I want polished API docs and a user guide, so the project ships explainable. ✅ (11C)
*Acceptance:* OpenAPI info bean + `@Tag`/`@Operation` on all 31 endpoints (8 groups;
doc-only — the regenerated client has no type drift); [user-guide.md](./user-guide.md)
(14 sections mapped to UC-1..8); a root [README.md](../README.md). *Maps to:* SRS §4.3.3.

---

# Measured-data ingestion *(feature track — Decision 26, [measured-data-plan.md](./measured-data-plan.md))*

### US-IO-03 — As Frank, I want to import a measured ephemeris (WOD CSV) as a scenario, so I can analyze/validate against the real flown trajectory. ✅ (slice 1)
*Acceptance:* server-path `POST /scenarios/import/measured` reads a WOD CSV (constrained to
`orbit.import.allowed-root`), creates a scenario whose chief is the measured craft
(`kind:"ephemeris"`, read-only truth; window = data span); the global/proximity views play the
real orbit. NORAD auto-resolved from the file name (override optional). Audited (`IMPORT_MEASURED`);
editing preserves the ephemeris chief.
*Maps to:* [UC-3](./use-cases.md), [UC-8](./use-cases.md); SRS §3.10.3, §4.1.2 (generalizes US-SCN-06).

### US-IO-04 — As Gita, I want imported measured attitude (quaternions) to drive orientation/FOV, so coverage reflects how the craft actually pointed. ✅ (slice 2)
*Acceptance:* `EST_ATTD_Q1..Q4_8` ingested as a parallel attitude series; `AttitudeProfile.mode="measured"`
SLERP-streamed through the existing `"fixed"` path; legend reads "measured"; quaternion convention
resolved empirically + pinned by a signed-axis test (`MeasuredAttitudeTest`, R15/R20). A toggleable
body-axis triad makes orientation legible. Verified on the dev stack (re-import → chief
`attitude.mode=measured`; the relative frame carries the chief's varying measured `att`).

### US-IO-05 — As Frank, I want a real measured chief+deputy pair and OEM/AEM import + browser upload. *(slice 3)*
*Acceptance:* import a dataset as a deputy (two measured craft = a real RPO pair); numerical handoff
beyond the data window; CCSDS OEM/AEM readers feed the same artifact; large files uploadable from the
browser (progress).

---

## Traceability

Every story should map to at least one [use case](./use-cases.md) and at least
one [SRS clause](./Software%20Requirements%20Specification.md). Periodic
reviews should catch any orphan stories (no SRS link → scope creep) or orphan
SRS clauses (no story → coverage gap).
