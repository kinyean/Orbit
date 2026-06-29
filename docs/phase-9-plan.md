# Phase 9 — Advanced Maneuvers & Analysis (plan + live status)

The **HOW** for Phase 9 (roadmap §9; SRS §3.5 / §3.12.4–5 / §3.6; US-MAN-06..11,
US-MC-01/02, US-EVT-05; UC-6). Sliced **9A / 9B / 9C / 9D**. The full design rationale is in
the approved planning doc `~/.claude/plans/plan-out-phase-9-floating-deer.md`; the **WHY**
becomes Decision 27 (still to write — see Remaining). This file is the **resume point**.

## Status (as of this session)

- **9A — Flight-ready rendezvous: ✅ done & verified.**
- **9B — CW close-range templates: ✅ core done & verified** (NMC + V-bar/R-bar hold).
  Finite burns / glideslope / closed-loop station-keeping **deferred** (see Remaining).
- **9C — Monte Carlo + covariance: ✅ done & verified.**
- **9D — Link budget / SNR: ✅ done** (`LinkBudgetComputer`, schema v6 `LinkBudget`,
  additive `linkBudgets` stream, `SensorPanel` fields + `Timeline` SNR band; `LinkBudgetComputerTests`).
- **Docs (Decision 27, acceptance-criteria, user-stories, risks, roadmap, CLAUDE.md): ✅ done**
  (2026-06-29 — Decision 27 written, R16 resolved, R21 added).

**Backend `./gradlew test` = 178 green** (was 152 at Phase 8 start). Frontend `type-check`
+ `build` green; `gen:api` regenerated. All slices verified on the live dev stack.
**Committed 2026-06-29** on branch `Phase-9` (9A/9B-core/9C/9D + docs in one Phase-9 commit).
Remaining work below is finite burns + glideslope + station-keeping (finish 9B).

---

## What landed (file map, for resume)

### 9A — Flight-ready rendezvous (closes R16)
- **NEW** [RendezvousCorrector.java](../backend/src/main/java/space/orbit/backend/scenario/RendezvousCorrector.java)
  — differential corrector against the real propagators (numerical deputy + SGP4/numerical
  chief). Damped Gauss-Newton (LM `JᵀJ+λI`) + backtracking line search + domain-exit (`OrekitException`)
  fallback + ΔV/iteration caps. Returns `Correction(depart, arrive, missM, iterations, converged, note)`.
- **NEW** [RendezvousSearchService.java](../backend/src/main/java/space/orbit/backend/analysis/RendezvousSearchService.java),
  [DvCell.java](../backend/src/main/java/space/orbit/backend/analysis/DvCell.java),
  [RendezvousSearchResult.java](../backend/src/main/java/space/orbit/backend/analysis/RendezvousSearchresult.java)
  — arrival × revolution two-body Lambert ΔV grid (serial chief-grid + pure parallel cells).
- **MOD** [ManeuverTemplateService.java](../backend/src/main/java/space/orbit/backend/scenario/ManeuverTemplateService.java)
  — `rendezvous(id, dep, arrival, corrected, nRev)` (corrected default true → corrector);
  `phasing(id, dep, revs)` (two-body sketch, in-track burns, window guard); `chiefStreamFidelity`.
- **MOD** [PropagationService.java](../backend/src/main/java/space/orbit/backend/prop/PropagationService.java)
  — extracted `buildManeuvered(seed, impulses)`; added `propagatorFor(StateVector seed, List<Impulse>)`.
- **MOD** [ScenarioController.java](../backend/src/main/java/space/orbit/backend/api/ScenarioController.java)
  — `RendezvousRequest` gained `corrected`/`nRev`; new `POST /maneuvers/rendezvous/search`,
  `POST /maneuvers/phasing` + DTOs.
- **Tests:** NEW `RendezvousCorrectorTests` (the R16 proof: corrected <1 m vs raw km; byte-identical
  rerun; divergence fallback), NEW `RendezvousSearchServiceTests`; MOD `ManeuverTemplateServiceTests`,
  `ScenarioControllerTests`.
- **Frontend:** MOD [ManeuverPanel.tsx](../frontend/src/scenario/ManeuverPanel.tsx) (Find→ΔV-map
  table→Insert-corrected + phasing row), MOD [useStore.ts](../frontend/src/store/useStore.ts)
  (`applyRendezvous` corrected/nRev, `searchRendezvous`, `applyPhasing`).

### 9B — CW close-range templates
- **NEW** [CwTargeting.java](../backend/src/main/java/space/orbit/backend/prop/CwTargeting.java)
  — CW STM blocks (analytic, matches `CwPropagation.advance`) + `twoImpulse(r0,v0,rT,vT,n,dt)`
  (null at integer-rev singularity). **NEW** test `CwTargetingTest` (lands-on-target, NMC closed
  loop, integer-rev null).
- **MOD** `ManeuverTemplateService` — `nmc(id, dep)` (in-track drift-cancel `vy=−2nx`),
  `hold(id, dep, axis, distanceM, arrival)` (CW two-impulse to a V-bar/R-bar point, zero arrival
  velocity), `relativeStateLvlh(chiefProp, depProp, t)` (rotating-LVLH relative state, R15).
- **MOD** `ScenarioController` — `POST /maneuvers/nmc`, `POST /maneuvers/hold` + `NmcRequest`/`HoldRequest`.
- **Tests:** MOD `ManeuverTemplateServiceTests` (`closeBody`, nmc, hold); MOD `ScenarioControllerTests`.
- **Frontend:** MOD `ManeuverPanel.tsx` (Close-range section: NMC button + hold axis/dist/arrival),
  MOD `useStore.ts` (`applyNmc`, `applyHold`).

### 9C — Monte Carlo + covariance
- **NEW** [MonteCarloService.java](../backend/src/main/java/space/orbit/backend/analysis/MonteCarloService.java)
  — per-sample `SplittableRandom` from `mix(seed, i)` (SplitMix64), perturbs the deputy's ECI seed
  state (Gaussian pos/vel) + maneuver ΔV (magnitude + pointing tilt), propagates each sample in a
  **bounded `ForkJoinPool`** (`MC_PARALLELISM ≤ 6` — caps memory; result is pool-independent,
  index-ordered collect), expresses relative to the chief LVLH (serial Transform[] precompute),
  aggregates the cloud + per-epoch covariance ellipsoids (Hipparchus `EigenDecompositionSymmetric`
  → ordered, sign-canonicalized, right-handed → quaternion). `DEFAULT_SAMPLES=100`, `MAX_SAMPLES=500`.
- **NEW** [MonteCarloResult.java](../backend/src/main/java/space/orbit/backend/analysis/MonteCarloResult.java),
  [EllipsoidSample.java](../backend/src/main/java/space/orbit/backend/analysis/EllipsoidSample.java).
- **MOD** [FrameService.java](../backend/src/main/java/space/orbit/backend/prop/FrameService.java)
  — extracted public static `matrixToQuaternionXyzw(double[][])` (the one pinned three.js trace
  formula); `basisQuaternion` now delegates to it.
- **MOD** `ScenarioController` — `POST /scenarios/{id}/monte-carlo` + `MonteCarloRequest`; injects
  `MonteCarloService`.
- **Tests:** NEW `MonteCarloServiceTests` (byte-identical same-seed = the §5.4.1 + order-independence
  proof; zero-uncertainty → nominal; recovers initial σ); MOD `ScenarioControllerTests`.
- **Frontend:** NEW [montecarlo.ts](../frontend/src/proximity/montecarlo.ts) (cloud `THREE.Line` +
  3σ ellipsoid shells), NEW [MonteCarloPanel.tsx](../frontend/src/scenario/MonteCarloPanel.tsx),
  MOD [ProximityView.tsx](../frontend/src/views/ProximityView.tsx) (`sceneRef` + MC effect),
  MOD `useStore.ts` (`monteCarlo`/`monteCarloVisible` slice + `runMonteCarlo`/`setMonteCarloVisible`/
  `clearMonteCarlo`), MOD `App.tsx` (mount `MonteCarloPanel`).

---

## Scope decisions / deviations from the original plan (reflect these in Decision 27)

1. **Deferred within 9B (build next):** finite burns (US-MAN-11), glideslope, closed-loop
   station-keeping. Reason: finite burns are a cross-cutting prop-layer change; not rushed at
   session end. The `CwTargeting` primitive is in place for glideslope/SK.
2. **Monte Carlo default 500 → 100 samples** (cap 500). Each sample is a full numerical
   propagation (~0.5 s), so 500 sync ≈ 4 min (R18). The pool is **bounded to ≤6** to cap peak
   memory (an unbounded common-pool run crashed the test JVM).
3. **Corrector non-convergence falls back to the open-loop seed + a warning in the audit summary**
   (the recommended option from the plan), not a hard 422.

## Gotchas / lessons discovered (save the next session time)

- **Lambert degenerates** for co-orbital pairs and ~180°-transfer-angle geometries (returns
  multi-km/s branches). The corrector test uses a chief ~0.5° ahead + ~0.4-orbit transfer (clean
  ~144° angle, small gentle seed). Co-orbital rendezvous is a *phasing* problem, not Lambert.
- **Impulse at the exact propagator seed epoch doesn't fire** (`DateDetector` at t0). The corrector
  test sets the deputy TLE epoch *before* the scenario start so the burn fires mid-stream (production
  frozen TLEs always precede the window). Worth a defensive note if a user ever sets scenario start = TLE epoch.
- **FD Jacobian step** must be large enough (`FD_STEP_MS = 1.0`) to dominate the adaptive
  integrator's step-pattern noise — 0.05 m/s gave a noise-swamped, non-descent direction.
- **MC determinism** needs: per-sample seed (not a shared RNG), fixed intra-sample draw order
  (pos xyz → vel xyz → per-maneuver mag/tilt/azimuth), index-ordered collect, and **canonicalized
  eigenvectors** (sorted desc, dominant-component-positive sign, right-handed) so the ellipsoid
  quaternion is deterministic.
- **`NumericalPropagation.build` reloads space-weather per build** — the dominant per-sample cost.
  Don't raise MC sample counts blindly; consider caching if MC perf becomes a priority.
- Dev-stack iteration: edit → `docker compose up -d --build backend` → wait health →
  `docker compose exec -T frontend sh -c 'BACKEND_URL=http://backend:8080 npm run gen:api'` →
  type-check. The frontend container bind-mounts the source, so HMR picks up edits.

---

## Remaining work (next session, in priority order)

> **Updated 2026-06-29:** items 1 (9D link budget) and 3 (docs) are now **done** — see Status
> above. The only remaining Phase-9 code is **item 2 (deferred maneuvers)**: finite burns,
> glideslope, closed-loop station-keeping.

### 1. 9D — Link budget / SNR overlays (US-EVT-05, Gita) — ✅ DONE
*(Built as below; kept for the record.)* Mirror the Phase-7/8 sampled-trajectory + additive-stream pattern.
- **Backend.** Extend `ScenarioBody.Sensor` (schema bump — **v6**, forward-additive: add the field +
  a convenience constructor for the old arity, bump `CURRENT_SCHEMA_VERSION`, re-stamp in `parse()`,
  no DB migration) with an optional `LinkBudget` sub-record (RF: frequency, Tx power dBm, Tx/Rx gain
  dBi, system noise temp / noise figure, bandwidth; optical: aperture, wavelength, detector NEP/QE).
  New `analysis/LinkBudgetComputer` on the **sampled trajectory** (like `SensorEventComputer`): per
  sensor↔target sample, free-space path loss ∝ 1/r² (range from `SampledCraft`) → SNR(dB); optical
  adds Sun-angle terms (8A's `sunVector` is already in the samples). Stream additively
  (`linkBudgets` series in the `scenario-relative` envelope; widen `RelativeStateEncoder.encodeRelative`
  + a `write*` helper that omits when empty; `VERSION` stays `"1"`). Author via the audited path
  (extend `addSensor` or a new `setLinkBudget`).
- **Frontend.** Link-budget fields in `SensorPanel` (RF/optical presets); `Timeline.tsx` SNR band
  (red below a configurable threshold, reuse the AOS/LOS-window band pattern); live SNR in the
  sensor-frame readout. Parse `linkBudgets` in `relativeBuffer.ts`. `gen:api`.
- **Tests.** `LinkBudgetComputerTests` — SNR falls 6 dB per range-doubling (inverse-square),
  threshold crossings, determinism. Update encoder/stream tests for the additive field.

### 2. Deferred maneuvers (finish 9B)
- **Finite burns (US-MAN-11).** `Maneuver` record (schema v6) + optional `thrustN, ispSec,
  durationSec` (+ 5-arg convenience ctor; null → impulsive). Extend `Impulse` (or add a `Burn`) with
  the finite fields + a 4-arg convenience ctor (keeps all call sites). `PropagationService.buildManeuvered`
  branches: finite → Orekit `ConstantThrustManeuver(epoch, duration, thrust, isp, LofOffset(QSW)
  attitudeOverride, dirUnit)` (mass is set via `settings.massKg()` in `NumericalPropagation.build` —
  confirmed available); compute duration from the intended ΔV via Tsiolkovsky if the user gives ΔV+thrust+Isp.
  CW fidelity: approximate as impulsive at the burn midpoint (documented). Thread the fields through
  `ScenarioStreamService.toImpulses` + `ScreeningService.toImpulses` + the controller `ManeuverRequest`
  + `ManeuverDraft` (convenience ctors!). Frontend: finite toggle on the Add-Δv form; the ΔV glyph
  shows the burn-duration window. Tests: v6 round-trip + one audit row; finite ≈ equivalent impulse.
- **Glideslope (US):** discretize a constant-closing-rate approach along V-bar/R-bar into segments,
  each a `CwTargeting.twoImpulse` leg. **Closed-loop station-keeping:** propagate the relative orbit,
  detect drift past a tolerance, emit corrective `CwTargeting` burns on an interval.

### 3. Docs — ✅ DONE (2026-06-29)
*(Decision 27 written; R16 resolved + R21 added; acceptance-criteria / user-stories / roadmap /
CLAUDE.md updated. Original checklist kept for the record.)*
- **Decision 27** in [decisions.md](./decisions.md) (Context/Decision/Why/Alternatives/Consequences,
  Decision-25 style) + table of contents. Capture the scope decisions above; **resolve R16**; note the
  **first introduction of seeded RNG** and how determinism is preserved (per-sample seed + ordered collect).
- [acceptance-criteria.md](./acceptance-criteria.md) — Phase-9 checklist from US-MAN-06..11 / US-MC-01/02 /
  US-EVT-05 + SRS clauses (mark deferred items).
- [user-stories.md](./user-stories.md) — expand the Phase-9 outline into full stories; mark done/deferred.
- [risks.md](./risks.md) — **R16 resolved** (differential corrector; keep the trigger note); add the new
  RNG-determinism surface + the MC numerical cost (R18) note.
- [architecture-and-roadmap.md](./architecture-and-roadmap.md) §9 + `CLAUDE.md` "Current phase" — mark
  Phase 9 status, the schema-version bump (→ v6 with finite burns/link-budget), and the new
  `analysis/` classes.

---

## Verification commands

```bash
# Backend (JDK 21; ~172 tests today, more after 9D/finite burns)
cd backend && export JAVA_HOME=$HOME/jdk-21.0.11+10 && export PATH=$JAVA_HOME/bin:$PATH
./gradlew test                       # full suite (needs Docker for Testcontainers)
./gradlew test --tests "*RendezvousCorrectorTests" --tests "*MonteCarloServiceTests"  # the R16 + reproducibility proofs

# Frontend (inside the running dev-stack container)
docker compose up -d --build backend                       # rebuild after backend changes
docker compose exec -T frontend sh -c 'BACKEND_URL=http://backend:8080 npm run gen:api'
docker compose exec -T frontend sh -c 'npm run type-check && npm run build'

# Dev-stack smoke (demo scenarios)
#   rendezvous demo: a50c2afe-21ed-4119-96e1-2af250c36d12 (chief 99001 / chaser 99003)
#   close-formation: a55f3982-b431-47c7-98f7-59a79681f515 (chief 99001 / NMC deputy 99002)
curl -s -X POST localhost:8081/scenarios/<id>/maneuvers/rendezvous/search -H 'Content-Type: application/json' -d '{"deputyNoradId":99003}'
curl -s -X POST localhost:8081/scenarios/<id>/monte-carlo -H 'Content-Type: application/json' -d '{"deputyNoradId":99002,"sampleCount":40,"seed":7,"posSigmaM":100,"velSigmaMs":0.1,"dvMagFrac":0.02,"dvPointingDeg":0.5}'
```
