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

> Status: **Phase 2 complete and verified end-to-end** (cold-start gate green:
> 15,501 sats from seed, 0 skipped; pass ~100–650 ms; 7.36 MB CZML; `/health`
> + proxied WebSocket deliver). The browser-side items previously held at
> **`[~]`** are now confirmed by observation — ~15.5k dots render and animate
> smoothly, click-inspect / filters / search-to-fly / double-click focus all
> work (no FPS counter was instrumented; the motion is smooth to the eye and
> the R7 PointPrimitiveCollection fallback was not needed). Deviations from the
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
- [x] All active satellites (~15,500) render as dots. *(stream delivers 15,501;
      confirmed visible in browser.)*
- [x] Smooth motion. *(Confirmed smooth to the eye; no FPS counter instrumented
      — R7 PointPrimitiveCollection fallback not needed. Revisit with a counter
      only if perf degrades.)*
- [x] `lib/celestrak.ts` removed; backend is the only catalog source.
- [x] Click resolves NORAD id (with ±5 px hit-padding) → info panel.
- [x] Info panel shows name, NORAD ID, current lat/lon/alt (live-updating),
      altitude, period, inclination. *Deviation:* country / launch date are
      not in the reachable OMM mirror (SATCAT join deferred) — shown as "—".
- [x] Hit-padding in dense regions *(confirmed; ±5 px ring around the click).*

**Catalog navigation**
- [x] Search: substring on name, exact on NORAD id.
- [x] Enter centers the globe on the match (camera fly-to).
- [x] Constellation filter checkboxes toggle visibility. *Deviation:* membership
      by name-prefix (group endpoints blocked); declutter semantics (off → hide).
- [x] Filter state persists in localStorage.

**Global-view camera** *(added during Phase 2 UX; see [Decision 18](./decisions.md))*
- [x] Single-click = inspect only (info panel + yellow highlight ring); the
      camera does not move.
- [x] Double-click = focus: a ~0.8 s smooth blend that recenters the satellite
      with no auto-zoom, then an ENU tracked-entity orbit (drag to orbit, scroll
      to zoom toward it). No twist on hand-off (the blend converges to the live
      tracked pose; the tracking frame is forced to ENU to match it).
- [x] Search (Enter) flies to and selects the match.
- [x] "Reset view" releases tracking and flies back to the global view.

**Performance**
- [x] Render with hot catalog is smooth to the eye at ~15.5k dots *(no FPS
      counter instrumented — R7)*.
- [x] Backend pass: 15,501 sats in ~100–650 ms; warm-start delivery is
      effectively immediate after the catalog loads (well within the ≤8 s
      neighborhood).

**Composer wiring**
- [x] Catalog click updates only the info panel; composer unchanged.
- [x] Info panel has disabled "Set as chief" / "Add as deputy" buttons
      (wired in Phase 3).

---

## Phase 3 — High-fidelity propagation + scenario CRUD

> Sliced into **3A** (scenario composition end-to-end on SGP4 — the shippable
> Maya-facing slice) and **3B** (numerical propagator + LVLH/RIC frames — the
> Frank-facing physics depth). See [phase-3-plan.md](./phase-3-plan.md).
>
> **Phase 3A complete & verified end-to-end** (2026-06-04). New backend package
> `scenario` (entities/repos/`ScenarioService`/`UserService`/`ScenarioBody`),
> `api/ScenarioController` + `@RestControllerAdvice`, migrations V2 (seed dev
> user) + V3 (soft-delete), a NORAD→TLE-snapshot resolver on `CatalogService`,
> and the frontend scenario store slice + wired InfoPanel + real ScenarioPanel.
> Verified through the Vite proxy (`/api`) and `psql`: create → list → load →
> rename→v2 → delete, with owner-tagged rows, immutable versions, an audit row
> per mutation, and soft-delete preserving history.
>
> **Phase 3B complete — backend tests green** (2026-06-10). Backend-only,
> engine-deepening: `prop` gained `Fidelity`, `PropagationSettings` (pinned
> `DEFAULT`), `NumericalPropagation` (Orekit numerical propagator), and
> `PropagationService` (the fidelity-dispatch seam Phase 4 will call);
> `FrameService` grew LVLH/RIC + a minimal body frame + `toRelativeState`. No
> UI, contract, or schema change (it's proven by `./gradlew test`, not in the
> browser — Phase 4 makes it visible). 49 backend tests pass. See
> [phase-3b-plan.md](./phase-3b-plan.md) and Decision 20.

**Propagator** *(Phase 3B ✅)*
- [x] Numerical propagator (DP8(7), gravity ≥J4 [16×16], NRLMSISE-00 drag, SRP,
      Sun + Moon third-body) implemented (`NumericalPropagation`); verified to
      hold a bound LEO orbit over a rev (`NumericalPropagationTests`).
- [x] Per-scenario fidelity selection: `sgp4` / `numerical` / `cw`. *(3B:
      `PropagationService` dispatches `sgp4`→SGP4 and `numerical`→numerical;
      `cw` throws `UnsupportedOperationException` until Phase 5. The persisted
      `ScenarioBody.fidelity` string is unchanged — `Fidelity.fromString` parses
      it in the prop layer, Decision 20. Default stays `sgp4` until Phase 4 has
      a streaming consumer.)*
- [x] Deterministic propagation: same inputs → same outputs, byte-compare
      (`numericalPropagationIsBitIdenticalOnRerun`; SRS §5.4.1, R11).

**Frames** *(Phase 3B ✅)*
- [x] `FrameService` v2: ECI↔LVLH(chief), ECI↔RIC(chief), ECI↔body(per-sat)
      (`lvlh`/`ric`/`body` + `toRelativeState`; LVLH≡QSW = glossary R/I/C, not
      `LVLH_CCSDS`).
- [x] Round-trip / orientation tests (`FrameRelativeTests`): a known
      displacement lands on the expected **signed** axis (radial→+R, in-track→+I,
      cross-track→+C — this pins the convention, R15; a closed loop alone would
      not), a co-period deputy traces a closed loop in LVLH, the body frame
      round-trips, and a missing chief is a clear error.

**Scenario CRUD** *(Phase 3A ✅)*
- [x] `POST /scenarios`, `GET /scenarios`, `GET /scenarios/{id}`,
      `GET /scenarios/{id}/versions/{v}`, `PUT /scenarios/{id}`,
      `DELETE /scenarios/{id}` all implemented and OpenAPI-documented
      (`/v3/api-docs` 200; client regenerated via `gen:api`).
- [x] All mutations go through a single service layer (`ScenarioService`); each
      emits exactly one `audit_log` entry in the same transaction.
- [x] Versioning: every PUT creates a new immutable version row (`version_no`
      monotonic; old versions never mutated). `DELETE` soft-deletes (sets
      `deleted_at`); list/get filter `deleted_at IS NULL`; history preserved.

**Initial-state sources**
- [x] TLE: catalog click → "Set as chief" / "Add as deputy" creates the role
      with the satellite's TLE — backend freezes a TLE snapshot (line1/line2/
      epoch) at compose time, so the scenario doesn't drift on catalog refresh.
- [ ] CCSDS OEM: import a file, scenario draft populated. *(deferred — later phase)*
- [ ] Keplerian: form-based creation works with unit-tagged inputs and
      bounds validation. *(deferred — later phase)*

**Composer behavior** *(Phase 3A ✅)*
- [x] "Set as chief" works; replacement prompts confirm (UC-1 edge case).
- [x] "Add as deputy" disabled without chief; tooltip "Designate a chief first".
- [x] "Remove from scenario" visible when the selected sat is a member.
- [x] Save creates v1; subsequent saves create new versions; scenario panel
      lists them (with chief/deputy display names from the catalog index).
- [x] Load restores composer state correctly. *(global/proximity view repopulation
      is static in 3A; live scenario streaming is Phase 4.)*

---

## Phase 4 — Dual viewports & shared clock

> Sliced into **4A** (authoritative shared clock + per-scenario CZML stream —
> the global view *plays* a loaded scenario) then **4B** (three.js proximity
> view + relative-state + lockstep). See [phase-4-plan.md](./phase-4-plan.md).
>
> **Phase 4A complete — backend tests green + frontend type-check/build green**
> (2026-06-11). Backend: per-scenario WebSocket `/stream/scenario/{id}`
> (`ScenarioStreamHandler` + handshake interceptor capturing identity off the
> security-filter window), a precompute-once `ScenarioStreamService.loadAndEncode`
> (rebuilds frozen-TLE roles, fidelity-dispatches, ECEF-samples, encodes
> `scenario-czml`), `CzmlEncoder.encodeScenario` (role-colored markers + orbit
> paths + effective-step echo), a shared `StreamGzip`, and context-free reads
> (`bodyForStream` / `findByEmail`). Frontend: a real clock slice + single-writer
> `clockEngine` rAF loop, Cesium's clock severed and driven from the store via
> `preRender`, a `ScenarioStreamClient` + scenario `CzmlDataSource`, the catalog
> hidden during scenario playback, a rewritten `TimeController` (play/pause/step/
> reset/reverse/log-rate 0.01×–10000×) + a `Timeline` scrub bar. 13 new backend
> tests (encoder/service/handler incl. Testcontainers WS close-codes) pass.

**Phase 4A — shared clock + per-scenario stream** ✅
- [x] Backend per-scenario stream `/stream/scenario/{id}` (gzip binary,
      precompute-once, owner-gated; close codes 4400/4404/4422).
- [x] `scenario-czml` message: chief + deputies, FIXED/ECEF samples, role marker
      + orbit path, effective `stepSeconds` echoed (sample cap, R8).
- [x] Determinism: `loadAndEncode` twice → byte-identical (R11).
- [x] One Zustand `clock` slice; a single rAF writer (`clockEngine`) advances it
      (lockstep by construction). Cesium reads it (4B adds the second reader).
- [x] Time controls: play / pause / step / reset / rate (0.01× – 10000×) /
      reverse + a scenario scrub bar.
- [x] Loading a scenario animates its chief + deputies (orbit paths) in the
      global view, driven by the scenario window; catalog hides during playback,
      restores on close.
- [x] Scrub latency ≤200 ms (SRS §5.1.3) — playback is pure client-side clock
      math over precomputed samples; confirmed responsive in-browser.
- [x] In-browser end-to-end (verified over the dev stack): load a saved scenario,
      drive play/scrub/rate/reverse; live-catalog step/scrub/play-from-time;
      time-range edit; click-to-toggle orbit paths.

**Phase 4B — three.js proximity view** ✅ (backend tests green + frontend build green
+ in-browser pass verified 2026-06-15)
- [x] three.js proximity view scene scaffold renders (`views/ProximityView.tsx`):
      Scene / PerspectiveCamera / WebGLRenderer + OrbitControls, R/I/C axes + grid.
- [x] Chief at LVLH origin (amber marker); deputies as fixed-pixel color-coded points.
- [x] Deputies render at relative positions from the `scenario-relative` stream
      (LVLH R/I/C; backend builds the rotating `lvlh(chiefProp)` transform once and
      transforms per step — R15-correct velocity; chief excluded as the origin).
- [x] Adjustable scale via OrbitControls (1 m min distance) with a distance readout;
      the camera auto-frames the deputies on load (max distance widened for any
      separation, since composed scenarios may be far apart).
- [x] Both views in lockstep — the proximity render loop READS `store.currentTime`
      each frame (never writes), mirroring Globe's preRender; one rAF clock writer.
- [x] One WebSocket serves both viewports (Globe owns the client; the proximity view
      reads a module buffer it fills). `scenario-relative` is the 2nd binary frame.
- [x] In-browser pass over the dev stack (`docker compose up -d --build` incl. the
      frontend with the new `three` dep): load a ≥2-sat scenario, confirm split view +
      lockstep play/scrub, divider resize, hide/show toggle. **Manually verified
      2026-06-15** — scenario load → split view, scrub lockstep, divider resize, and
      the proximity hide/show toggle all confirmed in-browser.

---

## Phase 5 — Relative motion + initial maneuvers

> Sliced **5A / 5B / 5C** (see [phase-5-plan.md](./phase-5-plan.md)).
>
> **5A complete — backend tests green + frontend build green** (2026-06-15):
> live relative readout + backend-computed closest approach.
> **5B complete — backend tests green + frontend build green** (2026-06-15):
> impulsive ΔV maneuvers (RIC), schema v2 + audited mutation + numerical
> re-propagation via Orekit `ImpulseManeuver`, ΔV glyphs + budget.
> **5C complete — backend tests green + frontend build green** (2026-06-15): CW
> fidelity (closed-form STM relative propagator) + Hohmann/Lambert templates.
> **Dev-stack verified (2026-06-15):** backend image rebuilt, `gen:api` regenerated
> from the live spec, and all maneuver endpoints round-trip 200 against the seeded
> demo (add Δv; Hohmann → two prograde impulses; Lambert rendezvous). A full visual
> click-through (CW animation / glyphs / transfer paths) is the remaining nicety.
>
> **Phase-5 closeout — Phases 1–5 audited complete (2026-06-16).** A read-only
> completeness + architecture audit confirmed every Phase 1–5 acceptance criterion is
> backed by code + tests and the load-bearing invariants hold (one streaming contract /
> R12, determinism / R11, frame discipline / R15, single mutation path / Decision 16,
> single clock writer, Decision-5 buffers, frontend-never-propagates / D9). Backend **91
> tests green**, frontend type-check + build green. One low-severity finding fixed:
> `ManeuverTemplateService.toRic` now routes its RIC projection through `FrameService`
> (the canonical path, Decision 12 / R15) instead of a hand-rolled QSW basis — behaviour
> unchanged, guarded by `ManeuverTemplateServiceTests`. The distance-vs-time graph
> (Decision 22) also landed in this window. **Phase 6 next** (proximity visualization).

**5A — relative-state analysis** ✅ (backend tests green + frontend build green)
- [x] Relative-state readout (distance, range-rate, R/I/C components) per
      deputy, updated per frame (`RelativeReadout.tsx`; throttled rAF off the
      relative buffer, ≤10 rows, color-keyed to the proximity view).
- [x] Closest-approach time + distance computed (backend, full-resolution
      golden-section refine in `ScenarioStreamService`; carried additively in the
      `scenario-relative` envelope) and annotated on the timeline + readout.
- [x] Distance-vs-time graph (`DistanceChart.tsx`, a `Table | Graph` tab in the
      readout): per-deputy chief-relative range over the scenario window, hand-rolled
      SVG (no charting dependency), static curves + an imperatively-driven "now"
      cursor (Decision 5). Window toggle (1h/6h/1d/7d/All) + pan scroller + follow so
      long many-orbit scenarios read as lines; the All overview is a min/max envelope
      band when samples overplot pixels. Per-deputy in-view closest-approach marker
      (labeled; note: the sample-grid minimum, coarser than the backend's refined
      TCA in the table), log distance axis, date-aware time axis, and a deputy-filter
      legend that rescales the y-axis to the shown set. See Decision 22.

**5B — impulsive ΔV maneuvers** ✅ (backend tests green + frontend build green)
- [x] Impulsive ΔV maneuver: add via UI (`ManeuverPanel.tsx`), scenario
      versioned (schema v2, one audit row, `MANEUVER_ADD`/`MANEUVER_REMOVE`),
      propagation re-runs (reload nonce reopens the stream). Maneuvered deputies
      propagate **numerically** via `ImpulseManeuver` (SGP4 can't be reset).
- [x] ΔV vectors annotated in proximity view at maneuver epochs (`ProximityView`
      ΔV `ArrowHelper` glyphs, shown within a scrub window of the epoch).
- [x] Cumulative ΔV budget per spacecraft visible (`ManeuverPanel` Σ|ΔV| per
      deputy).
- [x] Dev-stack round-trip (5A + 5B): backend rebuilt + `gen:api` regenerated; add
      Δv returns 200 and persists a new version; `scenario-relative` carries TCA.
      (Full visual click-through — readout live, timeline tick, glyph/budget — is the
      remaining nicety.)

**5C — CW fidelity + transfer templates** ✅ (backend tests green + frontend build green)
- [x] CW fidelity works for close-range scenarios (`CwPropagation` closed-form STM
      relative provider; chief on SGP4, deputies seeded R15-correctly from the live
      LVLH frame; piecewise impulses). The fidelity selector offers `cw`; a banner
      warns when separation > ~10 km or the chief isn't near-circular (envelope
      `fidelity`/`maxSeparationM`/`chiefEccentricity`). No longer 4422.
- [x] Hohmann transfer template: target altitude → two prograde impulses inserted
      (`POST /scenarios/{id}/maneuvers/hohmann`; vis-viva; via the audited path).
- [x] Two-impulse rendezvous template (Lambert): arrival epoch → two ΔV computed
      (`POST /scenarios/{id}/maneuvers/rendezvous`; Orekit `IodLambert`).
- [x] Dev-stack round-trip (5C): Hohmann + rendezvous endpoints return 200 and
      insert two impulses each via the audited path; CW scenarios stream (no 4422)
      with the validity hint. (Full visual click-through — CW animation, transfer
      render, budget — is the remaining nicety.)

---

## Phase 6 — Proximity visualization

> Sliced **6A / 6B / 6C** (see [phase-6-plan.md](./phase-6-plan.md), Decision 23).
>
> **Phase 6 complete — backend tests green + frontend type-check/build green**
> (2026-06-16). The bare `THREE.Points` proximity scene became a real scene:
> procedural spacecraft models (+ a GLTF-swap seam) with a fixed-pixel marker LOD,
> derived ram/LVLH orientation (estimated — attitude is Phase 7), past/predicted
> `Line2` trajectory ribbons, camera modes (external / chief-body / deputy-body),
> and an Earth backdrop placed from a new additive `chiefRadiusM` stream field.
> New `frontend/src/proximity/{spacecraftModel,orientation,ribbons,cameraModes,
> earthBackdrop}.ts`; the only backend touch is the one additive field
> (`RelativeStateEncoder` + `ScenarioStreamService`). Backend **91 tests green**
> (the `chiefRadiusM` assertions fold into the two stream tests); the in-browser
> dev-stack click-through is the remaining manual nicety.

**6A — spacecraft models + orientation (US-PROX-01, US-PROX-02)** ✅
- [x] Spacecraft rendered as 3D models (`proximity/spacecraftModel.ts`): procedural
      box bus + two solar arrays on named hinge joints + a dish gimbal
      (`MeshStandardMaterial`, palette-tinted), built under a per-craft `Group`
      positioned + oriented each frame. A `GLTFLoader` swaps in
      `/public/models/spacecraft.glb` when present, falling back to the primitive (R6).
- [x] Articulable parts present as named joints at a static **deployed** pose (no
      faked sun-tracking — drivers arrive Phase 7/8).
- [x] Derived orientation (`proximity/orientation.ts`): ram-pointing from the
      LVLH-frame velocity (stride 7), fixed LVLH pose for stride-4 / the chief;
      labeled "estimated" in the legend (attitude is Phase 7). Frontend derives
      nothing physical — it consumes the streamed state (Decision 9, R15-clean).
- [x] Fixed-pixel marker far-LOD keeps the colored dots at 100 km (no regression);
      the model fades in by apparent size with a near-plane scale clamp. Lockstep
      clock unchanged (the loop only READS `currentTime`).

**6B — trajectory ribbons + camera modes (US-PROX-03, US-PROX-04)** ✅
- [x] Past-solid / predicted-dashed trajectory ribbons per deputy
      (`proximity/ribbons.ts`): a depth-tested, sliding-window `THREE.Line` trail
      (±WINDOW_SECONDS around `currentTime`) selected per frame via `setDrawRange`,
      geometry built once from the client-side `samples`. Windowed (not the whole
      multi-orbit span) for legibility (Decision 22) and to stop the trail smearing
      over the Earth; honors the renderer's logarithmic depth buffer.
- [x] Renderer logarithmic depth buffer — the 1 m–100,000 km range (Earth backdrop)
      z-fights a normal buffer (flickering Earth); fixed across the whole range.
- [x] Camera modes (`proximity/cameraModes.ts`): external (free orbit), chief-body,
      deputy-body — one OrbitControls + a body-ride rig with an eased target
      transition on switch. A camera `<select>` + Earth/Stars/Off control in the
      `.proximity-controls` overlay (React state mirrored to refs — no 60 fps Zustand).

**6C — Earth backdrop (US-PROX-05)** ✅
- [x] Backend emits the chief's geocentric radius (`chiefRadiusM`) on the
      `scenario-relative` envelope — additive, `VERSION="1"` (R12); determinism
      intact (R11). It is a WebSocket-payload field, not REST/OpenAPI (`gen:api`
      is a no-op). Asserted Earth-scale in `ScenarioStreamServiceTests` (real
      propagator) + structurally in `RelativeStateEncoderTests`.
- [x] Earth backdrop (`proximity/earthBackdrop.ts`): a true-scale single-sphere Earth
      along −R at the chief radius (correct limb at LEO, small disc at GEO) + starfield;
      procedural material (no texture asset — R6), no atmosphere shell (it z-fought the
      surface); flat non-physical lighting (Sun vector is Phase 8). Earth/Stars/Off
      toggle ("Off" = pure space). Falls back to a representative LEO radius when absent.
- [x] **Decaying / unprocessable scenarios handled** — a body leaving the propagator's
      valid domain (decay, or a maneuver below the surface → Orekit "point is inside
      ellipsoid") previously crashed the whole stream (1011 + reconnect storm + blank).
      Now `sampleRole`/`encodeRelative` **HOLD** the last valid point per-sample (bail on
      first failure) so a body decaying *partway* still loads with its trail ending; a
      body *never* valid in the window → clean **4422** + logged reason + a
      `scenarioStreamError` banner. Verified end-to-end on the dev stack: a valid 11-day
      SGP4 scenario streams (4978 samples); the demo → `chiefRadiusM` Earth-scale; a
      saved scenario with a degenerate **12 km/s** ΔV → 4422 ("deputy … never reaches a
      valid relative state"), not a hang/crash.
- [x] Ribbons render **smooth** on coarsely-sampled long scenarios (R8 step ~190 s →
      ~28 pts/orbit) via Catmull-Rom densification — no faceted "cutting off".

---

## Phase 7 — Sensors & FOV

> Sliced **7A / 7B** (see [phase-7-plan.md](./phase-7-plan.md), Decision 24).
>
> **Phase 7 complete — backend 113 tests green + frontend type-check/build green**
> (2026-06-18). Sensors are first-class scenario objects + backend-authoritative modeled
> attitude (7A); acquisition/loss-of-sight events + occlusion + sensor-frame camera (7B).
> New backend `analysis/` package + `FrameService` attitude helpers + `ScenarioBody`
> schema v3; new `frontend/src/proximity/sensors.ts` + `scenario/SensorPanel.tsx`. The
> only contract change is additive WebSocket-payload fields (`VERSION="1"`, R12). Verified
> on the dev stack (sensor add → 200, bad FOV → 422, WS frame carries chief/attitude/
> sensors, a wide cone yields an acquisition at 565 m).

**7A — sensor model + modeled attitude + FOV rendering (US-SENSE-01/02, US-PROX-01)** ✅
- [x] Sensors as first-class scenario entities: `ScenarioBody` schema v3
      (`Sensor`/`Fov`/`Mount`/`AttitudeProfile` on chief OR deputy), forward-additive
      (v1/v2 bodies migrate; no DB migration). Add/remove/set-attitude through the single
      audited `ScenarioService` path (`SENSOR_ADD`/`SENSOR_REMOVE`/`ATTITUDE_SET`); REST
      `POST/DELETE /scenarios/{id}/sensors`, `PUT /scenarios/{id}/attitude`
      (`gen:api` regenerated). Cone (half-angle) + rectangular (H×V°), body-fixed pointing.
- [x] Backend-authoritative **modeled** attitude (`FrameService.bodyQuaternionInLvlh`):
      `lvlh` (LVLH-aligned from the orbital state) or `fixed` (constant inertial), streamed
      as a three.js-convention quaternion (`chief` block + per-deputy `att`, additive). The
      convention is pinned to three.js by a signed-axis test (R15). Frontend consumes it
      (SLERP), retiring the Phase-6 estimate; legend reads "modeled".
- [x] Translucent FOV volumes in the proximity view (`proximity/sensors.ts`), riding each
      craft's body frame, with a Sensors view/opacity control. `SensorPanel.tsx` with
      type presets (narrow imager / wide 20°×15° / rendezvous lidar).

**7B — acquisition/loss events + occlusion + sensor-frame camera (US-SENSE-04/05, US-EVT-01)** ✅
- [x] Acquisition / loss-of-sight detection (`analysis/SensorEventComputer`): in-FOV ∧
      in-range ∧ Earth line-of-sight unobstructed, on the sample grid + bisection refine;
      deterministic (R11). Streamed in an additive top-level `events` array.
- [x] Occlusion: Earth (analytic ray vs the WGS84 sphere). Inter-spacecraft is negligible
      (point targets); the **Sun (occlusion + sun-keep-out) is deferred to Phase 8**.
- [x] Timeline AOS/LOS windows (`Timeline.tsx` — acquisition→loss bands).
- [x] Sensor-frame camera mode (`cameraModes.ts` `sensor` — look along the boresight).

**Deferred (Decision 24):** CCSDS AEM measured attitude; gimbaled pointing;
frustum/polygonal FOV; exact rectangular FOV containment for events (v1 uses a bounding
cone); Sun occlusion / sun-keep-out (Phase 8); GPU-depth occlusion of the drawn volume.

---

## Phase 8 onwards

Acceptance criteria for Phases 8–11 will be drafted when the respective
phase begins, informed by what we learned in earlier phases. The
[user-stories outline](./user-stories.md#phase-8--environment--events-outline)
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
