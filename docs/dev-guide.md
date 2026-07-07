# Developer guide

The handover guide for a developer inheriting this repo. It owns the material no
other doc covers — the module maps, the runtime pipeline narratives, the change
recipes, and the consolidated gotcha list — and links out for everything else.

Companions to keep open in week one:
- [README.md](../README.md) — quick-start, ports, feature map.
- `CLAUDE.md` (repo root) — the agent-context file: stack, architecture rules,
  build commands. Its "Invariants" list is the same contract as §6 below.
- [decisions.md](./decisions.md) — the WHY for every architectural choice
  (29 decisions + a deferred-work registry at the end). When you're about to
  change something structural, check here first — the alternative you're
  considering has usually already been weighed.
- [build-history.md](./build-history.md) — how the system was built, phase by
  phase (frozen history).

Suggested reading order: this file top-to-bottom → README quick-start (get it
running) → decisions.md as needed per subsystem → [user-guide.md](./user-guide.md)
to learn the tool as a user.

Contents:
[1 Day one](#1-day-one--get-it-running) ·
[2 System at a glance](#2-system-at-a-glance) ·
[3 Backend map](#3-backend-map) ·
[4 Frontend map](#4-frontend-map) ·
[5 Runtime pipelines](#5-the-two-runtime-pipelines) ·
[6 Invariants](#6-the-rules-you-must-not-break) ·
[7 Recipes](#7-recipes--your-first-changes) ·
[8 Gotchas](#8-gotchas) ·
[9 Verification](#9-verification--smoke-tests) ·
[10 Doc map](#10-which-doc-to-update-when) ·
[11 Handover state](#11-handover-state)

---

## 1. Day one — get it running

Follow [README.md](../README.md) (Docker Compose quick-start, both auth modes)
and [frontend/README.md](../frontend/README.md) (frontend dev loop). Environment
facts you'll hit in the first hour, collected:

- **JDK:** local backend work needs JDK 21 at `$HOME/jdk-21.0.11+10`:
  `export JAVA_HOME=$HOME/jdk-21.0.11+10 && export PATH=$JAVA_HOME/bin:$PATH`
  (already in `~/.bashrc` on the original dev box).
- **Ports are remapped** because this is a shared box: backend host **8081** →
  container 8080 (host 8080 is taken by another service), frontend host **5174**
  → container 5173. The OIDC overlay adds an nginx TLS front on **8443**.
  All base-stack ports bind to `127.0.0.1`.
- **`./gradlew test` requires Docker** — the persistence/streaming/security
  tests run against a real ephemeral Postgres via Testcontainers (the schema
  uses PG-only `TEXT[]`/`jsonb`/`gen_random_uuid()`). `./gradlew build -x test`
  needs neither Docker nor a DB.
- **orekit-data is not committed.** The Gradle task `provisionOrekitData`
  downloads it into `backend/build/orekit-data/` (tests depend on it
  automatically); the backend Docker image bakes its own copy at
  `/opt/orekit-data` (`orbit.orekit.data-path`).
- **Cesium ion token:** `frontend/.env` needs `VITE_CESIUM_ION_TOKEN`
  (free at ion.cesium.com); copy `frontend/.env.example`.

If something looks broken, check [§8 Gotchas](#8-gotchas) before debugging.

## 2. System at a glance

```
┌─ Browser ─────────────────────────────────────────────┐
│  Global view (CesiumJS globe)   Proximity view (three.js, chief-LVLH)
│  panels · timeline · one shared clock (frontend-owned playback)
└───────┬───────────────────────────────┬───────────────┘
   REST /api/* (OpenAPI-generated   WebSocket /api/stream/*
   client, 31 endpoints)            · /stream/catalog   — one SHARED broadcast
        │                           · /stream/scenario/{id} — per-user
┌───────┴───────────────────────────────┴───────────────┐
│  Spring Boot backend (Java 21, Orekit 13.1.5)          │
│  catalog SGP4 pass · scenario CRUD/versioning/audit ·  │
│  propagation (SGP4 / numerical / CW) · analysis        │
└───────────────────────┬────────────────────────────────┘
                   PostgreSQL (Flyway V1–V5; jsonb scenario bodies)
```

The load-bearing concepts, one line each (details behind the links):

- **Chief + deputies** — a scenario is one reference craft (the LVLH origin) +
  up to 10 deputies ([Decision 14](./decisions.md)).
- **Two streaming modes** — the catalog is one shared computation broadcast to
  everyone; scenarios are per-user ([Decision 13](./decisions.md)).
- **Frozen inputs** — each scenario role stores a TLE snapshot (or a
  content-hashed measured dataset) at compose time, so reruns are reproducible
  ([Decisions 19, 26](./decisions.md)).
- **Precompute-once streaming** — on connect the backend computes the whole
  bounded ephemeris and pushes it; playback/scrub is pure client-side clock math
  ([Decision 11](./decisions.md)).
- **Frame-tagged states** — every state vector carries its frame; all
  conversions go through `FrameService` ([Decision 12](./decisions.md)).
- The wire format is specified in
  [streaming-contract.md](./streaming-contract.md).

## 3. Backend map

Base package `space.orbit.backend` under
`backend/src/main/java/space/orbit/backend/` (~103 classes):

| Package | ≈ | What lives there |
|---|---|---|
| `prop/` | 14 | The propagation engine — thin Orekit wrappers. `PropagationService` (fidelity dispatch + maneuver attachment), `SatellitePropagator` (SGP4), `NumericalPropagation` (DP8(7) + J4+ + drag + SRP + third-body), `CwPropagation`/`CwTargeting` (Clohessy-Wiltshire), **`FrameService`** (the one canonical frame utility — ECI/ECEF/LVLH/RIC/body, quaternion conventions), `StateVector` (frame-tagged), `Fidelity`, `Impulse`, `MeasuredAttitude`, `QuaternionSamples`, `PropagationSettings` (pinned constants), `OrekitConfig` (data bootstrap). |
| `catalog/` | 4 | The live ~15.5k-satellite catalog: `CatalogService` (seed + refresh + the scheduled SGP4 broadcast pass), `TrackedSatellite`, `TleSnapshot`, `CatalogProperties`. |
| `scenario/` | 37 | The domain core: JPA entities (`Scenario`, `ScenarioVersion`, `User`, `AuditLog`, `MeasuredDataset`), **`ScenarioService`** (the single audited mutation path — every edit writes one version + one audit row), `ScenarioBody` (the versioned jsonb body, schema v6), `ManeuverTemplateService` (Hohmann/rendezvous/phasing/NMC/hold/glideslope/station-keep), `RendezvousCorrector`, `ChiefStateResolver`, `MeasuredEphemerisFactory`, `SampleScenarioSeeder` + `UserProvisioner`, request/response DTOs. |
| `stream/` | 15 | WebSocket streaming: `CatalogStreamHandler` (broadcast + seek/snapshot/orbit), `ScenarioStreamHandler` + `ScenarioHandshakeInterceptor` (identity captured at handshake), **`ScenarioStreamService`** (`loadAndEncode` — the precompute-once heart), `CzmlEncoder`, `RelativeStateEncoder`, `StreamContract` (`VERSION = "1"`), `StreamGzip`, `WebSocketConfig`, `ScenarioStreamProperties`. |
| `analysis/` | 21 | Sampled-trajectory computers (they read the already-sampled arrays, never re-propagate — see §6): `SensorEventComputer`, `EclipseEventComputer`, `ConjunctionEventComputer`, `ConstraintChecker`, `LinkBudgetComputer`, `MonteCarloService`, `RendezvousSearchService`, `ScreeningService` + result/event records. |
| `io/` | 5 | File I/O: `GpCatalogParser` (TLE/OMM seed), `WodCsvReader` + `MeasuredEphemeris` (measured telemetry import), `OemExportService` (CCSDS OEM export). |
| `api/` | 4 | REST: **`ScenarioController`** (≈30 mappings — where every endpoint lives), `HealthController`, `ScenarioExceptionHandler` (404/409/422), `OpenApiConfig`. |
| `security/` | 2 | `SecurityConfig` — two `@ConditionalOnProperty("orbit.auth.mode")` filter chains (`stub` default / `oidc` resource-server + RBAC) — and `DevUserAuthenticationFilter` (deliberately *not* `@Component`; see §8). |

**Convention:** there is no `config/` package — config beans are colocated with
their feature (`OrekitConfig` in `prop/`, `WebSocketConfig` in `stream/`,
`OpenApiConfig` in `api/`).

**Configuration** (`backend/src/main/resources/application.yml`, 12-factor env
overrides): `orbit.auth.mode` (stub|oidc), `orbit.orekit.data-path`,
`orbit.catalog.*` (seed file, source URLs, refresh cron, pass interval, window),
`orbit.import.allowed-root` (the measured-import path jail, `/shared_folder`),
`orbit.scenario.*` (step-seconds, max-samples-per-sat, include-relative-velocity),
plus `spring.datasource`/`spring.flyway` and the OIDC issuer.

**Persistence:** Flyway migrations `V1__init` … `V5__measured_dataset` in
`backend/src/main/resources/db/migration/`; JPA runs `ddl-auto=validate` —
Flyway owns the schema, an entity/DDL mismatch fails boot.

**Tests:** `backend/src/test/java/...` mirrors main packages plus `validation/`
(the §5.2 conformance suite). 39 files, **217 tests**. Pure-JUnit prop/analysis
tests need no DB; anything touching persistence/streams/security uses
Testcontainers (Docker required).

## 4. Frontend map

Under `frontend/src/` (entry `main.tsx` → `App.tsx`):

| Dir | What lives there |
|---|---|
| `store/` | **`useStore.ts`** — the Zustand store: UI/control state only (clock, selection, composer, scenario list, panel state) + all REST-backed actions. **`clockEngine.ts`** — the single rAF loop; the **only** writer of `currentTime` during playback. |
| `stream/` | `CatalogStreamClient.ts` / `ScenarioStreamClient.ts` (gzip WebSocket clients; contract-version refusal; scenario client treats 44xx closes as fatal) and **`relativeBuffer.ts`** — a module singleton holding the high-frequency LVLH/attitude/event samples **outside Zustand** (Decision 5); ProximityView reads it every frame. |
| `api/` | `client.ts` (openapi-fetch, base `/api`, Bearer middleware), `schema.d.ts` (**generated** — `npm run gen:api`), `contract.ts` (`STREAM_CONTRACT_VERSION = '1'` — must equal backend `StreamContract.VERSION`). |
| `views/` | `ProximityView.tsx` — the three.js chief-LVLH scene (scene setup, rAF `drawFrame`, rebuild-on-scenario-change). |
| `proximity/` | Scene helpers, one lifecycle each: `spacecraftModel`, `orientation`, `ribbons`, `cameraModes`, `sensors`, `earthBackdrop`, `montecarlo`. |
| `components/` | `Globe.tsx` (Cesium viewer + catalog/scenario CZML layers + Decision-18 camera), `Timeline`, `TimeController`, `DistanceChart`, `RelativeReadout`, `InfoPanel`, `FilterPanel`, `StatsOverlay`, `PerfHud`, `HelpOverlay`, `FirstRunHint`, `StatusChip`, `PanelDock`, `ErrorBoundary`. |
| `scenario/` | The panel cards: `ScenarioPanel`, `ManeuverPanel`, `SensorPanel`, `EnvironmentPanel`, `MonteCarloPanel`, `AuditLogPanel`. |
| `export/` | Phase-11 export: `captureRegistry` (viewports register `{canvas, renderNow, setExportMode}`), `capture` (PNG), `mp4Exporter` (WebCodecs + mp4-muxer offline render), `eventsExport` (JSON/CSV builders), `ExportPanel`, `download`. |
| `auth/` | `AuthGate` (stub pass-through or OIDC PKCE login), `UserChip`, `config.ts` (`VITE_AUTH_MODE` or runtime `/env.js`), `token.ts` (bridges the OIDC token to the REST middleware + WS `?access_token=`). |
| `lib/` | `perf.ts` (the §5.1 instrumentation), `format.ts`, `constellations.ts`, `usePanelChrome.ts`. |

Two env vars matter for tooling: the Vite dev proxy target is **`PROXY_TARGET`**
(`vite.config.ts`), while `npm run gen:api` reads **`BACKEND_URL`** (both default
to `http://localhost:8081`).

## 5. The two runtime pipelines

No other doc narrates these end-to-end; message shapes are in
[streaming-contract.md](./streaming-contract.md).

### 5.1 Catalog (shared broadcast)

1. `CatalogService` loads the bundled OMM seed at startup (CelesTrak is
   firewalled on the dev box; a GitHub mirror refresh is best-effort, default
   every 6 h).
2. A scheduled pass (default every 30 s) runs SGP4 over all ~15.5k satellites
   for a ~180 s window → `CzmlEncoder` → one multi-MB CZML message → `StreamGzip`
   → broadcast as a binary frame to every `/stream/catalog` session
   (`CatalogStreamHandler`; new sessions get the latest pass immediately).
3. `CatalogStreamClient` inflates with the native `DecompressionStream`,
   verifies `contractVersion`, and feeds `CzmlDataSource.process()` — Cesium
   merges packets by id and interpolates between samples.
4. Stepping/scrubbing the live catalog leaves the broadcast: the client sends
   `{kind:"seek", epoch, windowSeconds}` on the same socket and gets a private
   `catalog-snapshot`; clicked orbit paths use `{kind:"orbit"}` →
   `catalog-orbit` (Decision 21).

### 5.2 Scenario (per-user, precompute-once)

1. `loadScenario(id)` in `useStore` fetches the scenario via REST, sets the
   clock bounds, and opens `ScenarioStreamClient` →
   `/api/stream/scenario/{id}?access_token=…`.
2. The handshake interceptor captures identity (WS callbacks run outside the
   servlet security window); the owner gate collapses missing/not-owned to a
   fatal `4404` close.
3. `ScenarioStreamService.loadAndEncode` does everything **once**: rebuilds each
   role's propagator from its frozen TLE / measured dataset (fidelity dispatch —
   SGP4 / numerical / CW; maneuvered deputies force numerical with impulse or
   finite-burn force models attached), samples the whole window on one time grid
   (the R8 cap raises the step and echoes `stepSeconds`), transforms deputies
   through the **rotating** chief-LVLH frame, samples attitude + Sun/Moon, and
   runs the `analysis/` computers over the sampled arrays (sensor events,
   eclipse, conjunctions, constraints, link budgets, TCA refine).
4. Two gzip frames go down the socket: `scenario-czml` (the globe's scenario
   layer) and `scenario-relative` (everything else). `relativeBuffer.ts` parses
   the latter; `ProximityView` interpolates from it per frame.
5. **Playback never touches the network.** `clockEngine` advances `currentTime`;
   Globe copies it into Cesium's clock in `preRender`; ProximityView reads it in
   its rAF loop. Scenario edits (maneuvers, sensors…) go through REST, bump a
   reload nonce, and simply reopen the stream (re-propagation = a fresh
   `loadAndEncode`).

## 6. The rules you must not break

The invariants, each with its backing decision/risk. `CLAUDE.md` carries the
same list as agent context — **if you change one here, change it there.**

1. **One streaming contract, additive only.** `VERSION` stays `"1"`; new
   envelope fields are optional and trailing; older clients must keep working
   (R12, Decision 10). Update `StreamContract.java` + `api/contract.ts` together.
2. **Every state is frame-tagged and converted through `FrameService`.**
   Relative velocity comes from the rotating-LVLH transform built from the live
   chief provider — never the single-epoch `toRelativeState` (R15, Decision 12).
   Pin any new frame/quaternion convention with a **signed-axis test**.
3. **Deterministic propagation — byte-identical reruns** (R11, SRS §5.4.1). No
   wall-clock, no unseeded RNG, fixed iteration counts, ordered collects. The
   two documented exceptions: catalog screening (live-catalog snapshot) and the
   OEM header date (pinned to the version timestamp).
4. **One `currentTime` writer** — `clockEngine.ts`. Render loops and views only
   read (Decision 11). Export code steps time through the existing
   `seek`/`setCurrentTime` actions, not by writing the store directly.
5. **Ephemeris lives in stream buffers, not Zustand** (Decision 5). Per-frame
   data flows module-singleton → rAF read; Zustand is for control/UI state.
6. **All scenario mutations go through `ScenarioService`** — one immutable
   version + one audit row per edit, in one transaction (Decision 16). Never
   write a version or audit row anywhere else. (Narrow precedent: OEM export
   writes an `EXPORT_OEM` audit row with *no* version — exports don't mutate.)

Two more patterns that function as rules:
- **`analysis/` computers consume the already-sampled trajectory** — they never
  re-propagate (a stateful maneuvered propagator queried out of order returns an
  inconsistent trajectory; Decision 24 records the original bug).
- **`ScenarioBody` evolves forward-additively** (see recipe 7.3) — no DB
  migrations for body-shape changes, ever (precedent v2→v6).

## 7. Recipes — your first changes

### 7.1 Add a REST endpoint end-to-end

1. Request/response DTOs: records in `scenario/` (web-agnostic drafts) and/or
   the controller file; bean-validate inputs (`@Valid`, constraints).
2. Endpoint method in `api/ScenarioController.java` (add `@Operation`/`@Tag` —
   every endpoint is documented) delegating to a `ScenarioService` method. If it
   *mutates* a scenario, follow the existing pattern there: load latest body →
   validate (422 via typed exceptions) → save new version + one audit row in the
   same transaction.
3. Rebuild the backend (`docker compose up -d --build backend`), then regenerate
   the client: `npm run gen:api` (or inside the container — see §9).
4. Frontend: an action in `store/useStore.ts` calling the typed client, then a
   panel/UI consumer. Type-check proves the contract.

### 7.2 Add a `scenario-relative` stream field (additively)

1. Compute it in `stream/ScenarioStreamService.loadAndEncode` (or an `analysis/`
   computer fed by the sampled arrays).
2. Widen `RelativeStateEncoder.encodeRelative` with a **trailing** parameter and
   a `write*` helper that **omits the field when null/empty** (so old clients
   ignore it). `VERSION` stays `"1"`.
3. Parse it in `frontend/src/stream/relativeBuffer.ts` (extend
   `RelativeFrameData` + `parseRelativeMessage`; add an interpolating accessor
   if it's a time series).
4. Consume it (ProximityView / Timeline / a panel). Update
   [streaming-contract.md](./streaming-contract.md) in the same change, and
   extend the encoder/stream tests including the byte-identical rerun.
   (`gen:api` is a no-op — stream fields are not REST.)

### 7.3 Add a `ScenarioBody` field (forward-additive schema bump)

The v2→v6 precedent, in `scenario/ScenarioBody.java`:
1. Add the field to the record + a convenience constructor with the **old
   arity** so existing call sites don't change.
2. Null-coalesce it in the canonical constructor / `parse()` (an old stored body
   deserializes with the field null → default).
3. Bump `CURRENT_SCHEMA_VERSION`; `parse()` re-stamps old bodies on save.
4. **No DB migration** — the body is opaque jsonb. Add a round-trip test that an
   old-version JSON parses and re-saves as the new version.

### 7.4 Add a Flyway migration

New file `backend/src/main/resources/db/migration/V6__<name>.sql` (next number).
Keep the matching JPA entity exactly in sync — `ddl-auto=validate` fails boot on
mismatch. Testcontainers applies migrations automatically in tests; the running
Compose stack applies them on backend restart. Only for *relational* changes —
scenario-body shape changes use 7.3 instead.

### 7.5 Run one backend test class

```bash
cd backend && export JAVA_HOME=$HOME/jdk-21.0.11+10 && export PATH=$JAVA_HOME/bin:$PATH
./gradlew test --tests "*RendezvousCorrectorTests"          # pure JUnit, no Docker
./gradlew test --tests "*ScenarioServiceTests"              # Testcontainers → needs Docker
```

### 7.6 The dev-loop iteration cycle

Backend edit → `docker compose up -d --build backend` → wait for health → if the
REST contract changed, regenerate the client inside the frontend container:
`docker compose exec -T frontend sh -c 'BACKEND_URL=http://backend:8080 npm run gen:api'`
→ `docker compose exec -T frontend sh -c 'npm run type-check'`. Frontend source
edits need nothing — the bind mount + HMR pick them up. Frontend *dependency*
changes need the §8 anon-volume dance.

## 8. Gotchas

The "something is weird" lookup page.

**Environment / dev loop**
- **Frontend deps changed but the container doesn't see them** — anonymous
  volumes persist across recreation: `docker compose build frontend` →
  `docker compose rm -v -s -f frontend` → `docker compose up -d frontend`.
  Full story in [frontend/README.md](../frontend/README.md).
- **Vite uses polling file-watch** (`usePolling`) because the host's
  `fs.inotify.max_user_instances` is only 128 — Cesium + three.js would EMFILE
  otherwise. Don't "optimize" it away.
- **Ports:** backend is host **8081** (container 8080 — host 8080 is occupied),
  frontend host **5174** (container 5173). Browser code must use the `/api`
  proxy path, never an absolute backend URL.
- **`./gradlew test` hangs or fails immediately** → Docker isn't available
  (Testcontainers). `build -x test` needs no Docker.
- **First backend test run is slow** → `provisionOrekitData` is downloading the
  Orekit data archive (cached afterwards).

**Auth / import**
- **The OIDC dev stack is HTTPS-only on :8443** (nginx, self-signed cert in
  `deploy/nginx/certs/`, single origin for frontend + Keycloak + API): OIDC PKCE
  needs Web Crypto (`crypto.subtle`), which browsers expose only in a secure
  context. Sign in as `maya/maya`, `frank/frank`, or `gita/gita`.
- **Issuer string vs JWKS URL are deliberately split** in the overlay: the
  backend validates the token's `iss` as the *string*
  `https://<host>:8443/realms/orbit` but fetches signing keys over the docker
  network (`http://keycloak:8080/...`) so it never has to trust the self-signed
  cert. If you change the host/IP or port, the `docker-compose.oidc.yml` header
  comment lists every place to update.
- **Measured import 422 "outside allowed root"** — server-path import is jailed
  to `orbit.import.allowed-root` (`/shared_folder`, a read-only mount of
  `/mnt/disk_large/shared_folder`).

**Physics / numerics**
- **`EPHEMERIS_INTERP_POINTS = 2` — never raise it.** Orekit's `Ephemeris`
  Hermite-fits degree 2N−1 through N points; at the WOD's ~5-min spacing N=4
  overshoots between nodes (Runge) to a ~1e11 km orbit while every *node* looks
  correct. Guarded by `interpolatesStablyBetweenNodes` (R19).
- **A measured craft renders mirrored/inverted?** Flip the named constants in
  `prop/MeasuredAttitude` (`SCALAR_LAST` / `CONJUGATE`) — the WOD quaternion
  convention was pinned empirically (R20); the flip is one line.
- **Lambert returns multi-km/s ΔV** for co-orbital or ~180°-transfer geometries —
  that's a degeneracy, not a bug; co-orbital rendezvous is a *phasing* problem.
  Use the rendezvous **search** (ΔV map) or the phasing template.
- **An impulse at exactly the propagator seed epoch doesn't fire**
  (`DateDetector` at t0). Frozen TLE epochs normally precede the window; beware
  scenario start == TLE epoch in hand-built tests.
- **`NumericalPropagation.build` reloads space-weather data each call** — the
  dominant per-sample cost in Monte Carlo. Don't raise sample counts blindly;
  cache here first if MC perf matters.
- **Corrector finite-difference steps must beat integrator noise** — the
  rendezvous corrector uses a 1 m/s FD step because 0.05 m/s produced a
  noise-swamped non-descent direction. Same trap for any new Newton loop
  against adaptive integrators.
- **Monte Carlo determinism recipe** (R21): per-sample
  `SplittableRandom(mix(seed,i))`, fixed intra-sample draw order, index-ordered
  collect, canonicalized eigenvectors, bounded pool (≤6 — an unbounded pool
  crashed the test JVM).
- **A deputy decays / leaves the valid domain** → its trail HOLDs from the first
  failure; a body never valid in the window → WebSocket close 4422 + an error
  banner (not a crash). A saved scenario with an absurd ΔV (≥ 5 km/s budget) is
  flagged in the maneuver panel.

## 9. Verification & smoke tests

```bash
# Backend — full suite (217 tests; needs Docker)
cd backend && export JAVA_HOME=$HOME/jdk-21.0.11+10 && export PATH=$JAVA_HOME/bin:$PATH
./gradlew test
# The two load-bearing proofs, runnable alone:
./gradlew test --tests "*RendezvousCorrectorTests" --tests "*MonteCarloServiceTests"
./gradlew test --tests "*ValidationConformanceTest"     # §5.2 conformance

# Frontend
cd frontend && npm run type-check && npm run build

# Stack smoke
docker compose up -d --build
curl -s localhost:8081/health          # {"status":...,"dbStatus":"up",...}
# List your scenarios (stub mode = the dev user; demos are seeded per user):
curl -s localhost:8081/scenarios | python3 -m json.tool | head -40
# Exercise an analysis endpoint against a seeded demo (take an id from the list):
curl -s -X POST localhost:8081/scenarios/<id>/monte-carlo \
  -H 'Content-Type: application/json' \
  -d '{"deputyNoradId":99002,"sampleCount":40,"seed":7,"posSigmaM":100,"velSigmaMs":0.1,"dvMagFrac":0.02,"dvPointingDeg":0.5}'
```

Demo scenario UUIDs are **per-database** (each user gets their own seeded
copies) — always resolve ids via `GET /scenarios`. The five demos: NMC
formation (chief 99001 / deputy 99002), rendezvous (chaser 99003),
sensor/link-budget, eclipse, V-bar station.

In the browser: load a demo → both viewports animate in lockstep; scrub feels
instant; `?perf=1` (or the ⏱ toggle) shows the PerfHud with the §5.1 targets.

## 10. Which doc to update when

| You changed… | Update |
|---|---|
| An architectural choice (new approach, new dependency, something reversed) | [decisions.md](./decisions.md) — append a numbered decision (Context/Decision/Why/Alternatives/Consequences); deferred ideas go in its end registry |
| The wire format (envelope fields, message types, close codes) | [streaming-contract.md](./streaming-contract.md) + `StreamContract.java` + `api/contract.ts`, same change |
| Something that could derail the project | [risks.md](./risks.md) (numbered risk with mitigation + trigger) |
| Agent-facing rules, build commands, invariants | `CLAUDE.md` (keep its invariants in sync with §6) |
| User-visible behavior / workflows | [user-guide.md](./user-guide.md) |
| Validation posture / conformance evidence | [validation-conformance.md](./validation-conformance.md) |
| Requirements / scope | The [SRS](./Software%20Requirements%20Specification.md) is authoritative — plus [user-stories.md](./user-stories.md) / [use-cases.md](./use-cases.md) |
| Deployment (chart, images, bundle) | [deployment.md](./deployment.md) |
| Module maps, recipes, gotchas | This file |

[build-history.md](./build-history.md) and everything it merged is **frozen** —
never update it as the system evolves.

## 11. Handover state

Honest ledger of what's open (as of 2026-07-07; all 11 roadmap phases shipped):

- **§5.1 performance evidence is recorded** (2026-07-07, browser on an RTX 4090 —
  hardware WebGL, above the SRS mid-range bar; table in
  [phase-11-plan.md](./phase-11-plan.md)). Passes at typical loads (proximity 60 fps at
  1–4 craft; globe 30 fps / its cap with the scenario layer; scrub p95 69 ms; 10-craft
  SGP4 load 1.79 s). **Two documented misses, both tracked follow-ups (not blockers):**
  (1) the full ~15.5k-dot catalog *overlaid on a scenario* drops the globe to ~10 fps —
  R7, the CZML-Entity per-frame eval is CPU-bound on the main thread (GPU-irrelevant, so a
  4090 doesn't help); fix = `PointPrimitiveCollection` / LOD (Decision E). (2) A 10-craft
  proximity scene (the SRS ceiling) sits at ~30 fps — a per-frame main-thread cost that is
  **not** the ribbon `setSplit` (that's already cheap); needs a browser Performance profile
  before any change. §5.1.4 heavy-numerical (10-craft 24 h numerical) is the R18 outlier,
  not the ≤5 s baseline. Ranked fix candidates pre-scoped in the phase plan — apply none
  unmeasured (the profile comes first for the proximity one).
- **Phase-11 manual browser click-throughs** (PNG/MP4 export, OEM export in oidc
  mode + audit row, fresh-OIDC-user demo seeding) — mechanisms are test-covered;
  the checklist is in phase-11-plan.md / acceptance-criteria §Phase 11.
- **The Helm chart has never been installed on a live Kubernetes cluster**
  (lint/template clean; OIDC round-trips on Compose). A `kind`/`k3s` run would
  exercise ingress/TLS/Secrets/StatefulSet — see acceptance-criteria §Phase 10.
- **Measured-data slice 3 is designed but unbuilt** — measured deputies (a real
  RPO pair), numerical handoff beyond the data window, OEM/AEM readers, browser
  upload. Plan: [measured-data-plan.md](./measured-data-plan.md).
- **Everything deliberately deferred** lives in the registry at the end of
  [decisions.md](./decisions.md) — notably composable/chained maneuver templates
  and the generic relative-state targeter (both scoped there in detail).

Suggested first week: run the stack and every demo (§9 + user-guide) → read
decisions.md 9, 11, 13, 16 (the architecture spine) → make a small change with
recipe 7.1 → then pick up one open item above.
