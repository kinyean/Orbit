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
**Phase 4B complete — backend tests green + frontend build green (in-browser pass
pending).** Phase 5 next (relative-state analysis readouts + initial maneuvers;
see roadmap §7).

**Phase 4B** (three.js proximity view + per-scenario `scenario-relative` stream):
backend `stream` adds `RelativeStateEncoder` (plain-JSON `scenario-relative`
envelope) + `RelativeSamples` DTO; `ScenarioStreamService.loadAndEncode` now also
samples each deputy's LVLH R/I/C (and velocity) on the **same time grid** as the
CZML and populates `EncodedScenario.relative` (the handler already sent it when
non-null). **R15 (critical):** the LVLH transform is built **once** from the *live*
chief propagator (`frames.lvlh(chiefProp)`) and applied per step
(`eci.getTransformTo(lvlh,date).transformPVCoordinates(deputyEci)`) — **not**
`FrameService.toRelativeState` (single-epoch constant provider → wrong relative
velocity). Frontend: `three` + a `stream/relativeBuffer.ts` module singleton (outside
Zustand, Decision 5) that Globe's `onRelative` fills and `views/ProximityView.tsx`
reads each frame; the proximity render loop READS `store.currentTime` (never writes)
→ lockstep by construction, mirroring Globe's `preRender`. Chief at the LVLH origin
(amber); deputies as fixed-pixel color-matched points (R→+X, I→+Y, C→+Z, 1 unit=1 m);
camera auto-frames. `App.tsx` is a **resizable split** (globe left / proximity right,
draggable divider) that appears only when a scenario is loaded, with a toggle that
**unmounts** the proximity pane (frees the 2nd WebGL context). One WebSocket serves
both viewports. Display-only (no cross-view click yet). Contract stays `VERSION="1"`.
See [docs/streaming-contract.md](docs/streaming-contract.md) (`scenario-relative`).
A `SampleScenarioSeeder` (scenario pkg, on `ApplicationReadyEvent`, idempotent via
`ScenarioService.seedIfAbsent`) seeds a demo **"Demo — close formation (NMC)"** for
the dev user: two *synthetic* sats (NORAD 99001/99002) on a bounded NMC relative
orbit (equal mean motion ⇒ no drift; small Δe/Δi → a ~2–4 km LVLH ellipse) so the
proximity view shows a real circumnavigation out of the box. NOTE: TLE line
serialization caps the NORAD id at 5 digits (`getLine1()`/`getLine2()` throw on
6-digit) — the formation test exercises that round-trip.

**Phase 4A** (authoritative shared clock + per-scenario CZML stream — the global
view now *plays* a loaded scenario): backend `stream` gained a per-connection
WebSocket `/stream/scenario/{id}` (`ScenarioStreamHandler` extends
`TextWebSocketHandler`; **not** broadcast). Identity is captured at handshake by
`ScenarioHandshakeInterceptor` (the WS thread runs outside the security-filter
window, where `DevUserAuthenticationFilter` has cleared the context).
`ScenarioStreamService.loadAndEncode` is **precompute-once** (Decision 11): rebuild
each role's TLE from the body's frozen line strings (`new TLE(l1,l2,utc)`),
`Fidelity.fromString`-dispatch (CW→4422), sample ECEF per step, encode one
`scenario-czml` message — sequential/ordered/no-RNG so it's byte-identical on
rerun (R11). `CzmlEncoder.encodeScenario` reuses the catalog FIXED-position block
+ role-colored markers + orbit `path` + an **effective `stepSeconds` echo** (the
`max-samples-per-sat` clamp raises the step, never silently truncates — R8).
Shared `StreamGzip`; context-free reads `ScenarioService.bodyForStream` /
`UserService.findByEmail` (no user provisioning on connect; not-owned collapses
to 4404 to avoid id enumeration). Close codes 4400/4404/4422. New
`ScenarioStreamProperties` (`orbit.scenario.*`). Contract stays `VERSION="1"`
(additive, R12). Frontend: a real clock slice (`rate`/`direction`/`bounds`) + a
single-writer `clockEngine` rAF loop (the sole `currentTime` writer → lockstep by
construction); Cesium's autonomous clock severed (`shouldAnimate=false`,
`multiplier=0`, `targetFrameRate=30`) and driven from the store in a `preRender`
listener (which also hosts the selection-position updater moved off the
now-silent `onTick`); a `ScenarioStreamClient` (fatal close codes 44xx → no
reconnect) feeding a second `CzmlDataSource('scenario')`; the catalog layer hidden
during scenario playback; a rewritten `TimeController` (play/pause/step/reset/
reverse/log-rate 0.01×–10000×) + a `Timeline` scrub bar. **No three.js yet** —
that + `scenario-relative` is 4B. See
[docs/phase-4-plan.md](docs/phase-4-plan.md) and
[docs/streaming-contract.md](docs/streaming-contract.md).

**Phase 4A follow-ups (Decision 21):** (1) **Live catalog time-travel** — the
clock slice gained a `catalogLive` flag; stepping/scrubbing in catalog mode
freezes the globe and sends `{kind:"seek",epoch}` on the catalog socket, and the
backend replies with a per-session `catalog-snapshot` (whole catalog propagated
to that instant; `CatalogService.buildSnapshotMessage` via a seek-handler
callback registered on `CatalogStreamHandler` — no service↔handler cycle). The
client applies snapshots always but ignores the live broadcast while frozen.
Playing **from** a traveled time is supported via **rolling rate-scaled prefetched
snapshots** (seek carries `windowSeconds`; the client widens it with the rate and
refetches before the window edge), **capped at 100×** so per-user bandwidth stays
bounded; a `● LIVE` toggle returns to realtime. (2) **Time-range editor** — the
composer carries `start`/`end`;
the scenario panel has UTC `datetime-local` inputs (used on create *and* when
editing a loaded scenario), and saving an edit reloads via a `scenarioReloadNonce`
so the per-scenario stream recomputes for the new window. See Decision 21.
(3) **Orbit paths** — single-clicking a catalog satellite toggles a dashed
orbit-path polyline on the globe (multiple at once; click again to remove). The
client sends `{kind:"orbit",noradId,epoch}` on the catalog socket; the backend
propagates that one sat over **one period** from `epoch` and replies with
`catalog-orbit` (ECEF positions only, `CatalogService.buildOrbitMessage` via an
orbit-handler callback). The path is **live** — the client re-requests at the
current sim clock as time advances (drift threshold), so it precesses with the
moving dots. Colors are **round-robin** (next palette slot, avoiding ones in use)
for max distinctness. The toggle is **debounced + cancelled by double-click**, so
double-click stays pure focus (Decision 18) and never draws a path; paths clear
when a scenario loads (catalog hidden). Drawn as a `PolylineDashMaterialProperty`
line (`arcType NONE`).

**Phase 3B** (numerical propagation + relative frames, backend-only — no UI/
contract change, proven by `./gradlew test`): new in `prop` — `Fidelity` enum
(`fromString` defaults unknown→`SGP4`), `PropagationSettings` (pinned,
deterministic `DEFAULT`: 500 kg, 1 m², Cd 2.2, Cr 1.8, gravity 16×16, DP8(7)
posTol 1e-3 m), `NumericalPropagation` (Orekit `NumericalPropagator`: DP8(7) +
Holmes-Featherstone gravity ≥J4 + NRLMSISE-00 drag + SRP + Sun/Moon third-body;
seeded from the SGP4 ECI state; gravity-field μ seeds the orbit so the
auto-added Newtonian central term agrees), and `PropagationService` (the
fidelity-dispatch seam Phase 4 calls — `sgp4`/`numerical`; `cw` throws until
Phase 5 — plus a uniform `sample()→StateVector` for both engines). `FrameService`
v2 adds chief-centered `lvlh()`/`ric()` (Orekit `LOFType.LVLH`≡`QSW`, the
glossary R/I/C — **not** `LVLH_CCSDS`), `toRelativeState`, a minimal static
`body()` frame, and an `earth()` accessor. Tests pin frame orientation by signed
axis (radial→+R, in-track→+I, cross-track→+C), a closed relative-orbit loop, and
bit-identical numerical reruns (Decisions 19→20; SRS §5.4.1). See
[docs/decisions.md](docs/decisions.md) Decision 20 and
[docs/phase-3b-plan.md](docs/phase-3b-plan.md).

**Phase 3A** (scenario composition on SGP4): backend `scenario` package — JPA
entities mapped to `V1__init.sql` (+ V2 seed dev user, V3 soft-delete),
repositories, `ScenarioService` (the single audited/versioned mutation path,
Decision 16), `UserService`, `ScenarioBody` (jsonb body schema v1 with a frozen
TLE snapshot per role), and `api/ScenarioController` + `@RestControllerAdvice`
(404/409/422). `CatalogService` gained a NORAD→`TleSnapshot` resolver. Frontend:
scenario store slice (calls the generated client), wired InfoPanel role buttons,
and a real ScenarioPanel (list/save/load/delete, names via the catalog index).
Tests: `@DataJpaTest` (Testcontainers Postgres), `ScenarioService` slice,
`@WebMvcTest`. New deps: `spring-boot-starter-validation`, Testcontainers;
springdoc bumped 2.6.0→2.8.9 (Spring Boot 3.5 compatibility — 2.6.0 500s on
`/v3/api-docs` once a `@ControllerAdvice` exists).

Phase 2 (still in place) on top of Phase 1's dual-container dev env:
- **Orekit 13.1.5** propagation core: SGP4 via `SatellitePropagator` (wraps
  Orekit `TLEPropagator`), `FrameService` (ECI/ECEF/geodetic, frame-tagged
  `StateVector`), OMM→TLE conversion. The reachable catalog mirrors serve OMM
  JSON (no TLE lines), so `TleFactory` builds TLEs from mean elements
  (ndot/nddot=0; SGP4 ignores them).
- **Catalog mode** (Decision 13): loads a bundled offline TLE seed (~15.5k
  sats) + best-effort GitHub-mirror refresh (CelesTrak is firewall-blocked
  here), propagates the whole set every 30 s, broadcasts one shared CZML feed
  over WebSocket `/stream/catalog`. ~100–650 ms/pass, 7.36 MB/message.
- **Streaming contract v1** (docs/streaming-contract.md): JSON envelope +
  CZML; ECEF/FIXED positions; `contractVersion` checked client-side (R12).
- **Frontend**: `CatalogStreamClient` → `CzmlDataSource` on the globe;
  click-to-inspect (hit-padded, live position), **single-click inspect /
  double-click focus** (smooth blend into an ENU tracked-entity orbit — no
  auto-zoom, no twist; Decision 18) + reset-view, constellation filters
  (name-prefix; localStorage), search-to-fly, live stats. satellite.js +
  client-side propagation/fetch removed.
- Data bundles (orekit-data + TLE seed) baked into the backend image; fully
  offline-capable. Backend at :8081, frontend at :5174 (8080/5173 taken).

Verified in-browser: ~15.5k dots render and animate smoothly; click-inspect,
constellation filters, search-to-fly, and double-click focus all work (R7
PointPrimitiveCollection fallback not needed; no FPS counter instrumented).

**Phase 5 next:** relative-state analysis (distance / range-rate / R-I-C readouts,
closest-approach), the closed-form CW fidelity for close range, and initial
impulsive ΔV maneuvers (+ templates) with re-propagation. The proximity view (4B)
already renders the relative motion the 3B engine produces; Phase 5 adds the
analysis + maneuver layer on top. See
[docs/architecture-and-roadmap.md §7](docs/architecture-and-roadmap.md).

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
