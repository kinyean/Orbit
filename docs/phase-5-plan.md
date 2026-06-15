# Phase 5 — Relative motion + initial maneuvers

> Planning artifact, written ahead of implementation (same workflow as
> [phase-2-plan.md](./phase-2-plan.md), [phase-3-plan.md](./phase-3-plan.md),
> [phase-3b-plan.md](./phase-3b-plan.md), [phase-4-plan.md](./phase-4-plan.md)).
> Phase 4 shipped and is verified in-browser (2026-06-15). This is the Phase 5
> design, sliced **5A / 5B / 5C** to match the project's 3A/3B, 4A/4B cadence.
> Current status: [acceptance-criteria.md](./acceptance-criteria.md) and `CLAUDE.md`.

## Context

Phase 4 gave the app two synchronized viewports playing a loaded scenario: the
Cesium global view and the three.js proximity view, driven by one authoritative
clock, fed by the per-scenario `scenario-czml` + `scenario-relative` streams. The
proximity view already *renders* the relative motion the 3B numerical engine
produces — but there is no **analysis** layer on top of it and no way to **act**
on the geometry.

Phase 5 adds that layer, for Maya (planning) and Frank (validation):
- **Relative-state analysis** — live distance / range-rate / R-I-C readouts per
  deputy (US-REL-01) and closest-approach time + distance over the scenario
  (US-REL-02).
- **Initial impulsive maneuvers** — add a ΔV to a deputy, re-propagate, see the
  effect (US-MAN-01), with a cumulative ΔV budget (US-MAN-05) and ΔV glyphs in
  the proximity view (US-MAN-04).
- **CW fidelity** for close range (US-REL-03) and the first two maneuver
  **templates** — Hohmann (US-MAN-02) and two-impulse Lambert rendezvous
  (US-MAN-03).

Decisions to respect throughout: frontend never propagates (Decision 9);
deterministic byte-identical reruns (R11); frame-tagged state (Decision 12, R15);
single authoritative clock with one rAF writer (Decision 11); maneuvers are
scenario edits → new immutable version + one audit row (Decision 16); the
streaming contract stays `VERSION="1"`, additive only (R12); prefer Orekit
built-ins and ask before adding deps.

**Groundwork verified during planning:**
- `prop/PropagationService.propagatorFor(tle, fidelity)` dispatches `SGP4`→
  analytic `TLEPropagator`, `NUMERICAL`→DP8(7) seeded from the TLE ECI state;
  `CW` throws `UnsupportedOperationException` (the 5C extension point).
- `prop/FrameService` exposes `lvlh(chief)` / `ric(chief)` (`LOFType.LVLH` ≡
  `QSW` = the glossary R/I/C), a fixed-rotation `body(...)`, and the single-epoch
  `toRelativeState` (constant provider — **not** to be used where relative
  *velocity* matters, R15).
- `stream/ScenarioStreamService.loadAndEncode` is precompute-once: it prepares a
  propagator per role, samples ECEF for CZML and chief-LVLH R/I/C for the relative
  stream on **one** time grid, with the R8 sample-cap echoed via `effectiveStep`.
  `encodeRelative` builds the rotating `lvlh(chiefProp)` once and transforms each
  deputy per step (R15-correct velocity) — the pattern CW seeding must reuse.
- `scenario/ScenarioBody` is a versioned (`schemaVersion`) Jackson record stored
  as raw `jsonb`; `ScenarioService` owns (de)serialization and is the single
  audited/versioned mutation path.
- Frontend `stream/relativeBuffer.ts` is a module singleton (outside Zustand,
  Decision 5) with `deputyPositionAt` interpolation + `getRelativeVersion`;
  `views/ProximityView.tsx` reads `currentTime` each frame (never writes).

---

## Slice 5A — Relative-state readout + closest approach (US-REL-01, US-REL-02)

The `scenario-relative` stream already carries per-deputy `[t,R,I,C,vR,vI,vC]` on
the chief LVLH grid. The **live readout** (distance, range-rate, R/I/C) is pure
display derivable from the buffer each frame. **Closest approach (TCA) is computed
on the backend** — the streamed samples sit on the R8-clamped grid (`effectiveStep`
can be ~86 s for a 24 h scenario), too coarse for an accurate JS min-scan, and the
backend already holds the propagators at full resolution.

**Frontend (live readout):**
- `stream/relativeBuffer.ts`: add allocation-free `deputyStateAt` (fills R/I/C +
  vR/vI/vC, mirroring `deputyPositionAt`'s binary-search + HOLD clamp). Derive
  `distance = |RIC|`, `rangeRate = (R·vR + I·vI + C·vC) / distance` (approaching ⇒
  negative).
- New `components/RelativeReadout.tsx`: one row per deputy (cap 10), updated via a
  throttled rAF (~200 ms, like ProximityView's `lastReadout`), writing to
  refs/DOM not Zustand. Color-keyed to ProximityView's `DEPUTY_COLORS`. Shows each
  deputy's TCA epoch + distance (from the envelope).
- `components/Timeline.tsx`: TCA tick markers (one per deputy, colored).
- `App.tsx`: mount `RelativeReadout` beside `ProximityView` when a scenario is
  loaded.

**Backend (closest approach):**
- In `ScenarioStreamService.loadAndEncode`, after preparing roles, compute each
  deputy's minimum chief-relative range over `[start,end]` — a fine analytic scan
  refined with a quadratic bracket, or (preferred) an Orekit min-distance event on
  the live propagators (deterministic, full-fidelity).
- Add `{tcaEpoch, tcaDistanceM}` per deputy to the `scenario-relative` envelope
  (additive). `RelativeStateEncoder` / `RelativeSamples` carry it; the frontend
  displays it. Recomputes for free on 5B re-propagation.

**Tests:** frontend `relativeBuffer.test.ts` — `deputyStateAt` interpolation/HOLD
clamps, range-rate sign. Backend `ScenarioStreamServiceTests` — TCA on a known
co-period closed loop matches the analytic minimum; byte-stable on rerun (R11).

---

## Slice 5B — Impulsive ΔV maneuvers (US-MAN-01, US-MAN-04, US-MAN-05)

### Schema (`ScenarioBody` → v2, additive)
- Bump `schemaVersion` to 2; add `List<Maneuver> maneuvers` to `Role`.
- `record Maneuver(String id, String kind, String epoch, String frame, DeltaV deltaV)`
  + `record DeltaV(double r, double i, double c)` (m/s; `kind`="delta_v";
  `id` = stable UUID). **`frame` = "ric" only in 5B** (body deferred — see below).
- **Migration:** `jsonb` + Jackson reads a v1 body into the v2 record with
  `maneuvers = null`; coalesce nulls + stamp `schemaVersion = 2` at parse time in
  `ScenarioService`. **No DB migration.** Test a stored v1 JSON round-trips.

### Audited mutation path (`ScenarioService`)
- `addManeuver(scenarioId, AddManeuverRequest)` / `removeManeuver(scenarioId,
  maneuverId)`: load latest body → validate (deputy exists & not chief; epoch ∈
  timeRange; frame == "ric"; finite ΔV) → new body with maneuver appended → save
  new immutable version + one `audit_log` row (`MANEUVER_ADD`) in the same
  transaction, reusing the create/update shape (Decision 16).
- DTO `AddManeuverRequest(deputyNoradId, epoch, frame, r, i, c)`; endpoints in
  `api/ScenarioController`: `POST /scenarios/{id}/maneuvers`,
  `DELETE /scenarios/{id}/maneuvers/{maneuverId}`, returning the updated scenario
  so `gen:api` regenerates the TS types.

### Maneuver-aware propagation (the trickiest part — note the SGP4 constraint)
- **A maneuvered deputy must propagate NUMERICALLY, not via SGP4.** SGP4's
  analytic `TLEPropagator` cannot have its state reset mid-propagation
  (`resetIntermediateState` unsupported), and SGP4 can't re-seed from an arbitrary
  post-burn osculating state. So **any role with ≥1 maneuver is built on the
  numerical engine** (seeded from the TLE start state, exactly as `NUMERICAL`
  already does) with the impulse(s) attached. This is the mechanism, not a
  fallback. A maneuvered deputy in an otherwise `sgp4` scenario silently upgrades
  to numerical for that deputy — surfaced in the UI with a small note.
- **Impulse construction:** `ImpulseManeuver(new DateDetector(epoch),
  new LofOffset(eci, LOFType.QSW), new Vector3D(r,i,c), Control3DVectorCostType.NONE)`
  — the ΔV direction comes from the LOF-aligned `AttitudeProvider` (QSW = the
  project's RIC), `NONE` = pure ΔV with no mass bookkeeping;
  `propagator.addEventDetector(...)` per maneuver. The precompute-once sampling
  loop in `ScenarioStreamService` is **unchanged** — it samples a propagator that
  now carries the events.
- Wire via a new `PropagationService.propagatorFor(TLE, Fidelity, List<Maneuver>,
  AbsoluteDate epoch0)` overload that forces numerical when maneuvers are present
  and attaches detectors; `ScenarioStreamService.prepareRole` passes
  `role.maneuvers()`.

### Determinism (R11)
- Maneuvers are frozen inputs (epoch string + ΔV doubles). Sort by (epoch, id)
  before attaching detectors so same-instant burns compose identically. **Pin the
  `DateDetector` maxCheck + threshold** so event location is reproducible under
  DP8(7). Extend the `ScenarioStreamServiceTests` byte-compare rerun to a
  maneuvered scenario.

### Re-propagation trigger (reuse the existing reload path)
- The frontend `addManeuver`/`removeManeuver` store actions hit the REST endpoints
  then bump `scenarioReloadNonce` — Globe already reopens the stream on nonce
  change, so `loadAndEncode` re-propagates and both views + readout + TCA refresh.
  **No new wire control message** (the reserved control channel stays reserved).

### Streaming contract (additive, stays `VERSION="1"`)
- Add a top-level `maneuvers` array to the `scenario-relative` envelope: per deputy
  `[{id,noradId,epoch,dvR,dvI,dvC,dvMag}]` (RIC components map directly to the LVLH
  axes the proximity view draws — no transform needed). `RelativeStateEncoder` +
  `ScenarioStreamService.encodeRelative` write it. Documented in
  [streaming-contract.md](./streaming-contract.md).

### Frontend
- `store/useStore.ts`: `maneuvers` in the derived type (via `gen:api`); `addManeuver`
  / `removeManeuver` actions; cumulative ΔV-budget selector per deputy `Σ|ΔV|`.
- New `scenario/ManeuverPanel.tsx`: per-deputy maneuver list + "add Δv" form (epoch
  bounded to timeRange, R/I/C m/s inputs — RIC only); cumulative ΔV per deputy
  (US-MAN-05); a "propagated numerically" note when the deputy has maneuvers.
- `views/ProximityView.tsx`: ΔV glyphs (US-MAN-04) — `THREE.ArrowHelper` at the
  deputy's interpolated position, oriented along ΔV in LVLH (R→+X, I→+Y, C→+Z),
  length scaled to |ΔV| (clamped for the 1 m–100 km scene), sprite label. Visible
  only when `|currentTime − epoch|` is within a small scrub window.
- `components/Timeline.tsx`: optional maneuver ticks (distinct from TCA ticks).

### Known limitations to note (not bugs)
- **Body-frame ΔV deferred to Phase 7.** A body ΔV needs an attitude profile;
  `FrameService.body` is a fixed rotation today, so "body" would be ill-defined.
  5B is RIC-only; revisit when sensors/attitude land (US-MAN-01's "or body").
- **Orbit-path margin uses the pre-maneuver period** (cosmetic trail length only).
- **Each maneuver edit triggers a full numerical recompute** (reload nonce →
  `loadAndEncode`). Within the ≤5 s load target; an incremental control-channel
  recompute stays deferred (the channel remains reserved).

**Tests:** backend — addManeuver creates v2 + audit; validation cases (chief
rejected, epoch out of range, non-ric frame rejected); v1→v2 round-trip; prograde
ΔV raises apogee as expected (numerical engine); deterministic byte-compare with
maneuvers; encoder serializes maneuvers. Frontend — budget selector sum; glyph
visibility windowing.

---

## Slice 5C — CW fidelity + transfer templates (US-REL-03, US-MAN-02, US-MAN-03)

### CW propagation (US-REL-03)
- Remove the CW rejection: `PropagationService.propagatorFor` CW case (currently
  throws) and the CW 4422 path in `ScenarioStreamService.loadAndEncode` /
  `ScenarioStreamHandler`.
- **CW is a relative model** — don't force it through the ECI `sample()` shape.
  Implement a small in-repo CW state-transition matrix (`prop/CwPropagation`): seed
  from the deputy's RIC pos+vel vs chief at scenario start, advance with the
  closed-form 6×6 CW STM (mean motion `n` from the chief's frozen TLE). Drive the
  `scenario-relative` producer directly; reconstruct ECI/ECEF for the CZML view by
  composing chief-ECI with the rotated RIC offset. No new dependency.
- **Seed via the rotating-LVLH PV transform (R15)** — build the chief LVLH frame
  from the live chief propagator and transform the deputy's start PV exactly as
  `encodeRelative` does, **not** `toRelativeState` (its single-epoch constant
  provider drops the frame rotation rate → wrong relative velocity).
- **Validity warning:** CW assumes a near-circular chief and small separation.
  Backend includes a `cwValid` / `maxSeparationM` hint (and a chief-eccentricity
  flag) in the envelope; the frontend banner (in `RelativeReadout`/`ManeuverPanel`)
  warns when any deputy separation exceeds ~10 km (separation already in hand from
  5A) or the chief is non-circular.

### Hohmann template (US-MAN-02)
- `POST /scenarios/{id}/maneuvers/hohmann` `{deputyNoradId, targetAltitudeKm}`.
  Derive the deputy's current orbit (Orekit `KeplerianOrbit`), compute the two
  coplanar Hohmann impulses via vis-viva (closed form, no dep), insert **two**
  `delta_v` maneuvers (frame ric, prograde +I) through the audited `addManeuver`
  path. Reuses 5B's re-propagation / glyphs / budget.

### Lambert / two-impulse rendezvous (US-MAN-03)
- `POST /scenarios/{id}/maneuvers/rendezvous` `{deputyNoradId, arrivalEpoch}`.
  r1 = deputy position at departure, r2 = chief position at arrival (both ECI via
  propagation), tof = arrival − departure. Solve with Orekit's built-in
  `org.orekit.estimation.iod.IodLambert` (pin prograde + zero-rev flags for
  determinism). Δv1 = v_transfer_dep − v_deputy; Δv2 = v_chief_arr −
  v_transfer_arr; convert to RIC, insert two maneuvers via `addManeuver`. The
  transfer arc renders for free via the existing re-propagation path.

### Determinism / frame
- CW STM is pure `(n, Δt, initial relative state)` — deterministic; pin `n` from
  the chief start state. Lambert/Hohmann solvers run **once on the mutation path**
  (not during streaming) and store concrete frozen maneuvers, so saved scenarios
  rerun byte-identical regardless of solver internals.

### Frontend
- `ScenarioPanel` / `ManeuverPanel`: fidelity selector offers `cw` (wire the
  existing composer `fidelity` field through `composerToRequest`); Hohmann form
  (target altitude) + rendezvous form (arrival epoch); CW separation warning.

**Tests:** `CwPropagationTests` (STM vs a known football orbit; identity at Δt=0);
`HohmannTests` (total ΔV matches vis-viva); `LambertTests` (endpoints reproduce
r1/r2; Δv2 nulls relative velocity at arrival); a CW-fidelity scenario streams (no
longer 4422) and reruns deterministically.

---

## Verification

- **Per slice, backend:** `./gradlew test` green (new maneuver / CW / Hohmann /
  Lambert / relative tests + extended determinism byte-compare).
- **Per slice, frontend:** `npm run type-check` + `npm run build` green;
  `npm run gen:api` after backend contract changes.
- **End-to-end (after each slice):** `docker compose up -d --build`, load the
  seeded demo or a ≥2-sat scenario:
  - 5A: readout shows distance / range-rate / R-I-C updating live; TCA in the panel
    + a timeline tick.
  - 5B: add a prograde Δv → new version + audit row (verify via `psql`); both views
    + readout re-propagate; ΔV glyph at the epoch; budget updates.
  - 5C: switch a close scenario to CW (no 4422, animates; >10 km warning shows);
    Hohmann + rendezvous templates insert maneuvers and render the transfer.
- **Reproducibility:** rerun a maneuvered / CW scenario `loadAndEncode` twice →
  byte-identical (R11).
