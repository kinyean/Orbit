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
**Phase 11 complete (11A/11B/11C) — polish & ship. The roadmap's eleven phases are done.**
Backend **217 tests green**, frontend type-check + `vite build` green. Scope extended (with
the user) beyond the roadmap bullet to complete **SRS §4.2** (OEM + events export in, not
just PNG/MP4). One new frontend dep: **`mp4-muxer`** (image rebuilt + anon volume dropped).
See [phase-11-plan.md](docs/phase-11-plan.md), Decision 29.
- **11B — export (§4.2 complete).** A capture seam (`frontend/src/export/captureRegistry.ts`:
  each viewport registers `{canvas, renderNow, setExportMode}`; pixel reads are **same-task**
  after an explicit render — no `preserveDrawingBuffer`, Decision 29). **PNG** snapshots
  (global/proximity/composite + caption). **MP4** = deterministic frame-stepped offline render
  (pause → `setCurrentTime` per frame via the existing writer path → WebCodecs H.264 →
  `mp4-muxer`; codec ladder behind `isConfigSupported`; ≤1800 frames; cancel restores clock +
  loops in `finally`; Chromium-first, non-WebCodecs browsers get a disabled tooltip).
  **Events JSON/CSV** client-side from the stream buffer (all five event kinds, pure builders
  in `export/eventsExport.ts`). **CCSDS OEM export**: `GET /scenarios/{id}/export/oem`
  (`io/OemExportService` — ScreeningService pattern, real providers incl. maneuvered/finite
  and measured-clipped-to-span; Orekit `OemWriter`; creation date pinned to the version stamp
  → **byte-identical rerun**, round-tripped through `OemParser` in tests; **audited**
  `EXPORT_OEM`, no version row — the narrow audited-export precedent). `ExportPanel` in the UI.
- **11A — usability (§5.6).** Demo set grown to **five** (new: sensor/link-budget inspection,
  eclipse 6 h, V-bar station 2 km behind — each validated in `SampleScenarioFormationTests`)
  and seeded **per user on first login**: `scenario/UserProvisioner` creates the user row in
  `REQUIRES_NEW` (fixes the readOnly-tx provisioning flush quirk) + publishes one
  `UserProvisionedEvent`; the seeder listens `AFTER_COMMIT`; `seedIfAbsent` is now
  `REQUIRES_NEW` (per-demo isolation + the after-commit-listener commit trap;
  `UserProvisioningSeedTests`). `?` **Help overlay** + one-time first-run hint; tooltip audit
  (76 `title=` added — every interactive control covered).
- **11C — perf + docs.** `lib/perf.ts` + **PerfHud** (⏱ toggle / `?perf=1`): live per-view FPS,
  scrub latency (seek → rendered frame), scenario-load time, §5.1 thresholds highlighted (the
  R7 FPS counter). OpenAPI **info bean** + `@Tag`/`@Operation` on all 31 endpoints (doc-only;
  regenerated client has no type drift). [docs/user-guide.md](docs/user-guide.md) (UC-mapped) +
  a root [README.md](README.md) (new). **Remaining manual:** browser click-throughs (PNG/MP4/
  OEM-in-oidc, fresh-OIDC-user demos) + recording the §5.1 PerfHud readings on reference
  hardware — the evidence table is in the phase plan. **Deferred (Decision 29):** WebM
  fallback; link-budget series export; OEM/AEM *import* (measured slice 3); bundle
  code-splitting; the live cluster install (Phase-10 follow-up).

**Phase 10 complete (10A/10B/10C) — enterprise hardening.** Backend **203 tests green**,
frontend type-check green, Helm chart `helm lint` + `helm template` clean. Activates the
Decision-16 seams **additively** (auth defaults to `stub`, so the prior dev loop + all earlier
tests are unaffected). See [phase-10-plan.md](docs/phase-10-plan.md), Decision 28.
- **10A — real auth + RBAC.** OIDC **resource-server** (stateless bearer JWT) gated by
  `orbit.auth.mode` (`stub` default / `oidc`); `SecurityConfig` two-chain split;
  `JwtAuthenticationConverter` maps Keycloak `realm_access.roles` → `ROLE_*` (principal =
  email); `UserService` syncs `sub`+roles. Ownership already enforced (non-owner → 404);
  capability role rules added. **WebSocket auth** via `?access_token=` (query-param bearer
  resolver → existing handshake interceptor unchanged). Frontend `auth/` module
  (`react-oidc-context` PKCE + Bearer middleware + stream token + `UserChip`). Self-hosted
  **Keycloak** dev overlay (`docker-compose.oidc.yml` + `deploy/keycloak/orbit-realm.json`).
  New deps: `spring-boot-starter-oauth2-resource-server`, `react-oidc-context`+`oidc-client-ts`.
- **10B — governance & trust.** Audit-log + version-history REST (`GET /scenarios/{id}/audit`,
  `/versions`, owner-gated) + `AuditLogPanel`; end-to-end **reproducibility** tests (byte-identical
  `loadAndEncode` across SGP4/numerical/maneuvered/finite-burn + MC same-seed); **§5.2 validation
  suite** (`validation/ValidationConformanceTest`) + [validation-conformance.md](docs/validation-conformance.md)
  (AIAA 2006-6753 inherited from Orekit, R2 — we validate correct integration).
- **10C — deployment.** Prod frontend image (`frontend/Dockerfile.prod`, nginx + runtime `/env.js`);
  **Helm chart** [deploy/helm/orbit](deploy/helm/orbit) (backend/frontend/Keycloak Deployments,
  Postgres StatefulSet, cert-manager **TLS** at split api/web/keycloak Ingresses, k8s **Secrets**,
  external-DB/IdP + GitOps toggles); offline bundle [scripts/bundle.sh](scripts/bundle.sh);
  [deployment.md](docs/deployment.md). **Dev stays on Compose.** **Deferred (Decision 28):** SAML2;
  production Keycloak HA; external golden vectors; a prod Compose path. **Phase 11 next** — polish & ship.

**Phase 9 complete (9A/9B/9C/9D) — advanced maneuvers & analysis.** Backend 188
tests green, frontend type-check + build green. Rides the Phase 4–8 architecture exactly
(sampled-trajectory `analysis/` computers, additive `scenario-relative` fields with `VERSION`
still `"1"`, forward-additive `ScenarioBody` schema **v6**, single audited `ScenarioService`).
**Resolves R16**; introduces the first **seeded RNG** (determinism held — per-sample seed +
ordered collect). See [phase-9-plan.md](docs/phase-9-plan.md), Decision 27. **Post-9 additive
change (2026-07-02):** the maneuver templates + `RendezvousSearchService` now plan against a
**measured-ephemeris chief** (e.g. an imported TELEOS-2 dataset), not just a frozen TLE, via
`scenario/ChiefStateResolver` + the shared `MeasuredEphemerisFactory`; `RendezvousCorrector.correct`
now takes a `Propagator` (so a measured chief works) and fast-refuses ΔV-dominated seeds. Deputies
stay TLE-backed (you don't maneuver the truth). This is the precondition for the deferred
composable-templates work (decisions.md).
- **9A — flight-ready rendezvous (closes R16).** `scenario/RendezvousCorrector` — a differential
  corrector (damped Gauss-Newton/LM + backtracking line search, domain-exit fallback, ΔV/iter caps)
  against the **real** propagators; the two-impulse template defaults `corrected=true`,
  non-convergence falls back to the open-loop seed + an audit warning (not a 422).
  `analysis/RendezvousSearchService` — an arrival × revolution two-body Lambert ΔV grid (serial
  chief-grid + parallel cells). `ManeuverTemplateService.phasing` — a co-elliptic in-track sketch.
  New REST `POST /maneuvers/rendezvous/search`, `/maneuvers/phasing`.
- **9B — CW close-range templates + finite burns.** `prop/CwTargeting` (analytic CW STM blocks
  matching `CwPropagation` + `twoImpulse`, null at the integer-rev singularity) → `ManeuverTemplateService`
  `nmc` (in-track drift-cancel `vy=−2nx`) + `hold` (CW two-impulse to a V-bar/R-bar point). New
  REST `POST /maneuvers/nmc`, `/maneuvers/hold`. **Finite burns (US-MAN-11):** `Impulse`/`Maneuver`
  gain optional `thrustN`/`ispSec` (v6-additive; null → impulsive); `PropagationService.buildManeuvered`
  realises a finite burn as an Orekit `ConstantThrustManeuver` of the Tsiolkovsky duration that
  achieves the ΔV (centred on the epoch → collapses to the impulse as thrust→∞; mass depleted via the
  rocket equation). CW/impulse-equivalent paths treat it as impulsive at the epoch (= midpoint).
  `ManeuverPanel` finite toggle (thrust + Isp). **Glideslope (US-MAN-09):** a constant-closing-rate
  V-bar/R-bar approach discretized into chained CW two-impulse legs (`ManeuverTemplateService.glideslope`)
  + a final park burn; `POST /maneuvers/glideslope`. **Closed-loop station-keeping (US-MAN-10):**
  periodic corrective burns holding a V-bar/R-bar point — genuinely closed-loop (each correction
  rebuilds the deputy's real numerical propagator with the corrections so far, reads back its drifted
  relative state, and re-aims via CW); `POST /maneuvers/station-keep`.
- **9C — Monte Carlo + covariance (UC-6).** `analysis/MonteCarloService` perturbs the deputy ECI
  seed (Gaussian pos/vel) + maneuver ΔV (mag + pointing), propagates each sample in a **bounded**
  `ForkJoinPool` (≤6, caps memory), aggregates the cloud + per-epoch covariance ellipsoids
  (Hipparchus eigendecomposition → canonicalized → three.js quaternion via the extracted
  `FrameService.matrixToQuaternionXyzw`). Deterministic despite RNG: **per-sample**
  `SplittableRandom(mix(seed,i))`, fixed draw order, index-ordered collect, canonicalized
  eigenvectors. Default 100, cap 500 (each sample = a full numerical prop, R18). New REST
  `POST /scenarios/{id}/monte-carlo`; `MonteCarloPanel.tsx` + `proximity/montecarlo.ts` (cloud +
  3σ shells).
- **9D — link budget / SNR (US-EVT-05).** `ScenarioBody` schema **v6** adds an optional
  `LinkBudget` on a `Sensor` (forward-additive; no DB migration). `analysis/LinkBudgetComputer`
  on the sampled trajectory — Friis `SNR(r)=EIRP+G/T−Lfs(r)+228.6−10log10(B)` (~6 dB per
  range-doubling); streamed additively as `linkBudgets` (strided). `SensorPanel` link-budget
  fields + `Timeline` SNR band. **Deferred:** optical detector NEP/QE detail.

`gen:api` regenerated (rendezvous-search / phasing / nmc / hold / glideslope / station-keep /
monte-carlo / set-link-budget / finite-burn fields on the maneuver REST; stream `linkBudgets` stays
WebSocket-only). Backend **188 tests green**. **Deferred (Decision 27):** optical detector NEP/QE
link detail; the finite-burn ΔV-glyph burn-window animation. **Phase 10 next** — enterprise hardening
(roadmap §10).

**Measured-data ingestion — slices 1 & 2 complete (2026-06-22; feature track, not a roadmap
phase).** Real flight telemetry (TELEOS-2 "Whole-Orbit Data" CSVs: measured GNSS ECI
pos/vel + ADCS quaternions) imports as a scenario whose chief is the measured craft
(read-only truth). `io/WodCsvReader` (streaming parse) → immutable content-hashed
`MeasuredDataset` (`measured_dataset` table, V5; samples OUT of the jsonb body) →
`InitialState{kind:"ephemeris", datasetId}` (`ScenarioBody` schema **v4**) → served via an
Orekit tabulated `Ephemeris` in `ScenarioStreamService.prepareEphemerisRole` (the
sampling/stream pipeline is unchanged — "measured" is a per-ROLE source, not a `Fidelity`).
Server-path import (`POST /scenarios/import/measured {path,noradId?}`, path constrained to
`orbit.import.allowed-root`); `update()` merges so editing preserves the ephemeris chief.
**Slice 2 — measured attitude:** the reader also picks up `EST_ATTD_Q1..Q4_8` as a parallel
attitude series (codec v2 behind a backward-compatible sentinel); `AttitudeProfile.mode="measured"`
(set on the chief at import) is **SLERP-streamed through the existing `"fixed"` path** (`bodyAttitude`
+ shared `prop/QuaternionSamples`), so the craft flies its real orientation. The WOD quaternion
convention was **resolved empirically + pinned** (scalar-last Q4=w, body→ECI ⇒ identity reorder;
`prop/MeasuredAttitude` w/ `MeasuredAttitudeTest`; flippable in one place, physical direction confirmed
visually — R20). Frontend: a toggleable **body-axis triad** (`spacecraftModel.setAxesVisible` + a
"Body axes" control) makes orientation legible, legend reads "measured". Backend **126 tests green**,
frontend green; verified end-to-end (570 MB → ~3.2 s; orbit radius holds ~6953 km; chief
`attitude.mode=measured`, the WS frame carries the chief's varying measured `att`). **Gotcha:** keep
`EPHEMERIS_INTERP_POINTS = 2` — higher overshoots between nodes (Runge → 1e11 km orbit). **Slice 3 next:**
measured deputies (real RPO pair — needs a 2nd dataset, R19) / numerical handoff / OEM-AEM readers /
browser upload. See [measured-data-plan.md](docs/measured-data-plan.md), Decision 26.

Per-phase detail lives in `docs/phase-*-plan.md` and the rationale in
[decisions.md](docs/decisions.md); this is just the map of what exists:

- **Phase 1** — dual-container dev env (Spring Boot + Postgres + Flyway + frontend),
  OpenAPI-generated client, Spring Security pipeline (stub).
- **Phase 2** — Orekit 13.1.5 SGP4 core + `FrameService` (ECI/ECEF/geodetic);
  **catalog mode**: one shared SGP4 pass over ~15.5k sats broadcast as gzip CZML on
  `/stream/catalog`; globe consumes it (click-inspect, double-click focus, filters,
  search). [phase-2-plan.md](docs/phase-2-plan.md).
- **Phase 3** — 3A: scenario CRUD + immutable versioning + audit through one
  `ScenarioService` (chief + deputies, frozen-TLE jsonb bodies). 3B: numerical
  propagator (DP8(7), J4+, drag, SRP, third-body) + LVLH/RIC frames + fidelity
  dispatch (backend-only). [phase-3-plan.md](docs/phase-3-plan.md),
  [phase-3b-plan.md](docs/phase-3b-plan.md), Decisions 19–20.
- **Phase 4** — 4A: one authoritative clock (single rAF `clockEngine` writer) +
  per-scenario `/stream/scenario/{id}` CZML stream (precompute-once); the globe
  plays a loaded scenario. 4B: three.js proximity view (chief-LVLH) +
  `scenario-relative` stream; both viewports lockstep on one socket.
  [phase-4-plan.md](docs/phase-4-plan.md).
- **Phase 5** — relative readout (distance/range-rate/R-I-C) + backend closest
  approach + a distance-vs-time graph (`DistanceChart`, Table|Graph tab, no-dep SVG,
  windowed/filterable — Decision 22); impulsive ΔV maneuvers (`ScenarioBody` schema
  v2, audited, numerical re-propagation via Orekit `ImpulseManeuver`, glyphs + Σ|ΔV|
  budget); CW fidelity (`CwPropagation`) + Hohmann/Lambert templates.
  [phase-5-plan.md](docs/phase-5-plan.md).
- **Phase 6** — proximity scene: procedural spacecraft models + GLTF-swap seam +
  fixed-pixel marker LOD (`proximity/spacecraftModel.ts`); derived ram/LVLH
  orientation (`proximity/orientation.ts`, estimated until Phase 7 attitude);
  past/predicted `Line2` trajectory ribbons (`proximity/ribbons.ts`); camera modes
  (`proximity/cameraModes.ts`); Earth backdrop from the additive `chiefRadiusM`
  field (`proximity/earthBackdrop.ts`). [phase-6-plan.md](docs/phase-6-plan.md),
  Decision 23.
- **Phase 7** — sensors & FOV: `Sensor`/`Fov`/`Mount`/`AttitudeProfile` in
  `ScenarioBody` (schema v3); backend-authoritative modeled attitude
  (`FrameService.bodyQuaternionInLvlh` LVLH basis, streamed as a quaternion); translucent
  FOV volumes + sensor-frame camera (`proximity/sensors.ts`, `cameraModes.ts` `sensor`
  mode, `orientation.ts` `bodyOrientationAt`); acquisition/loss-of-sight detection
  (`analysis/SensorEventComputer` — in-FOV + range + Earth occlusion, computed from the
  rendered samples in the LVLH scene, deterministic) streamed in `events` and drawn as
  timeline AOS/LOS windows; `SensorPanel.tsx` (+ type presets) on the audited path.
  [phase-7-plan.md](docs/phase-7-plan.md), Decision 24.
- **Phase 8** — environment & events: Sun/Moon LVLH directions (`FrameService.directionInLvlh`,
  reusing Orekit Sun/Moon) → real `DirectionalLight` + Earth terminator (resolves R17 flat
  lighting); conical eclipse (`analysis/EclipseEventComputer`, geocentric `SampledGeocentricCraft`)
  → timeline bands + craft dimming; intra-scenario conjunctions (`ConjunctionEventComputer`) +
  sun-keep-out/approach-corridor constraints (`ConstraintChecker`, `ScenarioBody` schema v5 +
  `missDistanceThresholdM`, audited) → `conjunctions`/`violations` + timeline marks +
  `EnvironmentPanel.tsx`; catalog conjunction screening (`ScreeningService`,
  `POST /scenarios/{id}/screening`, two-stage shell-prune + fine refine) → sortable table + CSV.
  All `scenario-relative` additions are additive (`VERSION="1"`). [phase-8-plan.md](docs/phase-8-plan.md),
  Decision 25.

Invariants to preserve (see `decisions.md`): one streaming contract, `VERSION="1"`,
additive only (R12); every state frame-tagged via `FrameService` — relative velocity
uses the rotating LVLH transform, never the single-epoch `toRelativeState` (R15);
deterministic propagation, byte-identical reruns (R11); one `currentTime` writer;
ephemeris in stream buffers, not Zustand (Decision 5); all scenario edits (incl.
maneuvers) go through the single audited `ScenarioService` path (Decision 16).

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
- `docker compose up -d --build` — backend + Postgres + frontend (build on first run). Auth is
  **stub** by default (a dev user; no IdP).
- **OIDC dev** (Phase 10): `docker compose -f docker-compose.yml -f docker-compose.oidc.yml up --build`
  adds a Keycloak IdP and flips auth to `oidc` (Keycloak on :8082; sign in as `maya/maya`,
  `frank/frank`, `gita/gita`). See [deployment.md](docs/deployment.md) for the issuer-consistency note.
- `docker compose down` — stop services (preserves db volume).
- `docker compose down -v` — stop and wipe db.
- Backend: http://localhost:8081  ·  Frontend: http://localhost:5174

### Production / on-prem (Kubernetes, Phase 10)
- Deploy artifact is the Helm chart [deploy/helm/orbit](deploy/helm/orbit) (backend + frontend +
  Keycloak + Postgres, cert-manager TLS at the ingress, k8s Secrets). Prereqs: an ingress controller
  + cert-manager. `helm install orbit deploy/helm/orbit -n orbit --create-namespace -f my-values.yaml`.
- Offline air-gapped bundle: `scripts/bundle.sh <version>` (`docker save` images + `helm package`).
- Full runbook + toggles (external DB / external IdP / GitOps secrets): [deployment.md](docs/deployment.md).
  (host ports 8080 / 5173 are taken by other services on this shared box).
- The backend mounts `/mnt/disk_large/shared_folder → /shared_folder:ro` (measured-data
  import root, `orbit.import.allowed-root`). Import a measured CSV:
  `curl -X POST localhost:8081/scenarios/import/measured -H 'Content-Type: application/json'
  -d '{"path":"/shared_folder/<…>.csv"}'` (see [measured-data-plan.md](docs/measured-data-plan.md)).
  `_teleos_samples/` holds throwaway sample CSVs (gitignored).

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
