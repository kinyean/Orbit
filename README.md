# Orbit — Inter-Satellite RPO Visualization & Simulation

A professional-grade platform for planning and analyzing **Rendezvous &
Proximity Operations (RPO)**: compose a scenario from the live satellite
catalog, propagate it at selectable fidelity (SGP4 / high-fidelity numerical /
Clohessy-Wiltshire), and study the relative motion in a dual-viewport client —
a Cesium globe for the world view and a three.js chief-centered LVLH scene for
the close range.

**For** mission planners (fast scenario sketching, ΔV budgets, maneuver
templates), flight-dynamics engineers (validated numerical propagation,
Monte Carlo dispersion, reproducibility + audit, CCSDS OEM export), GN&C
analysts (sensors, FOV volumes, attitude, link budgets), and operators
(smooth 0.01×–10 000× playback, annotated timeline, PNG/MP4 export).

## Feature highlights

- **Live catalog** — ~15,000 active satellites streamed as one shared feed;
  click-to-inspect, search, constellation filters, ±12 h time-travel.
- **Scenarios** — chief + deputies, immutable versioning, full audit trail,
  per-user demo set seeded on first login.
- **Propagation** — Orekit: SGP4, DP8(7) numerical (16×16 gravity, NRLMSISE-00
  drag, SRP, third-body), CW close-range; deterministic, byte-identical reruns.
- **Maneuvers** — impulsive + finite burns; Hohmann, differential-corrected
  rendezvous (+ ΔV search), phasing, NMC, V-bar/R-bar hold, glideslope,
  closed-loop station-keeping.
- **Sensors & environment** — body-fixed FOV volumes on modeled/measured
  attitude, AOS/LOS events, link-budget SNR, eclipse, conjunctions (+ full
  catalog screening), sun-keep-out / approach-corridor constraints.
- **Analysis** — Monte Carlo dispersion with covariance ellipsoids (seeded,
  reproducible).
- **Measured data** — import real flight telemetry (WOD CSV: GNSS ECI +
  ADCS quaternions) as a read-only measured chief.
- **Export** — PNG snapshots, offline-rendered MP4 sequences, events JSON/CSV,
  CCSDS OEM ephemerides.
- **Enterprise** — OIDC (Keycloak) + RBAC, audit UI, Helm chart with
  cert-manager TLS, offline air-gapped bundle.

## Quick start (dev)

Prereqs: Docker + Compose.

```bash
docker compose up -d --build
```

- Frontend: http://localhost:5174 · Backend: http://localhost:8081
  (Swagger UI at `/swagger-ui.html`)
- Auth defaults to **stub** (a fixed dev user). For real OIDC sign-in:

```bash
docker compose -f docker-compose.yml -f docker-compose.oidc.yml up --build
# open https://<host>:8443/ (self-signed cert; one origin for app + Keycloak)
# sign in as maya/maya, frank/frank, or gita/gita
```

First load: open **Scenarios** → load a **Demo** → press play. The **?**
button has the tour.

## Development

- Backend (`backend/`, Java 21 / Spring Boot 3.5 / Orekit 13):
  `./gradlew test` (needs Docker for Testcontainers), `./gradlew build -x test`.
- Frontend (`frontend/`, React + TS strict + Vite):
  `npm run dev` · `npm run type-check` · `npm run gen:api` (regenerate the
  typed client from the running backend). Dependency changes need an image
  rebuild + anon-volume drop — see [frontend/README.md](frontend/README.md).

## Production / on-prem

Kubernetes via the Helm chart at [deploy/helm/orbit](deploy/helm/orbit)
(backend, frontend, Keycloak, Postgres, cert-manager TLS, external-DB/IdP
toggles); offline bundle via [scripts/bundle.sh](scripts/bundle.sh). Runbook:
[docs/deployment.md](docs/deployment.md).

## Documentation map

| Doc | What |
|---|---|
| [docs/user-guide.md](docs/user-guide.md) | How to use the app, end to end |
| [docs/dev-guide.md](docs/dev-guide.md) | Developer handover guide — module maps, pipelines, recipes, gotchas |
| [docs/build-history.md](docs/build-history.md) | How it was built, phase by phase (frozen history) |
| [docs/Software Requirements Specification.md](docs/Software%20Requirements%20Specification.md) | The authoritative WHAT |
| [docs/architecture-and-roadmap.md](docs/architecture-and-roadmap.md) | HOW it's built + phase roadmap |
| [docs/decisions.md](docs/decisions.md) | WHY — the decision log |
| [docs/glossary.md](docs/glossary.md) | Domain vocabulary |
| [docs/streaming-contract.md](docs/streaming-contract.md) | The backend↔frontend streaming seam |
| [docs/validation-conformance.md](docs/validation-conformance.md) | Propagation validation posture (§5.2) |
| [docs/deployment.md](docs/deployment.md) | Helm/K8s + air-gapped install |

## Architecture (one paragraph)

Four components: a **Java/Spring/Orekit backend** (all propagation + analysis;
the frontend never propagates), a **React dual-viewport client**
(CesiumJS globe + three.js proximity view sharing one clock), **PostgreSQL**
(versioned jsonb scenario bodies + audit log), and external interfaces
(TLE catalogs, CCSDS files, measured telemetry). They meet at one streaming
contract: REST (OpenAPI 3) for CRUD/analyses, WebSocket for the shared catalog
feed and per-scenario CZML + relative-state streams.

## Status

Phases 1–11 of the [roadmap](docs/architecture-and-roadmap.md) are complete —
through enterprise hardening (OIDC/RBAC, Helm/TLS) and polish & ship (samples,
help, §5.1 performance instrumentation, PNG/MP4/events/OEM export, this
documentation).

Built on [Orekit](https://www.orekit.org/) (propagation),
[CesiumJS](https://cesium.com/platform/cesiumjs/) (globe) and
[three.js](https://threejs.org/) (proximity scene).
