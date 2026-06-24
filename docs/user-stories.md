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

# Phase 8 — Environment & events ✅ (done — Decision 25, [phase-8-plan.md](./phase-8-plan.md))

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

# Phase 9 — Advanced maneuvers & analysis *(outline)*

- US-MAN-06 through 10 — Maneuver templates: glideslope, V-bar hold, R-bar
  hold, NMC ellipse, station-keeping.
- US-MAN-11 — Finite-burn maneuvers (thrust, Isp, duration).
- US-MC-01 — Monte Carlo dispersion on initial state + maneuver error.
- US-MC-02 — Covariance ellipsoids in the relative frame.
- US-EVT-05 — Link budget / SNR overlays for RF and optical sensors.

# Phase 10 — Enterprise hardening *(outline)*

- US-AUTH-02 — Real OIDC/SAML integration.
- US-AUTH-03 — RBAC roles activated (scenario ownership enforced).
- US-INFRA-05 — §5.2 validation test suite.
- US-INFRA-06 — Audit-log UI.
- US-INFRA-07 — Reproducibility tests (bit-identical reruns).
- US-INFRA-08 — TLS termination at ingress.
- US-INFRA-09 — On-prem packaging.

# Phase 11 — Polish & ship *(outline)*

- US-UX-01 — Sample scenarios.
- US-UX-02 — Tooltips / contextual help.
- US-UX-03 — Performance pass against SRS §5.1 metrics.
- US-IO-01 — PNG snapshot export.
- US-IO-02 — MP4 sequence export.
- US-UX-04 — OpenAPI docs polish; user guide.

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
