# Orbit-project — Claude context

An Inter-Satellite Remote Proximity Operations (RPO) visualization and
simulation platform.

@./docs/Software Requirements Specification.md
@./docs/architecture-and-roadmap.md
@./docs/decisions.md

The SRS is the authoritative WHAT. `architecture-and-roadmap.md` is the
HOW + WHEN. `decisions.md` is the WHY. The previous public-tracker product
plan was retired in the 2026-05-28 SRS pivot — see `decisions.md` "Superseded"
section for the carried-over rationale.

## Current phase
Phase 0 (foundation scaffold) complete: React + Vite + Cesium globe runs
locally with day/night terminator. **Phase 1 next:** stand up the Java/Spring
backend + PostgreSQL + Docker Compose dev env; reshape the frontend shell from
catalog-browser to scenario-shell.

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
- Scenario data model is **chief + deputies**, never a flat catalog. See
  Decision 13.
- Build professional-grade from day one — auth pipeline real (stub
  initially), `scenario.owner` exists from the first migration, scenario
  mutations go through one service layer, propagation is deterministic
  (seeded + pinned). See Decision 15.
- TypeScript strict mode on. Never disable.
- Per-slice Zustand subscriptions.

## Conventions
- Ask before adding dependencies.
- Commit after each working feature (the scaffold + the SRS-pivot docs is one
  such — still uncommitted).
- Tag frame in code wherever state vectors live (names, types, comments):
  `ECI` / `ECEF` / `LVLH` / `RIC` / `body`.

## Build commands
### Frontend (`satellite-tracker/`)
- `npm run dev` — Vite dev server on port 5173.
- `npm run type-check` — `tsc --noEmit`.

### Backend (TBD — Phase 1)
- Spring Boot + Maven/Gradle; commands land here when the module is created.

### Stack (TBD — Phase 1)
- `docker compose up` — full local dev environment (frontend + backend + db).
