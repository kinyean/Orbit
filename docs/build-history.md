# Build history

How the system was built, phase by phase. This is a **curated merge** of the ten
per-phase planning documents (`phase-2-plan.md` … `phase-10-plan.md`) that guided
Phases 2–10; the originals are recoverable from git history (e.g.
`git log --follow -- docs/phase-4-plan.md`). It is **frozen history — do not
update it** as the system evolves.

What lives where:
- **WHY** each choice was made → [decisions.md](./decisions.md) (Decisions 19–28
  are the per-phase entries; each section below names its decision).
- **What "done" meant**, checked → [acceptance-criteria.md](./acceptance-criteria.md).
- **WHAT was required** → the [SRS](./Software%20Requirements%20Specification.md);
  **WHEN** → [architecture-and-roadmap.md](./architecture-and-roadmap.md).
- Two planning docs are **still live** (not merged here):
  [phase-11-plan.md](./phase-11-plan.md) (holds the §5.1 performance evidence table —
  now recorded, with two documented misses — + the remaining manual click-throughs) and
  [measured-data-plan.md](./measured-data-plan.md) (slice 3 is unbuilt).

Every phase followed the same workflow: a planning doc written **before**
implementation (design + execution sequence + verification gate), then executed,
then closed out by updating the living docs (decisions, acceptance-criteria,
roadmap, risks, CLAUDE.md). Phases were sliced (3A/3B, 4A/4B, …) so each slice
ended with something demonstrable.

Contents: [0–1](#phases-01--foundation-and-dev-environment) ·
[2](#phase-2--orekit-sgp4-core-and-the-shared-catalog-stream) ·
[3](#phase-3--scenario-crud-3a-and-the-numerical-propagator-3b) ·
[4](#phase-4--shared-clock-per-scenario-streaming-proximity-view) ·
[5](#phase-5--relative-readout-impulsive-maneuvers-cw-and-templates) ·
[6](#phase-6--proximity-scene-models-ribbons-cameras-earth) ·
[7](#phase-7--sensors-modeled-attitude-acquisition-events) ·
[8](#phase-8--environment-and-events) ·
[9](#phase-9--advanced-maneuvers-and-analysis) ·
[10](#phase-10--enterprise-hardening)

---

## Phases 0–1 — foundation and dev environment

*(No standalone plan files — these predate the plan-per-phase workflow.)*

Phase 0 (pre-pivot) produced the React + Vite + Cesium scaffold: a globe with
day/night lighting, the catalog filter/stats UI shell, fed by a client-side
CelesTrak fetch. The 2026-05-28 SRS pivot (see the decisions.md preamble) turned
the project from a public satellite tracker into the RPO platform; the scaffold
carried over as the global view.

Phase 1 stood up the professional skeleton before any features: the Spring Boot
backend + PostgreSQL + Flyway (`users`/`scenarios`/`scenario_versions`/`audit_log`
from `V1__init.sql`, with `owner_id` and `roles` present from day one — the
Decision-16 RBAC seam), Docker Compose for the whole stack, the Spring Security
pipeline (permit-all stub with a fixed dev principal), the OpenAPI-generated
frontend client, and one `GET /health` round-trip proving the wiring. The catalog
UI kept working against CelesTrak until Phase 2 repointed it.

## Phase 2 — Orekit SGP4 core and the shared catalog stream

**Delivered:** Orekit 13.1.5 wired with its data bundle; SGP4 propagation behind a
thin `prop/` wrapper (`SatellitePropagator`, frame-tagged `StateVector`);
`FrameService` v1 (ECI/ECEF/geodetic); the versioned streaming contract
([streaming-contract.md](./streaming-contract.md)); the catalog service (periodic
SGP4 pass over ~15.5k satellites → one broadcast CZML feed on `/stream/catalog`);
the globe repointed from the client-side CelesTrak fetch to the backend stream,
with click-inspect, search, filters, and the twist-free double-click focus
(Decision 18). `lib/celestrak.ts` / `lib/propagator.ts` (and satellite.js) were
deleted — the frontend never propagates again (Decision 9).

**How:** a tracer-bullet sequence — Orekit dep + data, frame service, one-satellite
SGP4 reference test, contract spec, catalog service, WebSocket broadcast, frontend
client — ordered so the smallest end-to-end slice (one satellite streamed to the
globe) landed mid-phase, then scaled to the full catalog with a perf baseline as
the explicit final step.

**Deviations & lessons:**
- **CelesTrak is firewall-blocked on the dev box** — the catalog loads a bundled
  offline OMM seed and best-effort refreshes from a reachable GitHub mirror.
- **The uncompressed 7.36 MB CZML frame could not drain to a remote browser**
  within the WebSocket send-time limit (fine over loopback, reset over the
  network) — the fix (gzip binary frames, 6.4×) is now a contract property. The
  measured cadence/size numbers live in the decisions.md deferred item "Catalog
  refresh cadence + sample density".
- ~15.5k CZML entities rendered smoothly without the R7
  `PointPrimitiveCollection` fallback — confirmed by eye then, instrumented
  properly in Phase 11 (PerfHud).

**Pointers:** Decisions 7, 10, 12, 13, 18 · acceptance-criteria §Phase 2.

## Phase 3 — scenario CRUD (3A) and the numerical propagator (3B)

**Delivered (3A, the Maya-facing slice):** the scenario as a real persistent
artifact — `scenario/` package (JPA entities, repositories, `ScenarioBody` schema
v1, `ScenarioService` as the single audited mutation path), REST CRUD with
immutable versioning and soft delete, migrations `V2__seed_dev_user` +
`V3__scenario_soft_delete`, the composer wired end-to-end (catalog click → set
chief / add deputy → save → reload). Each role freezes a **TLE snapshot** at
compose time so saved scenarios never drift with the catalog refresh.

**Delivered (3B, the Frank-facing depth, backend-only):** the high-fidelity
numerical propagator (`NumericalPropagation` — DP8(7), 16×16 gravity, NRLMSISE-00
drag, SRP, Sun/Moon third-body) with **pinned settings in code**
(`PropagationSettings.DEFAULT`); the `Fidelity` enum + `PropagationService`
dispatch seam; `FrameService` v2 (LVLH/RIC via `LOFType.QSW`, a minimal body
frame, `toRelativeState`). Nothing user-visible — proven by tests, consumed by
Phase 4.

**Deviations & lessons:**
- UUID PKs are **service-generated** (entities implement `Persistable`) because
  the circular `scenarios.latest_version_id` ↔ `scenario_versions.id` FK needs
  ids before insert (Decision 19).
- `V2__seed_dev_user` exists because `scenarios.owner_id` is NOT NULL from V1 but
  no user row existed yet in stub mode.
- The planned closed-loop LVLH test was **rotation-invariant** — it could not
  catch a flipped R/I/C convention. The shipped test pins orientation by
  **signed axis** instead (Decision 20, R15). This became the house pattern for
  every later frame/quaternion convention (Phases 7, 9, measured attitude).
- New deps taken with the user: `spring-boot-starter-validation`, Testcontainers
  (the schema uses PG-only `TEXT[]`/`jsonb`/`gen_random_uuid()` — H2 can't
  emulate it, so `./gradlew test` needs Docker from here on), and the
  springdoc 2.6→2.8.9 bump (2.6 broke `/v3/api-docs` under Boot 3.5).

**Pointers:** Decisions 16, 19, 20 · acceptance-criteria §Phase 3.

## Phase 4 — shared clock, per-scenario streaming, proximity view

**Delivered (4A):** one authoritative simulation clock — a clock slice plus
`store/clockEngine.ts`, a single rAF loop that is the **only writer** of
`currentTime`; Cesium's autonomous clock severed and driven from the store via a
`preRender` copy; full transport controls (play/pause/step/reset/reverse/log-rate
0.01×–10000×) + the Timeline scrub bar; the per-scenario WebSocket
`/stream/scenario/{id}` (owner-gated, gzip, close codes 4400/4404/4422) built on
a **precompute-once** model — the backend computes the whole bounded ephemeris on
connect, playback is pure client-side clock math (scrub latency ≤200 ms by
construction). Follow-ups in the same phase: live-catalog time-travel via
`seek`/`catalog-snapshot`, play-from-a-traveled-time with rolling prefetch capped
at 100× (Decision 21).

**Delivered (4B):** the three.js proximity view (`views/ProximityView.tsx`) —
chief at the LVLH origin, deputies from the new `scenario-relative` stream
(plain JSON, not CZML), both viewports in lockstep because they only *read* the
one clock. High-frequency samples live in `stream/relativeBuffer.ts`, a module
singleton outside Zustand (Decision 5).

**Deviations & lessons:**
- **WebSocket callbacks run outside the servlet security-filter window** — the
  dev filter clears the SecurityContext, so identity is captured at the
  **handshake** (`ScenarioHandshakeInterceptor`) and reads go through
  context-free `bodyForStream`/`findByEmail`. 4403 collapses into 4404 so
  scenario ids can't be enumerated.
- Severing Cesium's clock silently stops `viewer.clock.onTick` — the
  selected-satellite readout had to move to the same `preRender` listener or the
  InfoPanel froze.
- **Relative velocity must come from the rotating LVLH transform** built once
  from the live chief propagator — the single-epoch `toRelativeState` drops the
  frame's rotation rate (correct position, wrong velocity). This is the R15
  lesson every later relative-state producer follows.
- No frontend test framework was adopted (declined); lockstep is guaranteed
  structurally (single writer) and verified in-browser.

**Pointers:** Decisions 5, 11, 21 · acceptance-criteria §Phase 4 ·
[streaming-contract.md](./streaming-contract.md).

## Phase 5 — relative readout, impulsive maneuvers, CW and templates

**Delivered (5A):** the live relative readout (distance / range-rate / R-I-C per
deputy, throttled rAF into refs — never Zustand) and backend-computed **closest
approach** (`tcaEpoch`/`tcaDistanceM` per deputy, golden-section refine on the
live propagators — the streamed grid is too coarse for an honest client-side
minimum). Later in the phase: the distance-vs-time graph (Decision 22).

**Delivered (5B):** impulsive ΔV maneuvers — `ScenarioBody` schema **v2** (the
first forward-additive bump: old bodies parse with `maneuvers=null`, coalesced
and re-stamped on save, **no DB migration** — the precedent every later bump
followed), audited `addManeuver`/`removeManeuver`, re-propagation via the
existing reload-nonce path, ΔV glyphs + the Σ|ΔV| budget.

**Delivered (5C):** CW fidelity (`CwPropagation`, a closed-form STM relative
provider — chief on SGP4, deputies seeded R15-correctly from the live LVLH
frame) with validity hints in the envelope (`maxSeparationM`,
`chiefEccentricity`); Hohmann and Lambert two-impulse rendezvous templates.

**Deviations & lessons:**
- **SGP4 cannot be maneuvered** (its analytic state can't be reset mid-run), so
  any role with a maneuver silently upgrades to the numerical engine — the
  mechanism, not a fallback; surfaced in the UI.
- **Template solvers run once, on the mutation path.** Hohmann/Lambert compute
  concrete frozen impulses stored in the body — so saved scenarios rerun
  byte-identically regardless of solver internals, and the streaming path never
  solves anything.
- Determinism with events required pinning the `DateDetector` maxCheck/threshold
  and sorting same-instant burns by (epoch, id).
- Body-frame ΔV was deferred to Phase 7 (no attitude profile existed yet to
  define "body").

**Pointers:** Decision 22 (+ Decision 23's robustness addendum for what the
rendezvous template's open-loop limits turned out to be) ·
acceptance-criteria §Phase 5. The Phase 1–5 closeout audit (2026-06-16) confirmed
every criterion and invariant, fixing one finding (`toRic` rerouted through
`FrameService`).

## Phase 6 — proximity scene (models, ribbons, cameras, Earth)

**Delivered:** the point-cloud proximity view became a scene — procedural
spacecraft models (box bus + hinged solar arrays + dish gimbal) with a
`GLTFLoader` swap seam (`/public/models/spacecraft.glb`, R6) and a fixed-pixel
marker as far-LOD; derived ram/LVLH orientation labeled **"estimated"**;
past-solid / predicted-dashed trajectory ribbons (windowed ±WINDOW_SECONDS,
Catmull-Rom densified, depth-tested); camera modes (external / chief-body /
deputy-body); the Earth backdrop placed from one additive stream field
(`chiefRadiusM`) — the only backend change.

**Deviations & lessons** (the display-scale war stories are in Decision 23; the
short version):
- The 1 m–100,000 km scene needs the renderer's **logarithmic depth buffer**;
  fat `Line2` ribbons with `depthTest:false` smeared the whole ephemeris over
  the planet and were replaced by windowed depth-tested `THREE.Line`; an
  additive atmosphere shell z-fought the surface and was dropped.
- Validating the phase surfaced that a scenario can leave the propagator's valid
  domain (decay, or a bad 12 km/s maneuver found in a saved scenario) and
  previously crashed the whole stream. The fix — per-sample **HOLD** after the
  first domain exit, clean **4422** when a body is never valid, plus maneuver
  guardrails (Hohmann re-entry check, ≥5 km/s budget flag) — is recorded in
  Decision 23's robustness addendum, along with the honest limits of the
  open-loop rendezvous template (the origin of R16, later resolved in 9A).
- The phase's follow-up list seeded the Phase-9 scope: the four-layer
  flight-ready-rendezvous plan (corrector / arrival-search / phasing /
  closed-loop terminal ops) shipped almost exactly as sketched. Still open from
  that list: real GLTF models through the seam (decisions.md deferred registry),
  ribbon polish (fade-at-window-edge), and the R18 numerical-cost guardrail.

**Pointers:** Decision 23 · acceptance-criteria §Phase 6 · risks R6, R16–R18.

## Phase 7 — sensors, modeled attitude, acquisition events

**Delivered (7A):** sensors as first-class scenario objects — `ScenarioBody`
schema **v3** (`Sensor`/`Fov`/`Mount`/`AttitudeProfile`, cone + rect, body-fixed
mounts), audited `addSensor`/`removeSensor`/`setAttitude`, translucent FOV
volumes riding each craft's body frame, `SensorPanel` with type presets. The
Phase-6 orientation *estimate* was retired: attitude became
**backend-authoritative and modeled** (`lvlh`/`fixed`), streamed as a
three.js-convention quaternion pinned by a signed-axis test (R15) — one source
of truth for the drawn FOV and the computed events (Decision 9).

**Delivered (7B):** acquisition / loss-of-sight events
(`analysis/SensorEventComputer` — in-FOV ∧ in-range ∧ Earth-unobstructed,
computed on the sample grid + bisection refine), timeline AOS/LOS bands, and the
sensor-frame camera mode.

**Deviations & lessons:**
- The first event implementation **re-propagated independently** — and for a
  maneuvered deputy (a stateful numerical propagator with impulse detectors),
  out-of-order grid+bisection access returned a trajectory inconsistent with the
  rendered one; events disagreed with the drawn cone by minutes. The fix —
  compute events **from the already-sampled trajectory** (`SampledCraft`), never
  re-propagating — became the architectural pattern for every later `analysis/`
  computer (eclipse, conjunctions, constraints, link budget), guarded by a
  maneuvered-scenario regression test.
- v1 simplifications, done knowingly: the event FOV test is a bounding circular
  cone (a rect uses its larger half-angle); occlusion is Earth-only.

**Pointers:** Decision 24 · acceptance-criteria §Phase 7.

## Phase 8 — environment and events

**Delivered (8A):** Sun/Moon LVLH direction samples on the render grid
(`FrameService.directionInLvlh`, reusing the Orekit bodies already driving the
force model) → a real `DirectionalLight`, Earth terminator, eclipse-consistent
craft dimming — closing the R17 flat-lighting hole; conical umbra/penumbra
eclipse per craft (`EclipseEventComputer`) computed in **geocentric ECI** from
position arrays captured for free inside the existing per-step sampling loop.

**Delivered (8B):** intra-scenario conjunctions (`ConjunctionEventComputer`,
configurable `missDistanceThresholdM`); sun-keep-out + approach-corridor
constraints (`ConstraintChecker`, `ScenarioBody` schema **v5** — v4 was already
taken by measured ephemeris); timeline eclipse bands / conjunction ticks /
violation marks; `EnvironmentPanel`.

**Delivered (8C):** catalog conjunction screening (UC-7) as a separate REST
request/response (`ScreeningService`) — a two-stage prune (coarse radial-shell
band test `|p1−p2| ≥ ||p1|−|p2||`, then fine refine on survivors, parallelised)
keeps screening ~14.5k satellites tractable. A snapshot vs the live catalog,
tagged with the run instant — the one documented determinism exception (R11).

**Deviations & lessons:**
- The frame split is enforced **at the type level**: `SampledGeocentricCraft`
  (`posEci`) vs the LVLH `SampledCraft` (`pos`) — R15 discipline applied to
  plain arrays.
- Measured-data slices 1–2 landed mid-phase, for free value: a measured chief's
  tabulated ephemeris is just another `PVCoordinatesProvider`, so eclipse and
  sun-keep-out evaluate against its **real** attitude with no extra work.
- Sun-keep-out reused `QuaternionSamples.rotate` promoted to a shared util —
  the sensor event computer and constraint checker share one quaternion path.

**Pointers:** Decision 25 · acceptance-criteria §Phase 8 · UC-4/5/7.

## Phase 9 — advanced maneuvers and analysis

**Delivered (9A — closes R16):** the two-impulse rendezvous went from an
open-loop two-body sketch to a **converged plan**: `RendezvousCorrector`, a
differential corrector against the real propagators (damped Gauss-Newton/LM +
backtracking line search + domain-exit fallback + ΔV/iteration caps; corrected
miss <1 m where the raw plan missed by km); an arrival × revolution Lambert ΔV
**search** (`RendezvousSearchService`); a co-elliptic **phasing** template.
Non-convergence falls back to the open-loop seed + an audit warning, not a 422.

**Delivered (9B):** `prop/CwTargeting` (analytic CW STM + two-impulse targeting,
null at the integer-rev singularity) powering NMC, V-bar/R-bar hold,
**glideslope** (chained constant-closing-rate CW legs + park burn), and
genuinely **closed-loop station-keeping** (each correction rebuilds the deputy's
real numerical propagator with corrections-so-far and re-aims). **Finite burns**
(US-MAN-11): optional `thrustN`/`ispSec` (schema **v6**) realised as an Orekit
`ConstantThrustManeuver` of the Tsiolkovsky duration, centred on the epoch so it
collapses to the impulse as thrust → ∞.

**Delivered (9C):** Monte Carlo dispersion + covariance ellipsoids
(`MonteCarloService`, UC-6) — the project's **first RNG**, kept bit-identical
(R11/R21) via per-sample `SplittableRandom(mix(seed,i))`, fixed draw order,
index-ordered collect, canonicalized eigenvectors, in a bounded ≤6-thread pool
(an unbounded run crashed the test JVM).

**Delivered (9D):** link budget / SNR (`LinkBudgetComputer`, Friis, schema-v6
`LinkBudget` on a sensor, additive `linkBudgets` stream field, Timeline SNR band).

**Deviations & lessons** (the operational versions live in the
[dev guide's gotchas](./dev-guide.md)):
- **Lambert degenerates** for co-orbital and ~180°-transfer geometries —
  co-orbital rendezvous is a *phasing* problem, not a Lambert one.
- An impulse at the exact propagator seed epoch **doesn't fire**
  (`DateDetector` at t0); production frozen TLEs always precede the window.
- The corrector's finite-difference step had to be large (1 m/s) to dominate the
  adaptive integrator's step-pattern noise — 0.05 m/s gave a non-descent
  direction.
- `NumericalPropagation.build` reloads space-weather data per build — the
  dominant per-MC-sample cost; MC default dropped 500 → 100 samples (cap 500).
- Post-phase additive change (2026-07-02): the templates + search now plan
  against a **measured-ephemeris chief** (`ChiefStateResolver`), the
  precondition for the deferred composable-templates work.

**Pointers:** Decision 27 · acceptance-criteria §Phase 9 · risks R16 (resolved),
R18, R21.

## Phase 10 — enterprise hardening

**Delivered (10A):** real auth — OIDC resource-server (stateless bearer JWT)
gated by `orbit.auth.mode` (`stub` default keeps the dev loop IdP-free), two
conditional filter chains, Keycloak realm-roles → `ROLE_*`, WebSocket auth via
`?access_token=` (browsers can't set headers on `new WebSocket()`), frontend
PKCE (`react-oidc-context`) + Bearer middleware, a self-hosted Keycloak dev
overlay. Ownership was already enforced (the Decision-16 seam paid off —
activation was additive, not a rewrite; see risks R9).

**Delivered (10B):** governance — audit-log + version-history REST + panel;
end-to-end reproducibility proofs (byte-identical `loadAndEncode` across
SGP4/numerical/maneuvered/finite-burn + MC same-seed); the §5.2 validation suite
(`ValidationConformanceTest` + [validation-conformance.md](./validation-conformance.md)
— AIAA conformance inherited from Orekit; we validate correct integration, R2).

**Delivered (10C):** deployment — prod frontend image (nginx + runtime `/env.js`
so one image is portable), the Helm chart
([deploy/helm/orbit](../deploy/helm/orbit) — split TLS ingresses, k8s Secrets,
external-DB/IdP/GitOps toggles), the offline air-gapped bundle
([scripts/bundle.sh](../scripts/bundle.sh)), and [deployment.md](./deployment.md).
Dev stays on Compose.

**Deviations & lessons:**
- The dev filter had to **drop `@Component`** — as a global servlet filter it
  would clobber JWT auth in oidc mode; it is constructed only inside the stub
  chain.
- The browser/backend **issuer-consistency** problem (the token's `iss` must be
  one URL both sides resolve) shaped the deploy story; post-phase the Compose
  overlay moved to a single-origin HTTPS nginx front on :8443 (see
  [deployment.md](./deployment.md)) because OIDC PKCE needs a secure context.
- **Still open:** the full end-to-end install on a live Kubernetes cluster has
  never been run (the chart is `helm lint`/`helm template` clean; this box has
  no k8s tooling). Tracked in acceptance-criteria §Phase 10 and Decision 28.

**Pointers:** Decision 28 · acceptance-criteria §Phase 10 ·
[deployment.md](./deployment.md) · risks R9 (largely resolved).

---

*Phase 11 (polish & ship — exports, per-user demos, help, perf instrumentation,
docs) closed the roadmap on 2026-07-06; its plan stays live at
[phase-11-plan.md](./phase-11-plan.md) — the §5.1 evidence table is now filled
(2026-07-07, RTX 4090; two documented misses — full-catalog overlay / R7, and 10-craft
proximity), with the manual click-throughs still pending. The measured-data feature track
continues at [measured-data-plan.md](./measured-data-plan.md) (slice 3 planned).*
