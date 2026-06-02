# Acceptance criteria

Concrete "this phase is done" checklists. Removes ambiguity at phase
boundaries. Early phases are filled in detail; later phases get filled in as
we approach them.

Each criterion is testable — either pass/fail by observation, or a check
against a metric. Phase done = every criterion passes.

---

## Phase 0 — Foundation ✅ (complete)

- [x] React + Vite + TypeScript project compiles cleanly (`npm run type-check`
      passes).
- [x] `npm run dev` starts the Vite dev server on port 5173.
- [x] CesiumJS globe renders with day/night lighting at the current time.
- [x] Cesium ion authentication works with a `.env`-provided token.
- [x] The catalog UI shell (search box, constellation filter, stats overlay,
      time controller) renders and is interactive.
- [x] All TypeScript strict-mode errors fixed (no `// @ts-ignore`).

---

## Phase 1 — Project structure & dual-container dev env

**Infrastructure**
- [ ] `docker compose up` from the project root starts backend, Postgres,
      and frontend containers without errors.
- [ ] Postgres data persists across container restarts via a named volume.
- [ ] Backend reaches Postgres by Docker service name (not localhost).
- [ ] Frontend reaches backend by Docker service name in dev.

**Database**
- [ ] Flyway (or equivalent) runs on backend startup, applies migrations,
      and is idempotent on re-run.
- [ ] Tables exist: `users`, `scenarios`, `scenario_versions`, `audit_log`.
- [ ] `scenarios` table has `owner_id` referencing `users.id`.
- [ ] `users` table has a `roles` array column.
- [ ] Migration scripts checked into version control.

**Backend**
- [ ] Spring Boot starts and logs a healthy banner.
- [ ] `GET /health` returns 200 with JSON body containing `version`,
      `buildTime`, `dbStatus: "up"`.
- [ ] `GET /v3/api-docs` returns the OpenAPI 3.x JSON spec.
- [ ] Swagger UI (or equivalent) reachable at `/swagger-ui` in dev.
- [ ] Spring Security filter chain is configured; all endpoints pass
      through it.
- [ ] Default policy: permit-all (no auth required), dev user injected
      with all roles. `request.userPrincipal` populated on every request.
- [ ] Backend logs structured JSON (correlation IDs, level, ts).

**Frontend integration**
- [ ] A build step generates a TypeScript client from the OpenAPI spec.
- [ ] Generated client is imported and used for at least the health check.
- [ ] Frontend displays a small "backend: healthy" status chip on load,
      proven via the generated client (not a hand-rolled fetch).
- [ ] No regression in Phase 0 catalog UI behavior (still renders, filters
      still work, time controller still ticks).

**Scenario shell**
- [ ] Empty scenario panel renders in the layout.
- [ ] Empty composer card renders with "No chief designated."
- [ ] Zustand store has a `composer` slice with shape
      `{ chiefId, deputyIds, scenarioId, isDirty }` plus setters
      (`setChief`, `addDeputy`, `removeFromScenario`, `clear`).
- [ ] Composer slice is per-slice subscribable (no whole-store reads in
      components).

**Hygiene**
- [ ] Backend has at least one unit test that runs in CI (or local) and
      passes.
- [ ] Frontend `npm run type-check` still passes.
- [ ] README updated with `docker compose up` instructions.
- [ ] CLAUDE.md "build commands" section updated with backend / stack
      commands.

---

## Phase 2 — Propagation pipeline + shared catalog stream

> Status: backend + wiring **complete and verified** (cold-start gate green:
> 15,501 sats from seed, 0 skipped; pass ~100–650 ms; 7.36 MB CZML; `/health`
> + proxied WebSocket deliver). The only items left **`[~]`** need a human at
> the browser (render FPS / visual click + filter). Deviations from the
> original wording are noted inline — they reflect the firewall-blocked
> CelesTrak reality surfaced during the build.

**Propagation core**
- [x] Orekit (13.1.5) declared; data bundle (UTC-TAI, IERS EOP, leap seconds,
      ephemerides) loaded at startup via `OrekitConfig` (baked into the image;
      Gradle task provisions it for tests).
- [x] Backend produces a state vector at a given epoch via SGP4
      (`SatellitePropagator` over Orekit `TLEPropagator`).
- [x] Unit test asserts SGP4 correctness against Orekit (our reference impl):
      stable-LEO invariants + OMM→TLE getter round-trip (`Sgp4PropagationTests`).
      Note: external golden-vector / AIAA 2006-6753 conformance is the Phase 3
      §5.2 suite, as planned.
- [x] `FrameService` v1: ECI↔ECEF↔geodetic via Orekit; states frame-tagged
      (`StateVector`).
- [x] Frame round-trip tests pass (`FrameServiceTests`: sub-mm ECEF round trip).

**Catalog service**
- [x] Backend loads TLEs at startup. *Deviation:* CelesTrak is firewall-blocked,
      so it loads a bundled offline GP/OMM seed and best-effort refreshes from a
      reachable GitHub mirror (CelesTrak attempted first, fails fast).
- [x] TLE refresh schedule configurable (cron, default 6 h).
- [x] Catalog SGP4 pass runs on a schedule (default 30 s) producing one CZML
      chunk over the window.
- [x] Chunk size in bytes logged each pass.

**Streaming**
- [x] WebSocket `/stream/catalog` accepts connections.
- [x] New connections receive the latest chunk on join (warm start).
- [x] Subsequent chunks broadcast to all clients.
- [x] `contractVersion` present on every message (envelope).
- [x] Disconnect handling: removal by id, dead-session cleanup, no leaks
      (`ConcurrentWebSocketSessionDecorator`).

**Frontend catalog**
- [x] Globe connects to `/api/stream/catalog` on load (via Vite proxy).
- [x] Cesium ingests CZML via `CzmlDataSource.process` (merges by id).
- [~] All active satellites (~15,500) render as dots. *(stream delivers 15,501;
      visual confirmation pending in browser)*
- [~] Smooth motion at ≥30 fps. *(needs browser FPS measurement — R7; fallback
      to PointPrimitiveCollection if under)*
- [x] `lib/celestrak.ts` removed; backend is the only catalog source.
- [x] Click resolves NORAD id (with ±5 px hit-padding) → info panel.
- [x] Info panel shows name, NORAD ID, current lat/lon/alt (live-updating),
      altitude, period, inclination. *Deviation:* country / launch date are
      not in the reachable OMM mirror (SATCAT join deferred) — shown as "—".
- [~] Hit-padding in dense regions *(implemented; visual confirmation pending)*.

**Catalog navigation**
- [x] Search: substring on name, exact on NORAD id.
- [x] Enter centers the globe on the match (camera fly-to).
- [x] Constellation filter checkboxes toggle visibility. *Deviation:* membership
      by name-prefix (group endpoints blocked); declutter semantics (off → hide).
- [x] Filter state persists in localStorage.

**Performance**
- [~] Render FPS with hot catalog ≥30 fps *(browser measurement pending — R7)*.
- [x] Backend pass: 15,501 sats in ~100–650 ms; warm-start delivery is
      effectively immediate after the catalog loads (well within the ≤8 s
      neighborhood).

**Composer wiring**
- [x] Catalog click updates only the info panel; composer unchanged.
- [x] Info panel has disabled "Set as chief" / "Add as deputy" buttons
      (wired in Phase 3).

---

## Phase 3 — High-fidelity propagation + scenario CRUD

*(Filled in detail as Phase 3 begins; outline below.)*

**Propagator**
- [ ] Numerical propagator (DP8(7), gravity ≥J4, NRLMSISE-00 drag, SRP,
      Sun + Moon third-body) implemented.
- [ ] Per-scenario fidelity selection: `sgp4` / `numerical` / `cw`.
- [ ] Deterministic propagation: same inputs → same outputs, byte-compare.

**Frames**
- [ ] `FrameService` v2: ECI↔LVLH(chief), ECI↔RIC(chief), ECI↔body(per-sat).
- [ ] Round-trip tests for each frame pair.

**Scenario CRUD**
- [ ] `POST /scenarios`, `GET /scenarios`, `GET /scenarios/{id}`,
      `GET /scenarios/{id}/versions/{v}`, `PUT /scenarios/{id}`,
      `DELETE /scenarios/{id}` all implemented and OpenAPI-documented.
- [ ] All mutations go through a single service layer; each emits an
      `audit_log` entry.
- [ ] Versioning: every PUT creates a new immutable version row.

**Initial-state sources**
- [ ] TLE: catalog click → "Set as chief" / "Add as deputy" creates the
      role with the satellite's TLE.
- [ ] CCSDS OEM: import a file, scenario draft populated.
- [ ] Keplerian: form-based creation works with unit-tagged inputs and
      bounds validation.

**Composer behavior**
- [ ] "Set as chief" works; replacement prompts confirm.
- [ ] "Add as deputy" disabled without chief; tooltip explains.
- [ ] "Remove from scenario" visible when applicable.
- [ ] Save creates v1; subsequent saves create new versions; scenario
      panel lists them.
- [ ] Load restores composer + view state correctly.

---

## Phase 4 — Dual viewports & shared clock

*(Outline; fill in when starting Phase 4.)*

- [ ] three.js proximity view scene scaffold renders.
- [ ] Chief at LVLH origin (placeholder marker).
- [ ] Deputies render at relative positions from the scenario stream.
- [ ] Adjustable scale 1 m – 100 km.
- [ ] Both views read one Zustand `clock` slice.
- [ ] Time controls: play / pause / step / scrub / rate (0.01x – 10000x) /
      reverse.
- [ ] Scrub latency ≤200 ms (SRS §5.1.3).
- [ ] Sync verification test scrubs and asserts both views render the
      same epoch.

---

## Phase 5 — Relative motion + initial maneuvers

*(Outline; fill in when starting Phase 5.)*

- [ ] Relative-state readout (distance, range-rate, R/I/C components) per
      deputy, updated per frame.
- [ ] Closest-approach time + distance computed and annotated on the
      timeline.
- [ ] CW fidelity option works for close-range scenarios.
- [ ] Impulsive ΔV maneuver: add via UI, scenario versioned, propagation
      re-runs.
- [ ] Hohmann transfer template: target altitude → two impulses inserted.
- [ ] Two-impulse rendezvous template (Lambert): target epoch → ΔV
      computed.
- [ ] ΔV vectors annotated in proximity view at maneuver epochs.
- [ ] Cumulative ΔV budget per spacecraft visible.

---

## Phase 6 onwards

Acceptance criteria for Phases 6–11 will be drafted when the respective
phase begins, informed by what we learned in earlier phases. The
[user-stories outline](./user-stories.md#phase-6--proximity-visualization-outline)
seeds each phase; the SRS clauses they map to are the verification source.

---

## Cross-phase non-functional targets (SRS §5)

These apply continuously and should be checked at each phase boundary.

- **Performance.** Proximity view 60 fps with ≤10 spacecraft; global view
  30 fps with full catalog + scenario layer (§5.1.1–2). Scrub latency
  ≤200 ms (§5.1.3). Scenario load ≤5 s for a 24-hour scenario (§5.1.4).
- **Accuracy.** SGP4 conforms to AIAA 2006-6753 (§5.2.1, validated by
  Orekit). High-fidelity sub-km / 24h LEO (§5.2.2). CW sub-meter / 1h for
  <10 km separation (§5.2.3). Frame transforms precise to 1e-9 (§5.2.4).
- **Extensibility.** Adding a new propagator does not require rendering
  changes (§5.3.1, verified by code review). New sensor types via plugin
  interface (§5.3.2). Visualization decoupled from engine via streaming
  contract (§5.3.3, verified by architecture).
- **Reliability / Reproducibility.** A given scenario produces bit-identical
  results (§5.4.1, verified by CI test). All scenario modifications logged
  (§5.4.2, verified by audit log inspection).
- **Security.** Auth via SSO when activated (§5.5.1). RBAC enforced on
  scenario CRUD (§5.5.2). TLS 1.2+ at ingress (§5.5.3).
- **Usability.** New user can load a sample scenario and play back without
  prior training (§5.6.1). Contextual help / tooltips on primary controls
  (§5.6.2).
