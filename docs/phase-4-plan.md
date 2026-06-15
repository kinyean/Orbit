# Phase 4 plan — Dual viewports + shared clock

> **Status (2026-06-12): Slices 4A + 4B shipped** — backend `./gradlew test` green
> and frontend `build` green. 4A: the per-scenario `/stream/scenario/{id}` CZML
> stream, the single-writer shared clock, time controls + scrub bar, catalog
> hidden during playback (+ follow-ups: live catalog time-travel / play-from-time,
> time-range editor, click-to-toggle orbit paths — see Decision 21). 4B: the
> `scenario-relative` stream (LVLH R/I/C per deputy, built from a live
> `lvlh(chiefProp)` transform — R15), a `three.js` proximity view
> (`views/ProximityView.tsx`) reading the shared clock in lockstep, and a
> resizable split-screen that appears only with a scenario (toggle unmounts it).
> Done: in-browser end-to-end verified on the dev stack 2026-06-15 (scenario
> load → split view, scrub lockstep, divider resize, proximity hide/show toggle).
> Phase 5 is next (see [phase-5-plan.md](./phase-5-plan.md)).
>
> Planning artifact, written ahead of implementation (same workflow as
> [phase-2-plan.md](./phase-2-plan.md), [phase-3-plan.md](./phase-3-plan.md),
> [phase-3b-plan.md](./phase-3b-plan.md)). Phase 3 shipped (scenarios + numerical
> propagator + LVLH/RIC frames); this is the next phase. Sliced **4A** (shared
> clock + per-scenario stream — the global view animates a loaded scenario) then
> **4B** (three.js proximity view + relative-state + lockstep). Pick up as active
> work in a fresh session. Companion to
> [acceptance-criteria.md §Phase 4](./acceptance-criteria.md) and
> [architecture-and-roadmap.md §7](./architecture-and-roadmap.md).

## Context

Phase 3 made scenarios real (compose → save → version → audit) and gave the
backend a high-fidelity numerical propagator + LVLH/RIC frames. But a loaded
scenario is still **static** — `loadScenario` only repopulates the composer; the
globe animates only the live catalog, driven by Cesium's own internal clock.
Phase 4 turns a saved scenario into something you **play**: one authoritative
simulation clock both views obey, full time controls (play/pause/step/rate
0.01×–10000×/reverse + scrub bar), a per-scenario WebSocket stream, and a second
**three.js proximity view** showing the deputies' relative motion in the chief's
LVLH frame.

This is the phase that lights up the engine built in Phases 2–3B. Maps to
US-VIEW-01/02/03/04 and US-STREAM-02; SRS §3.3 (clock), §3.9 (proximity view),
§3.11 (controls), §6.1.3 (shared clock); Decisions 4, 10, 11, 13.

## Decisions locked (planning session, 2026-06-10)

- **Streaming model = precompute-once.** Scenarios are bounded (≤11 sats, finite
  `[start,end]`). On connect the backend computes the **whole** ephemeris and
  pushes it (gzipped, like the catalog); playback (scrub/rate/reverse) is then
  **pure client-side** clock math over the delivered samples → the ≤200 ms scrub
  target is met trivially (Decision 11: backend authoritative on state, frontend
  owns playback control). A client→server control channel is *reserved* on the
  same socket for Phase 5 (re-propagation on maneuver edits), **not built now**.
- **One clock writer.** A single `requestAnimationFrame` loop in a store-internal
  engine is the **only** code that advances `currentTime`; Cesium and three.js
  only *read* it each frame → lockstep is correct **by construction**, no
  per-view clock can drift.
- **Layout = resizable split-screen** (global left / proximity right, draggable
  divider). Cesium capped at `targetFrameRate=30`, proximity targets 60 fps
  (SRS §5.1.1–2).
- **Dependency: add `three` + `@types/three`** (Decision 4 chose three.js; this
  is the actual npm add — no Vite plugin needed). **No `vitest`** this phase
  (declined) → the US-VIEW-02 sync check is **manual + structural** (single-writer
  design + extracted pure functions), not an automated test; revisit when a
  frontend test harness is introduced.
- **Catalog dims during scenario playback.** The catalog feed is a live ~180 s
  window around *now*; it cannot represent a scenario's (arbitrary/historical)
  epoch — its dots would HOLD at the window edge. So when a scenario clock is
  active, hide/dim the catalog layer; restore it when no scenario is loaded.
- **Contract stays `VERSION="1"`** — the new endpoint + message types are purely
  additive (R12).

---

## Slice 4A — Authoritative shared clock + per-scenario CZML stream

**End state:** load a saved scenario → its chief + deputies animate in the global
view with orbit paths, driven by a real shared clock with working
play/pause/step/reset/rate/reverse and a scrub bar over the scenario's time range.
Shippable on its own (single viewport).

### Frontend

**1. Clock slice + engine** — `store/useStore.ts` (edit) + `store/clockEngine.ts` (new)
- Extend the clock slice: replace the vestigial `currentTime`/`isPlaying` with
  `{ currentTime: Date, isPlaying: boolean, rate: number, direction: 1 | -1,
  bounds: { start: Date, end: Date } | null }` plus setters `setRate`,
  `toggleDirection`, `seek(t)`, `step(deltaSec)`, `reset()`, `setBounds(start,end)`.
  Keep the per-slice subscription pattern (Decision 5).
- `clockEngine.ts`: an idempotent `startClockEngine()` running ONE rAF loop: when
  `isPlaying`, `currentTime += rate * direction * dtRealMs`, clamped to `bounds`
  (pause at the edge). Reads `rate`/`direction` fresh each frame (no loop restart
  on change). This is the **sole writer** of `currentTime`. Started once from an
  `App.tsx` mount effect; stopped on unmount.

**2. Sever Cesium's autonomous clock** — `components/Globe.tsx` (edit)
- Set `viewer.clock.shouldAnimate = false`, `viewer.clock.multiplier = 0`,
  `viewer.targetFrameRate = 30`.
- Delete the one-time epoch seed (`Globe.tsx` ~L199–202); on the first catalog
  message instead call `setBounds`/`seek` only if no scenario clock is active
  (the catalog "live" regime).
- Add a `scene.preRender` listener that copies `store.currentTime` →
  `viewer.clock.currentTime` every frame (one reused `JulianDate` scratch). CZML
  `SampledPositionProperty` interpolation and the Decision-18 focus/track code
  keep working unchanged (they read `viewer.clock.currentTime`, which stays
  authoritative).
- **Move** the throttled selected-satellite lat/lon/alt updater from
  `viewer.clock.onTick` (`Globe.tsx` ~L231–248) into the same `preRender`
  listener — `onTick` stops firing once `shouldAnimate = false`, which would
  freeze the InfoPanel. (Confirmed against the live file.)

**3. Time controls + scrub bar** — `components/TimeController.tsx` (rewrite) + `components/Timeline.tsx` (new)
- TimeController: play/pause (wire `isPlaying` + engine), step ±1 frame
  (`step()`), reset (`reset()` → jump to `bounds.start`), reverse toggle
  (`toggleDirection`), and a **log rate slider** `rate = Math.pow(10, v)`,
  `v ∈ [-2, 4]` → 0.01×–10000× with a readout. Reads the clock slice; writes via
  setters.
- Timeline: a bottom scrub bar showing `bounds.start … current … end`; drag →
  `seek(t)` (normalized 0..1000 input domain mapped to `[start,end]`). Event
  annotations (maneuvers/eclipses) are later phases — leave the hook, render none.

**4. Scenario stream client + global scenario layer** — `stream/ScenarioStreamClient.ts` (new), `components/Globe.tsx` (edit), `store/useStore.ts` (edit)
- `ScenarioStreamClient` mirrors `stream/CatalogStreamClient.ts` (same gzip
  `DecompressionStream`, `contractVersion !== "1"` refuse, exponential-backoff
  reconnect) but: URL `/api/stream/scenario/{id}`; routes by `type` —
  `scenario-czml` → `onCzml(czml)`, `scenario-relative` → `onRelative(block)`
  (used in 4B); does **not** reconnect on close codes 44xx (4400/4403/4404/4422 —
  fatal), only on transport drops.
- Add a `loadedScenario` store object `{ id, name, body }` and rework
  `loadScenario(id)`: after the REST fetch, also `setBounds(timeRange)`,
  `seek(start)`, and open a `ScenarioStreamClient`; closing/replacing a scenario
  closes the stream. Dim the catalog layer while a scenario is active.
- In `Globe.tsx` add a second `CzmlDataSource('scenario')` fed by the client.
  Scenario packets carry a CZML `path` (orbit trail) + role color (chief vs
  deputy) so they're visually distinct from catalog dots.

### Backend (`backend/src/main/java/space/orbit/backend/`)

**5. Endpoint + handshake** — `stream/WebSocketConfig.java` (edit), `stream/ScenarioHandshakeInterceptor.java` (new), `stream/StreamContract.java` (edit)
- Register a raw handler at `"/stream/scenario/*"` (single-segment wildcard; raw
  handlers don't template `{id}`), `.setAllowedOrigins(...)` (reuse
  `orbit.stream.allowed-origins`), `.addInterceptors(interceptor)`.
- `ScenarioHandshakeInterceptor implements HandshakeInterceptor`: in
  `beforeHandshake` parse the last path segment → `attributes.put("scenarioId", …)`
  and capture `request.getPrincipal().getName()` → `attributes.put("principalName", …)`.
  **Why:** WS handler callbacks run outside the servlet filter window, and
  `DevUserAuthenticationFilter` clears `SecurityContextHolder` in a `finally`, so
  `UserService.currentUser()` would throw on the WS thread — capture identity at
  handshake instead.
- `StreamContract`: add `SCENARIO_ENDPOINT_PATTERN`,
  `MESSAGE_TYPE_SCENARIO_CZML = "scenario-czml"`,
  `MESSAGE_TYPE_SCENARIO_RELATIVE = "scenario-relative"`, and close-code constants
  (`4400` bad id; `4403`/`4404` not-owned/not-found — collapse 4403→4404 to avoid
  id enumeration; `4422` unusable body / CW fidelity / TLE-parse failure).
  `VERSION` stays `"1"`. Hoist the catalog send limits (`30_000` ms / `32` MB)
  here so both handlers share them.

**6. Per-connection handler** — `stream/ScenarioStreamHandler.java` (new, `extends TextWebSocketHandler`)
- NOT broadcast: no shared `Set`/`latestMessage`. In `afterConnectionEstablished`:
  wrap in `ConcurrentWebSocketSessionDecorator(session, 30_000, 32 MB)`; read
  `scenarioId` + `principalName` from session attributes; call the stream service;
  gzip each JSON and send two `BinaryMessage`s (CZML, then relative). Keep the
  socket open (idle) for the reserved control channel. Map typed exceptions →
  close codes (§5).
- `handleTextMessage`: reserved (log + ignore in v1). `handleTransportError`:
  close `SERVER_ERROR`.
- The heavy work (build ≤11 propagators + sample + encode) runs on the container
  thread inside `afterConnectionEstablished` — fine for v1 (tens of ms);
  offload to a bounded executor only if it becomes a bottleneck (follow-up).

**7. Scenario → ephemeris service** — `stream/ScenarioStreamService.java` (new, `@Service @DependsOn("orekitConfig")`)
- `EncodedScenario loadAndEncode(UUID id, String callerEmail)`:
  1. `ScenarioBody body = scenarioService.bodyForStream(id, callerEmail)` (new
     context-free read, §8).
  2. `Fidelity fidelity = Fidelity.fromString(body.fidelity())`; `CW` → typed
     exception → 4422.
  3. Rebuild each role's TLE from the **frozen line strings**:
     `new TLE(line1, line2, TimeScalesFactory.getUTC())` (verified present in
     Orekit 13.1.5 via the sources jar — **no `TleFactory` change**). Parse
     failure → 4422.
  4. `Propagator p = propagationService.propagatorFor(tle, fidelity)` per role
     (reuse one propagator per sat across all samples).
  5. **Effective step**: clamp so `(durationSec / step) + 1 ≤ max-samples-per-sat`
     and **echo the effective step** in the envelope (never silently truncate; R8).
  6. **ECEF samples (CZML, 4A):** `p.getPVCoordinates(date, frames.ecef())` per
     step → flat `[t, X, Y, Z, …]` (mirror `CatalogService` sampling; keep it
     fidelity-agnostic — do **not** use the SGP4-typed
     `SatellitePropagator.ecefPosition`).
  7. Sequential, ordered loops; no `parallelStream`, no wall-clock, no RNG → the
     ephemeris is a pure function of `(body, props)` (R11; enables a future
     byte-compare test).

**8. Context-free reads** — `scenario/ScenarioService.java` (edit), `scenario/UserService.java` (edit)
- `ScenarioService.bodyForStream(UUID, String email)` `@Transactional(readOnly = true)`:
  resolve the user by email **read-only** (do NOT provision a user on a stream
  connect), run the same gate as `activeScenario` (exists ∧ not soft-deleted ∧
  owned) → `ScenarioNotFoundException` on miss; return the parsed latest-version
  body. Leave `get(UUID)` (the request-thread path) untouched.
- `UserService.findByEmail(String)` read-only (no `getOrCreate` side effect).

**9. CZML encoding** — `stream/CzmlEncoder.java` (edit, additive) + `stream/StreamGzip.java` (new) + `stream/ScenarioSatelliteSamples.java` (new)
- Add `encodeScenario(Instant start, int stepSeconds, List<ScenarioSatelliteSamples> sats)`:
  envelope `{ contractVersion:"1", type:"scenario-czml", epoch, satelliteCount,
  stepSeconds, czml:[…] }`; each per-sat packet reuses the catalog position block
  (FIXED / LAGRANGE / degree-clamp / HOLD / rounded) **plus** a `path` trail and a
  `role` property. Leave `encodeCatalog` byte-identical.
- Extract the gzip helper out of `CatalogStreamHandler` into a shared
  `StreamGzip.gzip(String)` util (DRY; both handlers call it).
- DTO record `ScenarioSatelliteSamples { String role, int noradId, String name,
  double[] cartesian }`.

**10. Config** — `stream/ScenarioStreamProperties.java` (new), `application.yml` (edit)
- `@ConfigurationProperties("orbit.scenario")` record
  `{ int stepSeconds = 30, int maxSamplesPerSat = 5000,
  boolean includeRelativeVelocity = true, boolean includePaths = true }`;
  register it the same way `CatalogProperties` is (`@EnableConfigurationProperties`).
  Add the `orbit.scenario.*` block with 12-factor env overrides.

---

## Slice 4B — three.js proximity view + relative-state stream

**End state:** a second viewport renders the chief at the LVLH origin with deputy
markers at their relative positions, animating in lockstep with the global view.

### Backend
- `stream/RelativeStateEncoder.java` (new) + `stream/RelativeSamples.java` (new):
  `encodeRelative(Instant start, int stepSeconds, List<RelativeSamples> deputies)`
  → `{ contractVersion:"1", type:"scenario-relative", epoch, stepSeconds,
  frame:"LVLH", chiefId, deputies:[ { id, noradId, name, interpolationDegree,
  samples:[t, R, I, C, (vR, vI, vC), …] } ] }`. This is **plain JSON, not CZML** —
  three.js consumes it directly.
- `ScenarioStreamService`: add LVLH sampling. **Critical (R15 / correctness):**
  build **one** `Frame lvlh = frames.lvlh(chiefPropagator)` (a `Propagator` *is* a
  `PVCoordinatesProvider`) and per step do
  `frames.eci().getTransformTo(lvlh, date).transformPVCoordinates(deputyEci)`.
  Do **NOT** call `FrameService.toRelativeState` per sample — its single-epoch
  `constantProvider` drops the LVLH frame's rotation rate, giving correct relative
  *position* but **wrong relative velocity**. (`toRelativeState` stays for the
  single-epoch UI readout it was built for.) Chief = origin (skip it). The handler
  sends this as the second frame.

### Frontend
- `npm i three @types/three` (no Vite plugin needed).
- `views/proximity/ProximityView.tsx` (new): Scene / PerspectiveCamera /
  WebGLRenderer + `OrbitControls`; a chief marker at the origin; one mesh per
  deputy positioned from the `scenario-relative` samples **interpolated at
  `store.currentTime`** (linear between bracketing samples in v1; spline later).
  LVLH→three.js axis map **R→+X, I→+Y, C→+Z** (right-handed), **1 scene unit =
  1 m**. Scale 1 m–100 km via camera dolly (OrbitControls min/max distance) + a
  scale readout. Its render loop READS `currentTime` (never writes). Reads the
  relative block from a buffer the `ScenarioStreamClient` fills (held outside
  Zustand, like the catalog ephemeris).
- `App.tsx` / `App.css` (edit): resizable split-screen — a flex/grid wrapper with
  a draggable divider hosting `<Globe/>` left and `<ProximityView/>` right; the
  bottom controls span both. Reuse the dark-theme `.panel` styling. Provide a
  toggle that unmounts the hidden viewport (the two-GL-context perf escape hatch).
- Extract pure helpers `cesiumTimeForFrame()` and `deputyPositionAt(samples, t)`
  so the lockstep math is unit-testable later (even without vitest now).

---

## Contract & docs
- `docs/streaming-contract.md` (edit): document the `/stream/scenario/{id}`
  endpoint, the two new message `type`s, the relative-block shape, the
  `stepSeconds` echo, and the close-code table. `VERSION` remains `"1"` (additive)
  — the contract doc already promises this Phase-4 extension.
- `frontend/src/api/contract.ts` unchanged (`STREAM_CONTRACT_VERSION = '1'`).
- No `gen:api` needed — the stream is WebSocket (hand-typed on the client); REST
  DTOs don't change.

## Tests & verification
- **Backend** (mirror the existing pure-JUnit + Testcontainers patterns):
  - `stream/ScenarioCzmlEncoderTests` + `stream/RelativeStateEncoderTests` (pure,
    no Spring): envelope `type`/`contractVersion`/`stepSeconds`, packet shape,
    degree-clamp, rounding, `path` present, relative interleave `[t,R,I,C,…]`.
  - `stream/ScenarioStreamServiceTests` (pure-JUnit + `OrekitTestData.ensureLoaded()`,
    mock `bodyForStream`): build a body from **line-string** TLEs (proves the
    `new TLE(l1,l2,utc)` reconstruction); assert chief + deputy ECEF are plausible
    LEO; a co-period deputy traces a **closed bounded loop** in LVLH (reuse the
    Phase-3B `FrameRelativeTests` style — also guards the velocity fix); CW → typed
    exception; the `max-samples-per-sat` clamp raises the effective step and the
    envelope reports it; **determinism** — `loadAndEncode` twice → byte-identical
    (R11).
  - `stream/ScenarioStreamHandlerTests` (full Spring + Testcontainers): connect a
    `StandardWebSocketClient` to a seeded scenario, inflate the gzip frames, assert
    two messages with the right `type`s; non-existent / soft-deleted id → close
    `4404`; malformed id → `4400`.
- **Frontend sync** (no test framework — declined): manual + structural. Lockstep
  is correct **by construction** (single clock writer; both views read
  `currentTime`). Verify in-browser: load a scenario, play/scrub/reverse/rate —
  confirm the global scenario layer and the proximity deputies advance to the
  **same epoch** together and scrub feels instant. The extracted pure helpers keep
  an automated test cheap to add once a harness exists.
- **End-to-end:** `docker compose up -d --build`; open the frontend (`:5174`);
  load a saved scenario; confirm orbit paths + moving chief/deputies in the global
  view, deputies orbiting the chief in the proximity view, the time controls
  (play/pause/step/reset/rate 0.01×–10000×/reverse) + scrub bar all driving both
  views in lockstep, and the catalog dimming during scenario playback (restoring
  on clear). Backend `./gradlew test` green.

## Critical files
- **Frontend new:** `store/clockEngine.ts`, `stream/ScenarioStreamClient.ts`,
  `components/Timeline.tsx`, `views/proximity/ProximityView.tsx`.
- **Frontend edit:** `store/useStore.ts` (clock slice + `loadedScenario` +
  `loadScenario` opens/closes the stream), `components/Globe.tsx` (sever clock,
  preRender sync, move the onTick updater, scenario `CzmlDataSource`),
  `components/TimeController.tsx` (rewrite), `App.tsx` / `App.css` (split-screen),
  `package.json` (three.js).
- **Backend new:** `stream/ScenarioStreamHandler.java`,
  `stream/ScenarioStreamService.java`, `stream/ScenarioHandshakeInterceptor.java`,
  `stream/RelativeStateEncoder.java`, `stream/ScenarioStreamProperties.java`,
  `stream/StreamGzip.java`, and DTO records `ScenarioSatelliteSamples` /
  `RelativeSamples`.
- **Backend edit:** `stream/WebSocketConfig.java`, `stream/StreamContract.java`,
  `stream/CzmlEncoder.java`, `stream/CatalogStreamHandler.java` (use `StreamGzip`),
  `scenario/ScenarioService.java` (+`bodyForStream`), `scenario/UserService.java`
  (+`findByEmail`), `application.yml`, `docs/streaming-contract.md`.

## Risks (carryovers + phase-specific)
- **Clock double-drive** → single rAF writer in `clockEngine.ts`; views read-only.
- **InfoPanel / selection freeze** when severing Cesium's clock → move the onTick
  updater to `preRender` (the selection-ring `CallbackPositionProperty` already
  reads `viewer.clock.currentTime`, kept fresh by the preRender copy).
- **Two GL contexts perf** (SRS §5.1: proximity 60 / global 30) → Cesium
  `targetFrameRate = 30`; the toggle unmounts the hidden viewport.
- **Relative-velocity error (R15)** → continuous `lvlh(chiefProp)` transform, not
  per-sample `toRelativeState`.
- **WS auth off-thread (security)** → identity captured at handshake; context-free
  `bodyForStream`; collapse 4403→4404.
- **Payload (R8)** → precompute is ~130–200 KB gzipped for 11 sats × 24 h × 30 s;
  the `max-samples-per-sat` clamp + echoed step bound it; windowed streaming is the
  reserved Phase-5 escape hatch.
- **Determinism (R11)** → sequential ordered sampling, pinned settings, frozen
  TLEs, no wall-clock / RNG.
- **Contract skew (R12)** → `VERSION = "1"` additive; the new client refuses a
  mismatch; the contract doc is updated.

## Out of scope (later phases)
Relative-state *analysis* readouts / closest-approach / CW (Phase 5); maneuvers
(Phase 5); GLTF models / trajectory ribbons / camera modes (Phase 6); timeline
*event* annotations (Phases 7–8); the client→server playback-control protocol +
windowed re-streaming (Phase 5+); an automated frontend test harness (vitest,
deferred).
