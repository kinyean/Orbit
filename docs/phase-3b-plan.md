# Phase 3B — High-fidelity numerical propagation + relative frames

> **✅ Historical — Phase 3B shipped (2026-06-10), backend tests green.** The
> design below was implemented as planned; the one substantive refinement —
> the LVLH/RIC orientation is pinned by a **signed-axis** test, since the
> planned closed-loop test is rotation-invariant and wouldn't catch a flipped
> convention (R15) — is recorded in [decisions.md](./decisions.md) Decision 20.
> Current status: [acceptance-criteria.md](./acceptance-criteria.md) and `CLAUDE.md`.

> Planning artifact, written ahead of implementation (same workflow as
> [phase-2-plan.md](./phase-2-plan.md) and [phase-3-plan.md](./phase-3-plan.md)).
> Phase 3A shipped; this is the **3B half** — the Frank-facing physics depth. It
> supersedes the brief "Phase 3B" sketch in [phase-3-plan.md](./phase-3-plan.md)
> with the detailed, planned design. Picked up as active work in a fresh session.

## Context

Phase 3A made scenarios real (compose → save → version → audit) but everything
still runs on **SGP4** — fine for "where's the dot," too coarse (±1–3 km) for
proximity ops. Phase 3B adds the **high-fidelity numerical propagator** Frank
must trust and the **LVLH/RIC frames** that are the native language of relative
motion (US-PROP-02, US-FRAME-02; SRS §3.1.2–6, §3.2.3–4). It also stands up the
**fidelity-dispatch** seam so a scenario's `fidelity` selects the engine.

**This is a backend-only, engine-deepening slice.** It adds no UI and no
streaming — there is nothing new to click. The visible payoff comes in **Phase
4** (proximity view + per-scenario streaming), which *consumes* this engine.
3B is proven by tests (`./gradlew test`), exactly as Phase 2 built SGP4 before
wiring the catalog stream.

**Groundwork verified during planning:**
- `prop/FrameService` caches `eci()` (EME2000), `teme()`, `ecef()` (ITRF) +
  a WGS84 `earth` ellipsoid in `@PostConstruct init()`. Frames are real Orekit
  `Frame` objects, and `StateVector` already carries `Frame frame` — so **LVLH/
  RIC are just Orekit `LocalOrbitalFrame`s; no enum/string tag, no contract or
  schema change** (Decision 12 holds as-is).
- `prop/SatellitePropagator` exposes `eciState(TLEPropagator, date) → StateVector`
  (EME2000) — the **seed** for the numerical propagator.
- The bundled `orekit-data` (build + Docker `/opt/orekit-data`) **already has
  everything**: `CSSI-Space-Weather-Data/SpaceWeather-All-v1.2.txt` (NRLMSISE‑00
  inputs), `Potential/eigen-6s.gfc` (gravity ≥J4), `DE-440-ephemerides` (Sun/
  Moon), EOP + leap seconds. **No data provisioning needed.**
- Greenfield: no force-model code exists; `fidelity` is a plain string (no enum).
  Orekit 13.1.5.

### Decisions locked (with the user)
- **Propagation settings are pinned constants in code** (mass, area, drag Cd,
  reflectivity Cr, gravity degree/order, integrator tolerance) → deterministic
  by construction, no body-schema change. They move into the scenario body when
  Phase 4 wires per-scenario propagation (R11's "settings in the body" applies
  once they're user-tunable + reproduced across users).
- **Frames stay Orekit `Frame` objects** (no enum); `StateVector` is unchanged.
- **Fidelity enum lives in the propagation layer only** — the persisted
  `ScenarioBody.fidelity` string is untouched; `PropagationService` parses it.

### Out of scope (Phase 3B)
- Clohessy–Wiltshire propagator → **Phase 5** (`CW` stays a known fidelity value
  but throws "not yet implemented" in 3B).
- Per-scenario propagation streaming, proximity view, relative-state stream →
  **Phase 4**. No frontend changes in 3B.
- Per-scenario tunable settings in the body; CCSDS/Keplerian initial states.
- §5.2 golden-vector / AIAA conformance suite → **Phase 10**. 3B uses
  invariant + determinism tests (matching the existing prop suite).

---

## Backend — extend `space.orbit.backend.prop`

Keep the thin-wrapper discipline (R1): new code wraps Orekit; callers use our
surface, not Orekit directly. All Orekit-touching beans carry `@DependsOn("orekitConfig")`.

### 1. `Fidelity` enum (`prop/Fidelity.java`, NEW)
`SGP4`, `NUMERICAL`, `CW` + `static Fidelity fromString(String)` (case-insensitive;
null/blank/unknown → `SGP4`, the safe default). The only place the loose string
becomes a type.

### 2. `PropagationSettings` (`prop/PropagationSettings.java`, NEW)
An immutable record of pinned defaults with one `DEFAULT` constant:
spacecraft `massKg`, cross-section `areaM2`, drag `cd`, reflectivity `cr`;
`gravityDegree`/`gravityOrder` (≥4, e.g. 16×16); integrator `positionToleranceM`,
`minStepS`, `maxStepS`. Documented as deterministic/pinned (SRS §5.4.1). No
process-env reads.

### 3. Numerical propagator (`prop/NumericalPropagation.java`, NEW — `@Service`)
Builds an Orekit `NumericalPropagator` seeded from an ECI `StateVector`:
- Integrator: Hipparchus `DormandPrince853Integrator` (DP8(7)) sized via
  `NumericalPropagator.tolerances(positionTolerance, orbit, OrbitType.CARTESIAN)`;
  `setOrbitType(CARTESIAN)`.
- Initial state: `SpacecraftState(new CartesianOrbit(pv, eci, date, mu), massKg)`
  from the seed `StateVector` (position/velocity in `frames.eci()`).
- Force models (added in order):
  - Gravity — `HolmesFeatherstoneAttractionModel(frames.ecef(),
    GravityFieldFactory.getNormalizedProvider(degree, order))`.
  - Drag — `DragForce(new NRLMSISE00(new CssiSpaceWeatherData("SpaceWeather-All-v1.2.txt"),
    sun, earth), new IsotropicDrag(areaM2, cd))`.
  - SRP — `SolarRadiationPressure(sun, earth, new IsotropicRadiationSingleCoefficient(areaM2, cr))`.
  - Third-body — `ThirdBodyAttraction(sun)` and `ThirdBodyAttraction(moon)`
    (`CelestialBodyFactory.getSun()/getMoon()`).
- `sun`/`moon` from `CelestialBodyFactory`; reuse `FrameService.earth()` (add a
  package accessor) for the body shape. Exact Orekit 13.1.5 constructor
  signatures verified at implementation.
- Returns the reusable `NumericalPropagator`; sampling to `StateVector` (ECI) is
  done by `PropagationService.sample` (below).

### 4. `PropagationService` (`prop/PropagationService.java`, NEW — `@Service`) — the dispatch
- `Propagator propagatorFor(TLE tle, Fidelity fidelity)`:
  - `SGP4` → `satellitePropagator.build(tle)`.
  - `NUMERICAL` → seed `= satellitePropagator.eciState(build(tle), tle.getDate())`,
    then `numericalPropagation.build(seed, PropagationSettings.DEFAULT)`.
  - `CW` → `throw new UnsupportedOperationException("CW propagation lands in Phase 5")`.
- `StateVector sample(Propagator p, AbsoluteDate date)` → `p.getPVCoordinates(date,
  frames.eci())` wrapped as an ECI `StateVector` (uniform for both engines).
- This is the seam Phase 4 calls; in 3B only tests call it.

### 5. `FrameService` v2 (`prop/FrameService.java`, EDIT)
Add, reusing the cached `eci`/`ecef`/`earth`:
- `Frame lvlh(PVCoordinatesProvider chief)` and `Frame ric(PVCoordinatesProvider chief)`
  → `new LocalOrbitalFrame(eci, LOFType.LVLH | LOFType.QSW, chief, name)`.
  **Pin the LOFType to the glossary's R/I/C convention** (R radial-out, I
  along-velocity, C cross-track → `QSW` for RIC; LVLH is the same triad per the
  glossary) and document it in a comment; the round-trip test (below) validates
  orientation (R15).
- `StateVector toRelativeState(StateVector deputyEci, StateVector chiefEci)` →
  transform the deputy's PV into the chief-centered LVLH via
  `LOFType.LVLH.transformFromInertial(date, chiefPV).transformPVCoordinates(deputyPV)`;
  return a `StateVector` tagged with the LVLH frame. Clear `IllegalArgumentException`
  if chief is null/missing (US-FRAME-02).
- `Frame body(Rotation attitudeEciToBody, ...)` (minimal) — per-spacecraft body
  frame from an attitude quaternion; lightly used until sensors (Phase 7), so a
  small, tested stub is enough now.
- Add `OneAxisEllipsoid earth()` accessor for #3 to reuse.

No `StateVector` change; no `ScenarioBody`/controller/DTO/contract change.

---

## Tests (pure-JUnit, mirror `Sgp4PropagationTests`/`FrameServiceTests`)
Use `OrekitTestData.ensureLoaded()` in `@BeforeAll`; JUnit5 + AssertJ. No DB/Docker.

- **`NumericalPropagationTests`** —
  - *Invariant:* seed an ISS-like orbit, propagate one period sampling ~12 points;
    assert semi-major axis (and specific orbital energy) stay within a tight band
    (e.g. SMA drift < ~1 km over a rev) — sanity that forces are wired, not a
    golden vector.
  - *Determinism (SRS §5.4.1):* build + propagate the **same** inputs twice to the
    same epoch; assert **component-wise exact equality** of position/velocity
    (bit-identical reruns).
- **`PropagationServiceTests`** — `propagatorFor` returns an SGP4 propagator for
  `SGP4` and a numerical one for `NUMERICAL` (both sample to plausible LEO
  radius/speed via `sample`); `CW` throws `UnsupportedOperationException`;
  `Fidelity.fromString` defaults/normalizes correctly.
- **Frame round-trip** (extend `FrameServiceTests` or new `FrameRelativeTests`) —
  US-FRAME-02: a deputy placed on a **circular NMC ellipse** around the chief
  traces a **closed loop** in LVLH (start ≈ end within tolerance after one
  period); `toRelativeState` with a null chief throws clearly.

---

## Verification (end-to-end)
- **`./gradlew test`** — all green, including the three new prop test classes.
  These are pure-JUnit (Gradle provides `orekit.data.path`); **no DB/Docker
  needed** for them (the 3A Testcontainers tests still run as before).
- **Manual sanity (optional):** a small throwaway main/test that propagates ISS
  24 h numerically and prints SMA drift + confirms a rerun is byte-identical.
- **No stack/browser step** — 3B changes nothing user-facing; do not expect a
  visible change. (Phase 4 is where this becomes visible.)

## Critical files
- **NEW** `backend/.../prop/Fidelity.java`, `prop/PropagationSettings.java`,
  `prop/NumericalPropagation.java`, `prop/PropagationService.java`.
- **EDIT** `backend/.../prop/FrameService.java` (lvlh/ric/body/toRelativeState +
  `earth()` accessor). Possibly a tiny reuse tweak in `prop/SatellitePropagator.java`.
- **NEW tests** `backend/src/test/.../prop/NumericalPropagationTests.java`,
  `prop/PropagationServiceTests.java`, and LVLH round-trip (extend
  `FrameServiceTests.java` or new file).
- **Docs at close-out:** `acceptance-criteria.md` (Phase 3 — tick the 3B
  propagator/frames items), `architecture-and-roadmap.md` (3B done), `CLAUDE.md`
  (current phase → Phase 3 complete; Phase 4 next), `decisions.md` (numerical-
  propagator pinned-settings + fidelity-dispatch decision). Also: with all of
  Phase 3 done, mark `phase-2-plan.md`/`phase-3-plan.md` as completed/historical
  (a header note or move to `docs/archive/`) per the earlier discussion.

## Notes / deferred toggles
- **Default fidelity stays `sgp4`** for new scenarios through 3B (no propagation
  consumer yet; numerical-by-default is invisible and slower until Phase 4).
  US-PROP-03's "default numerical" flips when Phase 4 wires streaming.
- Settings move from pinned constants into the scenario body in Phase 4 (per the
  locked decision above).
