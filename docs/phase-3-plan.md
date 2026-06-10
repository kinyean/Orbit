# Phase 3 plan — from viewer to tool (scenarios + high-fidelity propagation)

> Planning artifact, written ahead of implementation (same workflow as
> [phase-2-plan.md](./phase-2-plan.md)). Do not start coding from this until
> picked up as active work.

## Context

Phases 1–2 produced a *viewer*: ~15.5k real satellites stream from the backend,
render on the globe, and any one can be inspected and focused. But everything so
far is **read-only and ephemeral** — nothing persists, and there is no analysis,
only observation. **Phase 3 turns the viewer into the tool the SRS actually
specifies** (an RPO analysis platform):

- It makes the **core user workflow** real (UC-1): pick a chief, add deputies,
  save a *scenario* — the central artifact of the whole product. Today the
  "Set as chief / Add as deputy" buttons are dead stubs and the scenario panel
  says "No saved scenarios" forever.
- It crosses the **analysis-fidelity bar**: SGP4 (±1–3 km) is fine for "where's
  that dot," useless for proximity ops. Phase 3 adds the **high-fidelity
  numerical propagator** Frank must trust, and the **LVLH/RIC frames** that are
  the native language of relative motion.
- It **activates the "professional from day one" seams** (Decision 16):
  owner-tagged scenarios, immutable versioning, an audit log, deterministic
  propagation — empty tables today — so Phase 10 stays additive.
- It is the **load-bearing dependency** for Phases 4–9 (proximity view,
  maneuvers, sensors, conjunctions all operate *on a scenario*).

**Groundwork already in place** (verified): the DB schema (`V1__init.sql`), the
JPA starter, the dev-user security principal, the OpenAPI client + `gen:api`
flow, Orekit 13.1.5, the thin `prop/` wrappers, and the frontend `composer`
store slice. Phase 3 is mostly **integration + propagation depth**, not
green-field plumbing.

**Decisions taken** (with the user, during planning):
- **Slice into 3A then 3B.** 3A = scenario composition end-to-end on the
  existing SGP4 engine (the shippable Maya-facing slice). 3B = high-fidelity
  numerical propagator + relative frames (the Frank-facing physics depth).
- **TLE-from-catalog only** for initial states in Phase 3. CCSDS OEM import and
  Keplerian-element forms are deferred.
- **Out of scope (Phase 4):** the per-user *scenario playback stream* and the
  proximity view. Loading a scenario in Phase 3 populates the composer/views
  statically; live streaming comes in Phase 4.

---

## Phase 3A — Scenario composition end-to-end (SGP4)  ← BUILD FIRST

End state: click a catalog satellite → "Set as chief" / "Add as deputy" →
"Save" → it appears in the scenario list, survives a page reload, can be
loaded/renamed/saved-as-new-version/deleted, and every mutation is owner-tagged,
versioned, and audited.

### Backend — new package `space.orbit.backend.scenario`

Mirror the existing `api/HealthController` controller-with-record-DTOs +
springdoc pattern. Map entities **exactly** to `V1__init.sql` columns
(`ddl-auto=validate` fails boot on any mismatch).

1. **JPA entities** (one per existing table):
   - `User`, `Scenario`, `ScenarioVersion`, `AuditLog`.
   - UUID PKs: **service-generated** (`UUID.randomUUID()`), NOT `@GeneratedValue`
     (we need ids before insert for the circular `scenarios.latest_version_id` ↔
     `scenario_versions.id` FK; DB `gen_random_uuid()` stays as a fallback).
   - DB-defaulted columns (`id` write-once, `created_at`, `updated_at`,
     `timestamp`) → `insertable=false, updatable=false`; `TIMESTAMPTZ` →
     `OffsetDateTime`.
   - `users.roles` `TEXT[]` → `List<String>` via
     `@JdbcTypeCode(SqlTypes.ARRAY)` `columnDefinition="text[]"`.
   - `scenario_versions.body` `jsonb` → **`String`** via
     `@JdbcTypeCode(SqlTypes.JSON)` (keeps the evolving body schema OUT of the
     validated entity; service owns (de)serialization).
   - Use **plain UUID FK fields, not `@ManyToOne`** (we run `open-in-view=false`;
     avoid lazy-association traps; resolve ids explicitly).

2. **Repositories** (`JpaRepository<T, UUID>`):
   - `UserRepository.findByEmail`.
   - `ScenarioRepository.findByOwnerIdOrderByCreatedAtDesc`,
     `findByOwnerIdAndName` (active-only).
   - `ScenarioVersionRepository.findByScenarioIdAndVersionNo`,
     `findByScenarioIdOrderByVersionNoAsc`, `findMaxVersionNo` (`max+1` =
     next monotonic version).
   - `AuditLogRepository` (writes only for 3A).

3. **Scenario JSON body schema v1** (immutable `ScenarioBody` record →
   `jsonb`). Stores both the **NORAD id** (the composer's join key) AND a
   **TLE snapshot** (the two line strings + epoch) captured at compose time, so
   a saved scenario is reproducible and does NOT drift when the 6-h catalog
   refresh lands (Frank's reproducibility requirement, SRS §5.4.1). Shape:
   `{ schemaVersion:1, fidelity:"sgp4", timeRange:{start,end},
   chief:{role,noradId,name,initialState:{kind:"tle",tle:{line1,line2,epoch}}},
   deputies:[…] }`. `fidelity` defaults to `"sgp4"` (only honored value in 3A).

4. **`ScenarioService`** — the single mutation path (Decision 16). All of
   create/get/list/update/delete are `@Transactional`; every mutating call
   writes the version row + exactly one `audit_log` row in the same transaction.
   - `UserService.currentUser()` resolves the principal email
     (`SecurityContextHolder…getName()` → `"dev@orbit.local"`) to a `User` row →
     `owner_id`/`author_id`. Include a `getOrCreateByEmail` fallback so a real
     OIDC principal in Phase 10 self-provisions (additive).
   - `create`: build body (snapshot each NORAD id via the catalog — see #7),
     insert scenario → insert v1 version → set `latest_version_id` → audit
     CREATE. Translate the `UNIQUE(owner_id,name)` `DataIntegrityViolationException`
     → 409.
   - `update`: insert a NEW version (`versionNo = max+1`, immutable history),
     bump `latest_version_id`, audit UPDATE.
   - `delete`: **soft-delete** (set `deleted_at`), audit DELETE; `list`/`get`
     filter `deleted_at IS NULL`. (Hard delete would cascade-wipe versions +
     orphan the audit trail.)
   - Validation rules in-service (deputies-require-chief, `end>start`, NORAD
     resolvable) → 422.

5. **`ScenarioController`** `@RequestMapping("/scenarios")` + a
   `@RestControllerAdvice` for 404/409/422. Endpoints (US-SCN-03):
   `POST /scenarios`, `GET /scenarios`, `GET /scenarios/{id}`,
   `GET /scenarios/{id}/versions/{v}`, `PUT /scenarios/{id}`,
   `DELETE /scenarios/{id}`. Request DTOs take **only NORAD ids**
   (`RoleRef(int noradId)`) — backend resolves names + TLE snapshots. Response
   DTOs return the typed `ScenarioBody` so `gen:api` gives the frontend a usable
   schema. Springdoc auto-exposes them at `/v3/api-docs`.

6. **Flyway migrations**:
   - `V2__seed_dev_user.sql` — **required** (fixes the `owner_id` gotcha: no
     `users` row exists yet, but `scenarios.owner_id` is a NOT NULL FK). Insert
     `DEV_USER_ID` / `dev@orbit.local` (constants from
     `DevUserAuthenticationFilter`) with `ON CONFLICT (id) DO NOTHING`.
   - `V3__scenario_soft_delete.sql` — `ALTER TABLE scenarios ADD COLUMN
     deleted_at TIMESTAMPTZ;` + partial index
     `(owner_id) WHERE deleted_at IS NULL` (also covers the owner-scoped list).

7. **`CatalogService` resolver** (one additive change): build a
   `Map<Integer,TrackedSatellite> byNorad` alongside the existing `tracked` list
   in `loadFromGpJson`, and expose `snapshot(noradId) → TleSnapshot` using
   `TrackedSatellite.propagator().getTLE().getLine1()/getLine2()`. A composer id
   absent from the in-memory catalog → clear 422 (not NPE).

### Frontend

8. Add a **scenario store slice** to `store/useStore.ts`: `scenarios` list,
   `loadScenarios()`, `saveScenario()`/`updateScenario()`, `loadScenario(id)`
   (populates composer), `deleteScenario(id)` — calling the generated client.
   Reuse the existing per-slice pattern and the `composer` slice (numeric
   `chiefId`/`deputyIds`).
9. **Wire the InfoPanel buttons** (`components/InfoPanel.tsx`): remove
   `disabled`; "Set as chief" → `setChief(selected.noradId)` (confirm-replace if
   a chief exists); "Add as deputy" → `addDeputy(...)`, disabled w/ tooltip when
   no chief; show "Remove from scenario" when already a member.
10. **`scenario/ScenarioPanel.tsx`**: replace the "No saved scenarios" stub with
    a real list (load on mount), a Save button (enabled on `composer.isDirty`,
    prompts for a name on first save → `POST`; later saves → `PUT`), load-on-click,
    delete, and version count. Show chief/deputy **names** (from `catalogIndex`),
    not bare ids.
11. Regenerate the client: `npm run gen:api` (after the backend endpoints exist)
    → `src/api/schema.d.ts`; call via the existing `openapi-fetch` `api` client
    (`api.POST('/scenarios', …)`).

### Tests (follow existing patterns)
- `@DataJpaTest` repo/migration tests (needs a real Postgres — `TEXT[]`,
  `jsonb`, `gen_random_uuid()` are PG-specific): migrations apply + dev user
  seeded; `UNIQUE(owner_id,name)` throws; `findMaxVersionNo` increments; JSONB +
  `TEXT[]` round-trip.
- `ScenarioService` slice test: versioning monotonic + `latest_version_id`
  tracks newest + old versions immutable; audit row per mutation (zero on
  rollback); TLE snapshot frozen against catalog change.
- `@WebMvcTest(ScenarioController.class)` (mirror `HealthControllerTests`):
  status codes + JSON shapes + 404/409/422 paths.

---

## Phase 3B — High-fidelity numerical propagation + relative frames  ← AFTER 3A

12. **Numerical propagator** in `prop/` (extend, don't expose Orekit surface):
    Orekit `NumericalPropagator` with a `DormandPrince853Integrator` (DP8(7)),
    `HolmesFeatherstoneAttractionModel` gravity (degree/order ≥ J4, configurable),
    `DragForce` + NRLMSISE-00 atmosphere, `SolarRadiationPressure`, and
    Sun/Moon `ThirdBodyAttraction`. **Deterministic**: pinned settings, no
    process-env reads (SRS §5.4.1). Returns frame-tagged `StateVector` (ECI).
    *Verify the bundled `orekit-data` includes CSSI space-weather for NRLMSISE-00;
    add it if missing.*
13. **Fidelity dispatch**: a small `PropagationService` that, given a scenario
    body's `fidelity`, selects SGP4 (existing) vs numerical. (`cw` stays in the
    enum but the Clohessy-Wiltshire propagator lands in **Phase 5** with relative
    motion — flag this; 3B implements `sgp4` + `numerical`.)
14. **`FrameService` v2**: add `lvlh(chief)` and `ric(chief)` via Orekit
    `LOFType` (LVLH / QSW), `body(attitude)` from a quaternion, and
    `toRelativeState(deputy, chief)` returning an LVLH-tagged `StateVector`.
    Add `LVLH`/`RIC`/`BODY` to the frame tags.
15. **Tests**: numerical propagator invariants (SMA/energy stable over an orbit;
    determinism — same inputs → byte-identical output) and frame round-trips
    (US-FRAME-02: a deputy on a circular NMC ellipse traces a closed loop in
    LVLH).

---

## New dependencies (need approval — CLAUDE.md "ask before adding")
- **`org.springframework.boot:spring-boot-starter-validation`** (3A) — `@Valid`
  request DTOs + OpenAPI-visible constraints. (Fallback: all validation in the
  service.)
- **Testcontainers (Postgres)** (3A) — for `@DataJpaTest` against real Postgres,
  since the schema uses PG-only types. (Fallback: skip `@DataJpaTest`, rely on
  service slice tests + a manual integration check against the Compose Postgres.)

## Deferred (explicitly NOT Phase 3)
- CCSDS OEM import + Keplerian-element forms (later phase).
- Per-user scenario playback **stream** + proximity view (Phase 4).
- Clohessy-Wiltshire propagator (Phase 5).
- Real OIDC/SAML + RBAC enforcement; full §5.2 AIAA golden-vector conformance
  (Phase 10) — 3B uses invariant/determinism tests, matching the current suite.

## Critical files
- `backend/.../scenario/` (NEW: entities, repos, `ScenarioService`, `UserService`,
  `ScenarioBody`).
- `backend/.../api/ScenarioController.java` + DTOs + `@RestControllerAdvice`
  (NEW; mirror `api/HealthController.java`).
- `backend/src/main/resources/db/migration/V2__seed_dev_user.sql`,
  `V3__scenario_soft_delete.sql` (NEW).
- `backend/.../catalog/CatalogService.java` (add NORAD resolver + `snapshot`).
- `backend/.../prop/` (3B: numerical propagator, `PropagationService`,
  `FrameService` v2) + `backend/.../prop/StateVector.java` (new frame tags).
- `backend/build.gradle.kts` (validation; Testcontainers).
- `frontend/src/store/useStore.ts` (scenario slice), `components/InfoPanel.tsx`
  (wire buttons), `scenario/ScenarioPanel.tsx` (list/save/load/delete),
  `src/api/schema.d.ts` (regenerated).
- Docs at close-out: `acceptance-criteria.md` (Phase 3 ticks),
  `architecture-and-roadmap.md` (Phase 3 status), `CLAUDE.md` (phase),
  `decisions.md` (scenario body schema + fidelity-dispatch decision).

## Verification (end-to-end)
- `./gradlew test` — all backend tests green (no DB needed for the slice/WebMvc
  tests; `@DataJpaTest` needs Compose Postgres or Testcontainers).
- `docker compose up -d --build`, then in the browser: click ISS → "Set as
  chief" → click another sat → "Add as deputy" → "Save" (name it) → it appears
  in the scenario panel. **Reload the page** → load the scenario back → composer
  repopulates. Rename + save → a v2 appears. Delete → it leaves the list.
- `psql`: confirm rows in `scenarios`, `scenario_versions` (v1, v2, immutable),
  `audit_log` (CREATE/UPDATE/DELETE with the dev `actor_id`), and `owner_id` set.
- `npm run type-check` passes; `npm run gen:api` produced the scenario types.
- 3B: a unit test propagates ISS 24 h numerically and asserts bounded SMA drift
  + byte-identical reruns; an LVLH round-trip test passes.
