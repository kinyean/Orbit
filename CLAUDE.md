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
`docs/dev-guide.md` (not embedded here) is the **HANDOVER GUIDE** — module maps,
runtime pipeline narratives, change recipes, gotchas. `docs/build-history.md` is
the **frozen build narrative** (the merged former phase plans).
The previous public-tracker product plan was retired in the 2026-05-28 SRS
pivot — see `decisions.md` "Superseded" section for the carried-over
rationale.

## Project status

**All eleven roadmap phases are complete** (Phase 11 shipped 2026-07-06). Backend
**217 tests green**; frontend type-check + `vite build` green. The per-phase build
narrative is curated in [build-history.md](docs/build-history.md); the WHY per phase
is `decisions.md` (Decisions 19–29); done-state is `acceptance-criteria.md`. Current
shapes: `ScenarioBody` schema **v6**; streaming contract `VERSION = "1"` (all
additions ever made were additive). One-line map of what exists:

- **Phase 1** — dual-container dev env, Flyway schema (owner/roles from V1), OpenAPI
  client, Spring Security stub.
- **Phase 2** — Orekit SGP4 core + `FrameService` v1; the shared gzip-CZML catalog
  stream (~15.5k sats) + globe browsing (Decision 18 camera).
- **Phase 3** — scenario CRUD + immutable versioning + audit through the single
  `ScenarioService` (frozen-TLE jsonb bodies, Decision 19); numerical propagator
  (DP8(7), J4+, drag, SRP, third-body) + LVLH/RIC frames (Decision 20).
- **Phase 4** — one authoritative clock (single rAF `clockEngine` writer) +
  per-scenario precompute-once streams (`scenario-czml` + `scenario-relative`);
  three.js proximity view in lockstep (Decisions 11, 21).
- **Phase 5** — relative readout + backend closest approach + distance chart
  (Decision 22); impulsive ΔV maneuvers (schema v2, audited, numerical
  re-propagation); CW fidelity + Hohmann/Lambert templates.
- **Phase 6** — proximity scene: procedural models + GLTF-swap seam, windowed
  trajectory ribbons, camera modes, Earth backdrop from `chiefRadiusM`
  (Decision 23).
- **Phase 7** — sensors as scenario objects (schema v3) + backend-authoritative
  modeled attitude (streamed quaternion) + FOV volumes + AOS/LOS events computed
  from the sampled trajectory (Decision 24).
- **Phase 8** — Sun/Moon LVLH directions → real lighting + terminator; eclipse,
  intra-scenario conjunctions, sun-keep-out/approach-corridor constraints
  (schema v5); catalog conjunction screening (Decision 25).
- **Phase 9** — rendezvous differential corrector (resolves R16) + arrival×rev ΔV
  search + phasing/NMC/hold/glideslope/station-keep templates + finite burns +
  Monte Carlo (the first seeded RNG — determinism held, R21) + link budget
  (schema v6, Decision 27). Post-9 additive: templates plan against a
  **measured-ephemeris chief** (`scenario/ChiefStateResolver`).
- **Phase 10** — OIDC resource-server + RBAC behind `orbit.auth.mode` (**stub**
  default keeps dev IdP-free), audit-log/version-history UI, §5.2 validation suite,
  Helm chart + offline bundle (Decision 28).
- **Phase 11** — exports (PNG / MP4 / CCSDS OEM / events JSON+CSV — SRS §4.2
  complete), five demo scenarios seeded per user on first login, `?` help overlay +
  tooltip audit, PerfHud (⏱ / `?perf=1`), OpenAPI polish, user guide + README
  (Decision 29). Plan stays live: [phase-11-plan.md](docs/phase-11-plan.md).
- **Measured-data track (slices 1–2)** — TELEOS-2 WOD CSV imports as a scenario
  whose chief is the measured craft flying its real attitude (schema v4,
  Decision 26). **Gotcha:** keep `EPHEMERIS_INTERP_POINTS = 2` (Runge overshoot,
  R19). Slice 3 (measured deputies / OEM-AEM readers / browser upload) is planned:
  [measured-data-plan.md](docs/measured-data-plan.md).

**Open items:** §5.1 PerfHud readings are **recorded** (2026-07-07, RTX 4090; table in
phase-11-plan.md) — passes at typical loads, with two documented misses: the full ~15.5k
catalog overlay drops the globe to ~10 fps (R7 — CZML-Entity CPU path; LOD/`PointPrimitive`
mitigation pre-scoped) and a 10-craft proximity scene sits at ~30 fps (the SRS ceiling; 2–4
craft hold 60). Still open: the Phase-11 manual browser click-throughs; the live k8s cluster
install (Phase-10 follow-up); measured-data slice 3; everything deliberately deferred is in
the registry at the end of `decisions.md`.

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
  adds a Keycloak IdP + an nginx TLS front and flips auth to `oidc` — open
  **https://<host>:8443/** (single origin, self-signed cert; sign in as `maya/maya`,
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
