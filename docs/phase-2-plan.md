# Phase 2 plan: Orekit propagation + shared catalog stream

The execution roadmap for Phase 2, with a small Phase 1 closing-touches
prefix. Companion to:
- [acceptance-criteria.md §Phase 2](./acceptance-criteria.md) — the "done"
  checklist this plan ticks off.
- [user-stories.md §Phase 2](./user-stories.md) — the backlog items.
- [architecture-and-roadmap.md §7](./architecture-and-roadmap.md) — the
  one-paragraph Phase 2 description and where it sits in the long arc.
- [decisions.md](./decisions.md) — Decisions 7 (Orekit), 10 (streaming
  contract), 12 (frame management), 13 (dual modes).
- [risks.md](./risks.md) — the risks this phase activates.

## Context

Phase 1 (dual-container dev env + Spring Boot + Postgres + Flyway + frontend
shell) is committed up to `a439c60`. An audit against
[acceptance-criteria.md](./acceptance-criteria.md) found every
infra/DB/security/integration criterion **passes**, with one **stretch**
and a couple of cosmetics worth closing before Phase 2:

- *Stretch:* the only backend test is Spring Initializr's vacuous
  `contextLoads()` (`backend/src/test/java/space/orbit/backend/BackendApplicationTests.java`).
  The acceptance bar says "at least one unit test … passes" — technically
  true, but it doesn't actually assert anything we built. Closing this
  gives Phase 2's Orekit tests a real harness to extend rather than start
  fresh on.
- *Cosmetic:* [`frontend/src/components/InfoPanel.tsx:14`](../frontend/src/components/InfoPanel.tsx)
  has `×` as raw JSX text — it renders as the literal string, not
  the × glyph. We'll touch InfoPanel anyway in Phase 2 (populate real
  fields, add disabled Set-as-chief / Add-as-deputy buttons), so fix it
  then.

**Intended outcome:** finish Phase 1 cleanly with one focused test commit,
then deliver Phase 2 — Orekit-backed SGP4 propagation, a frame utility,
a shared catalog stream over WebSocket, and the frontend repointed from
the deprecated client-side CelesTrak fetch to the backend stream.

---

## Phase 1 closing touches — one small commit

Both items live in a single commit titled *"Phase 1 closing touches: real
HealthController test + InfoPanel × glyph fix"*.

1. **`backend/src/test/java/space/orbit/backend/api/HealthControllerTests.java`**
   (new). `@WebMvcTest(HealthController.class)` (slice test — no full app
   context, no DB). Mock `DataSource` so `Connection.isValid(1)` returns
   true → assert `dbStatus="up"`. Second case: mock returns false → assert
   `dbStatus="down"`. Also assert `contractVersion` equals the constant
   `HealthController.CONTRACT_VERSION` and `version`/`buildTime` come
   through from `@Value` properties. Keeps `BackendApplicationTests.contextLoads()`
   as it is — they exercise different layers.

2. **[`frontend/src/components/InfoPanel.tsx:14`](../frontend/src/components/InfoPanel.tsx)**
   — change literal `×` to `{'×'}` (or just `×`). Inside JSX
   text without braces, escape sequences pass through as literal characters;
   that's why it currently shows the raw 6-character string.

**Verify:** `./gradlew test` runs both `BackendApplicationTests` and
`HealthControllerTests` and both pass without needing a database (slice
tests don't bring up JPA/Flyway). `npm run dev` shows the × button render
correctly in the info panel (selectable by setting a NORAD ID into the
store via React DevTools temporarily, since click-to-select wiring lands
in Phase 2).

---

## Phase 2 execution sequence

Ordered so each step builds on the previous and the smallest end-to-end
slice (one satellite streamed from backend → globe) lands by Step G.

### A. Orekit dependency + data files
- `backend/build.gradle.kts` → add `org.orekit:orekit:12.x` (latest stable
  on Maven Central). Pin major version explicitly per
  [Decision 7](./decisions.md) reproducibility note.
- *Decision:* ship Orekit's data bundle (UTC-TAI history, IERS EOP,
  leap seconds, ephemerides) by depending on `orekit-data` jar OR by
  bind-mounting a directory. Recommend the jar — simpler, deterministic,
  no host setup.
- `backend/src/main/java/space/orbit/backend/prop/OrekitConfig.java`
  (new): `@Configuration` that initializes Orekit's `DataContext` from
  the bundled jar on application startup. One `@Bean` exposing the
  configured `TimeScalesFactory` so the rest of the app can resolve
  UTC/TAI/J2000 without re-initializing.

### B. Frame service v1 (ECI / ECEF / geodetic)
- `backend/src/main/java/space/orbit/backend/prop/FrameService.java`
  (new): thin wrapper around Orekit's `FramesFactory`. Methods:
  - `eciToEcef(Vector3D, AbsoluteDate) → Vector3D`
  - `ecefToGeodetic(Vector3D) → GeodeticPoint` (lat°, lon°, alt km)
  - reverse pair for completeness
- All inputs/outputs carry an explicit frame tag — implemented as a small
  `record StateVector(Vector3D r, Vector3D v, AbsoluteDate t, Frame frame)`
  so it's a compile error to mix ECI and ECEF math (mitigates
  [R15](./risks.md)).
- LVLH / RIC / body frames deferred to Phase 3 with full scenario context.

### C. SGP4 propagator + reference test
- `backend/src/main/java/space/orbit/backend/prop/TLEPropagator.java`
  (new): wrap Orekit's `org.orekit.propagation.analytical.tle.TLEPropagator`.
  Method `propagate(TLE, AbsoluteDate) → StateVector` returning ECI/TEME
  state.
- `backend/src/test/java/space/orbit/backend/prop/TLEPropagatorTests.java`
  (new): a single fixture — ISS TLE at a specific epoch, expected position
  ±2 km of a published reference (e.g., from Orekit's own validation
  fixtures). One round-trip test on FrameService at the same time
  (ECEF → geodetic → ECEF) asserts agreement within 1e-9.

### D. Streaming-contract spec + CZML encoder
- `docs/streaming-contract.md` (new): the versioned message-shape spec
  the frontend will rely on ([Decision 10](./decisions.md)). Sections:
  connection lifecycle (`init` → `czml-chunk` → … → close); payload
  schemas with example JSON; version field semantics; mismatch handling
  ([R12](./risks.md) mitigation).
- `backend/src/main/java/space/orbit/backend/stream/CZMLEncoder.java`
  (new): takes `(catalogTimestamp, List<TLEPropagationSample>) → JSON
  string` encoded as CZML, using `SampledPositionProperty`-compatible
  structure. Cesium's docs at
  [czml-writer](https://github.com/AnalyticalGraphicsInc/czml-writer)
  show the schema; we hand-roll the small subset we need (no transitive
  dep).

### E. Catalog service
- `backend/src/main/java/space/orbit/backend/catalog/CatalogService.java`
  (new): two scheduled tasks (Spring `@Scheduled`).
  - **TLE refresh** (cron-style, default 6 h): pulls
    `https://celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=json`,
    parses, stores in memory. On startup, also restore from a local
    cache file under `/var/orbit/tle-cache.json` (volume-mounted from
    compose) so fresh container starts don't all hit CelesTrak.
  - **Propagation pass** (default 30 s): walks the in-memory TLE set,
    runs SGP4 for each at `now + buffer` over the next chunk window,
    hands the samples to `CZMLEncoder`, broadcasts the chunk to all
    connected WebSocket subscribers, logs `chunkBytes` for monitoring
    ([R8](./risks.md) measurement foundation).
- New env vars in `application.yml`: `CATALOG_REFRESH_HOURS` (default 6),
  `CATALOG_CHUNK_SECONDS` (default 30), `CATALOG_CACHE_FILE` (default
  `/var/orbit/tle-cache.json`), `CATALOG_SOURCE_URL` (default the
  CelesTrak active group).

### F. WebSocket broadcast endpoint
- `backend/src/main/java/space/orbit/backend/stream/WebSocketConfig.java`
  (new): enables WebSocket, registers handler at `/stream/catalog`.
- `backend/src/main/java/space/orbit/backend/catalog/CatalogStreamHandler.java`
  (new): tracks connected sessions in a thread-safe set.
  `afterConnectionEstablished` pushes the latest chunk for warm-start.
  `afterConnectionClosed` removes the session — no leaks. `CatalogService`
  calls `broadcast(chunk)` after each pass.
- Update `SecurityConfig` to permit `/stream/catalog` explicitly (currently
  `permitAll` covers it; keep for clarity once we tighten in Phase 10).

### G. Frontend stream client + Cesium catalog ingestion
- `frontend/src/stream/CatalogStreamClient.ts` (new): opens a WebSocket
  to `/api/stream/catalog` (proxied through Vite; one origin from
  browser POV). Handles version negotiation, reconnect with backoff,
  exposes an event-based API: `onCzmlChunk((json) => …)`,
  `onError(…)`, `close()`.
- [`frontend/src/components/Globe.tsx`](../frontend/src/components/Globe.tsx)
  extensions:
  - On mount, instantiate `CatalogStreamClient`, create a Cesium
    `CzmlDataSource`, add it to `viewer.dataSources`. Pipe chunks
    through `czmlDataSource.process(json)`.
  - Wire `viewer.scene.canvas` click handler: `scene.pick(position)` →
    resolve `id` (set by CZML) → call the (new) store action
    `setSelectedNoradId`. Padded re-scan ±5 px if pick misses.
  - Cleanup: close the stream client on unmount.

### H. Click → info panel populated
- [`frontend/src/store/useStore.ts`](../frontend/src/store/useStore.ts):
  add `selectedSatelliteMeta` slice (`name`, `noradId`, `country?`,
  `launchDate?`, current geodetic position). Setter
  `setSelectedSatellite(meta)` and clearer-named action wired from Globe.
- [`frontend/src/components/InfoPanel.tsx`](../frontend/src/components/InfoPanel.tsx):
  read the new slice; render name, NORAD ID, current lat/lon/alt (from
  the CZML stream's interpolated value), altitude, period, inclination.
  Two **disabled** buttons (with tooltips saying "wired in Phase 3"):
  "Set as chief", "Add as deputy".

### I. Repoint search + filter to the backend catalog
- [`frontend/src/components/FilterPanel.tsx`](../frontend/src/components/FilterPanel.tsx):
  hide entities by constellation by toggling Cesium entity `show` based
  on store filters. The list comes from the streamed catalog (a small
  in-memory index built in `CatalogStreamClient` from the CZML's metadata).
- Search box wiring lives in the top bar
  ([`frontend/src/App.tsx`](../frontend/src/App.tsx)); resolves by name
  substring or exact NORAD ID against the same index, centers the camera
  on the match.
- [`frontend/src/components/StatsOverlay.tsx`](../frontend/src/components/StatsOverlay.tsx):
  replace the hard-coded `14,512 / 11,203` with live `(visible / total)`
  counts.

### J. Archive the client-side stubs
- [`frontend/src/lib/celestrak.ts`](../frontend/src/lib/celestrak.ts)
  → delete (replaced by backend stream; decisions.md superseded entry
  "D" already records the rationale).
- [`frontend/src/lib/propagator.ts`](../frontend/src/lib/propagator.ts)
  → delete (satellite.js retired — superseded entry "B").
- Remove `satellite.js` from
  [`frontend/package.json`](../frontend/package.json) dependencies.

### K. Performance baseline
- Run the full stack against the live catalog, measure:
  - Cold load: connection → all dots visible.
  - Steady-state FPS with hot stream.
  - CZML chunk bytes per pass.
- Record numbers in [`decisions.md`](./decisions.md) under the deferred
  "catalog refresh cadence + sample density" item. If FPS drops below
  30 on dev hardware, back off the chunk frequency or sample density
  before declaring Phase 2 done.

---

## Critical files

### New (backend)
- `backend/src/main/java/space/orbit/backend/prop/OrekitConfig.java`
- `backend/src/main/java/space/orbit/backend/prop/StateVector.java`
- `backend/src/main/java/space/orbit/backend/prop/FrameService.java`
- `backend/src/main/java/space/orbit/backend/prop/TLEPropagator.java`
- `backend/src/main/java/space/orbit/backend/catalog/CatalogService.java`
- `backend/src/main/java/space/orbit/backend/catalog/CatalogStreamHandler.java`
- `backend/src/main/java/space/orbit/backend/stream/CZMLEncoder.java`
- `backend/src/main/java/space/orbit/backend/stream/WebSocketConfig.java`
- `backend/src/test/java/space/orbit/backend/api/HealthControllerTests.java`
- `backend/src/test/java/space/orbit/backend/prop/TLEPropagatorTests.java`
- `backend/src/test/java/space/orbit/backend/prop/FrameServiceTests.java`

### New (docs / frontend)
- `docs/streaming-contract.md`
- `frontend/src/stream/CatalogStreamClient.ts`

### Modified
- [`backend/build.gradle.kts`](../backend/build.gradle.kts) — Orekit dep
  + orekit-data.
- [`backend/src/main/resources/application.yml`](../backend/src/main/resources/application.yml)
  — catalog config vars.
- [`backend/src/main/java/space/orbit/backend/security/SecurityConfig.java`](../backend/src/main/java/space/orbit/backend/security/SecurityConfig.java)
  — explicit permit for `/stream/catalog` (clarity).
- [`docker-compose.yml`](../docker-compose.yml) — env vars + cache-file
  volume for the backend.
- [`frontend/src/components/Globe.tsx`](../frontend/src/components/Globe.tsx)
  — CzmlDataSource + click pick + hit-pad.
- [`frontend/src/components/InfoPanel.tsx`](../frontend/src/components/InfoPanel.tsx)
  — × bug + populated fields + disabled buttons.
- [`frontend/src/components/FilterPanel.tsx`](../frontend/src/components/FilterPanel.tsx)
  — toggles entity visibility.
- [`frontend/src/components/StatsOverlay.tsx`](../frontend/src/components/StatsOverlay.tsx)
  — live counts.
- [`frontend/src/store/useStore.ts`](../frontend/src/store/useStore.ts)
  — `selectedSatelliteMeta` slice + setter.
- [`frontend/src/App.tsx`](../frontend/src/App.tsx) — search-box wiring
  against catalog index.
- [`frontend/package.json`](../frontend/package.json) — drop `satellite.js`.
- [`decisions.md`](./decisions.md) — record chunk cadence + perf
  measurement under Deferred.
- [`acceptance-criteria.md`](./acceptance-criteria.md) — tick boxes off
  as we go.
- [`CLAUDE.md`](../CLAUDE.md) — update "Current phase" once Phase 2
  completes.

### Deleted
- `frontend/src/lib/celestrak.ts`
- `frontend/src/lib/propagator.ts`

---

## Reuse from existing code

- **OpenAPI client generation**
  ([`frontend/src/api/client.ts`](../frontend/src/api/client.ts), `gen:api`
  script) — extend the schema by adding REST endpoints to the backend;
  regenerate. No new tooling.
- **Vite proxy** ([`frontend/vite.config.ts`](../frontend/vite.config.ts))
  — `ws: true` is already on, so WebSocket upgrades route through `/api`
  automatically. The new `/stream/catalog` becomes `/api/stream/catalog`
  in the browser.
- **Spring Security pipeline**
  ([`backend/src/main/java/space/orbit/backend/security/SecurityConfig.java`](../backend/src/main/java/space/orbit/backend/security/SecurityConfig.java))
  — extends without rewrites.
- **Zustand store pattern**
  ([`frontend/src/store/useStore.ts`](../frontend/src/store/useStore.ts))
  — add slice; existing setter style.
- **HealthController**
  ([`backend/src/main/java/space/orbit/backend/api/HealthController.java`](../backend/src/main/java/space/orbit/backend/api/HealthController.java))
  — pattern for new endpoints if any (Phase 2 is mostly WebSocket; REST
  stays light).

---

## Dependencies to add

- **Orekit 12.x** (`org.orekit:orekit:12.x`) — flight dynamics library
  ([Decision 7](./decisions.md)).
- **Orekit data** (`org.orekit:orekit-data:…` jar OR a downloaded data
  bundle) — IERS EOP, leap seconds, ephemerides, UTC-TAI history.

No new frontend dependencies. Cesium handles CZML natively via
`CzmlDataSource`; WebSocket is browser-native.

---

## Active risks during Phase 2

From [risks.md](./risks.md):

- **R1 — Orekit learning curve.** Tracer-bullet first (Step C, ISS
  reference test). Keep the public API of `prop/` deliberately small so
  we can swap internals.
- **R2 — Validation effort underestimated.** Reference test in Step C
  is the beginning of the §5.2 conformance suite; build the patterns
  we'll reuse for Phase 3's numerical propagator.
- **R7 — Full catalog rendering perf.** Measurement is Step K — not an
  afterthought.
- **R8 — CZML chunk sizing.** Default 30 s / 30 s window is a starting
  guess; tune empirically. Record the chosen numbers in `decisions.md`.
- **R12 — Streaming contract version skew.** Every chunk carries the
  contract version; client refuses on mismatch with a clear UI error.

---

## Verification

### Per-step
Each step has its acceptance criterion in
[acceptance-criteria.md §Phase 2](./acceptance-criteria.md). Tick boxes
off as completed.

### Full Phase 2 gate
- `./gradlew test` runs all backend tests (HealthControllerTests,
  TLEPropagatorTests, FrameServiceTests) cleanly without a DB.
- `docker compose up -d --build` brings the stack up; backend logs show
  Orekit data loaded, TLEs refreshed, first SGP4 pass logged with chunk
  size.
- Browser at `http://<server-ip>:5174/` shows **~14,500 dots** on the
  Cesium globe, animating smoothly at ≥30 fps on dev hardware.
- Clicking any dot populates the info panel with that satellite's name,
  NORAD ID, lat/lon/alt, orbital params.
- Set-as-chief / Add-as-deputy buttons render but are disabled (tooltip).
- Filter toggles + search box behave per acceptance criteria.
- StatusChip remains green throughout (Phase 1 regression check).
- No `celestrak.ts` / `propagator.ts` references remain (`grep -r` clean).
- `npm run type-check` passes.

### Smoke-test sequence after Phase 2 lands
1. `docker compose down -v && docker compose up -d --build` — full cold
   start.
2. Wait for backend log: `Orekit data loaded`, `Loaded N TLEs`,
   `Catalog pass: …`.
3. Browser → frontend → ~14,500 dots, status chip green.
4. Click a known satellite by name (search "ISS" → Enter → camera
   centers, click → info panel populated with
   `25544 / ZARYA / RU / 1998-11-20 / …`).
5. Toggle Starlink filter off → ~9,900 Starlink dots vanish in <100 ms.

---

## Out of scope for Phase 2

- High-fidelity numerical propagation (DP8(7) + J4 + drag + SRP +
  third-body) → Phase 3.
- LVLH / RIC / body frame conversions → Phase 3 (when scenarios designate
  a chief).
- Scenario CRUD endpoints → Phase 3.
- Composer wiring (Set-as-chief / Add-as-deputy actually doing things)
  → Phase 3, US-SCN-08/09.
- Proximity view (three.js) → Phase 4.
- Real OIDC/SAML auth → Phase 10.
- Sensor modeling, eclipse, conjunction analysis, Monte Carlo →
  Phases 7–9.
- Self-hosting Cesium imagery tiles → deferred; revisit if ion 5 GB/mo
  cap approached.
