# Risks

What could derail the project, with mitigation strategies and trigger
conditions to watch. Reviewed at each phase boundary.

Severity scale:
- **High** — could push the project off-track or fail a key requirement.
- **Medium** — would meaningfully delay or constrain a deliverable.
- **Low** — annoying but absorbable.

Each entry: *description, likelihood, impact, mitigation, trigger to watch*.

---

## R1 — Orekit learning curve (Medium impact, Medium likelihood)

**Description.** Orekit is the right tool, but it's a deep library with its
own conventions (frame transforms, time scales, attitude providers,
propagator builders). A new user can spend weeks getting basics fluent.

**Mitigation.**
- Build a thin internal `prop/` wrapper that exposes only the operations we
  need. New code calls our wrapper, not Orekit directly.
- Spike a tracer-bullet Phase 2 implementation early (one satellite,
  SGP4-only) to surface the worst friction before it blocks later phases.
- Lean on Orekit's well-maintained docs and the public tutorials; don't
  reinvent.

**Trigger.** If Phase 2 (propagation pipeline) slips past its target by
>1 week, or if Phase 3 keeps tripping on frame/attitude issues, escalate to
"do a focused Orekit study week."

---

## R2 — Validation effort underestimated (High impact, Medium likelihood)

**Description.** SRS §5.2 demands sub-km / 24h LEO accuracy validated to
AIAA 2006-6753. Orekit is validated upstream, but our *integration* of it
needs reference-comparison tests, and we have no flight-dynamics QA history
of our own.

**Mitigation.**
- Architect the validation test suite as a first-class deliverable from
  Phase 3 (when high-fidelity propagation lands), not Phase 10.
- Use Orekit's own published test cases as fixtures; their pass/fail is
  our pass/fail (we're validating that we *used Orekit correctly*, not that
  Orekit itself is right).
- Pin Orekit versions; never silently upgrade.

**Trigger.** Any deviation from Orekit reference output beyond expected
numerical noise.

---

## R3 — Scope creep (High impact, High likelihood)

**Description.** The SRS is huge. New "obvious" features will keep
suggesting themselves. Without discipline, we ship nothing.

**Mitigation.**
- Phase plan is sequential; no jumping ahead.
- Out-of-scope list (`docs/use-cases.md`, end) is enforced.
- New requirements get pushed onto the deferred list in `decisions.md`
  before adding to a phase.
- Each phase ends with an explicit "Phase N done" gate; nothing crosses
  until it's met.

**Trigger.** Any phase taking 1.5× its target estimate; pause and audit
scope additions.

---

## R4 — Solo-developer bus factor (High impact, lifetime certainty)

**Description.** If this is built by one person, all institutional
knowledge is in their head. Health issue, job change, vacation — the
project halts.

**Mitigation.**
- Documentation discipline: every decision recorded in `decisions.md`,
  every domain term in `glossary.md`, every workflow in `use-cases.md`.
  These docs are *the* mitigation.
- Git history is the second-best documentation; commit messages explain
  *why*, not just *what*.
- Code comments explain non-obvious math and frame choices.

**Trigger.** Always present. Doc gap discovered = fix immediately.

---

## R5 — Cesium ion bandwidth ceiling (Low impact, Low likelihood at v1, Medium at scale)

**Description.** Cesium ion free tier: 5 GB egress / month for imagery
tiles. For a development/internal project this is plenty; for a publicly
deployed tool with traction, it's exhaustable.

**Mitigation.**
- v1 ships on ion; no premature optimization.
- Self-hosted tile path designed-for ([decisions.md](./decisions.md)
  deferred list).

**Trigger.** Monthly usage past 3 GB → start the self-host plan.

---

## R6 — GLTF spacecraft model sourcing (Medium impact, Medium likelihood)

**Description.** Quality 3D models of spacecraft (ISS, common satellite
buses, generic chasers) aren't trivially free; licensing-clean ones with
articulation rigs are rarer still.

**Mitigation.**
- Start with generic placeholder primitives (boxes, cylinders) and a
  "spacecraft" billboard for v1.
- NASA's 3D resources page has some free models; check licensing per
  model.
- Open the GLTF asset pipeline (Decision 4 deferred) early enough to
  test with primitives first; swap to detailed models when available.

**Trigger.** Phase 6 work blocked on art assets → fall back to primitives
+ ship.

---

## R7 — Full catalog rendering performance (Medium impact, Low likelihood)

**Description.** Rendering ~14,500 `PointPrimitive` dots fed by a CZML
stream + a scenario-layer overlay needs to stay at 30 fps on mid-range
hardware (SRS §5.1.2). Cesium handles 15k points in benchmarks, but
combining with day/night/atmosphere shaders + a CZML interpolation
workload could surface bottlenecks.

**Mitigation.**
- Profile early — Phase 2's "catalog stream + render" milestone includes
  an FPS check.
- Have an LOD (level-of-detail) fallback designed: at low zoom, render
  only the constellations matching active filters; full catalog only when
  zoomed in.

**Trigger.** FPS drops below 30 with a hot catalog.

---

## R8 — CZML chunk sizing trade-offs (Low impact, Medium likelihood)

**Description.** CZML chunks for 14,500 satellites need careful sizing.
Too small → many round-trips, choppy interpolation. Too large → high
peak bandwidth, slow first-render. The sweet spot is empirical.

**Mitigation.**
- Decision deferred (`decisions.md` deferred list); pick by measurement
  when Phase 2 lands.
- Make chunk window and sample density configurable.

**Trigger.** Phase 2 perf review.

---

## R9 — Enterprise shell underestimated (Medium impact, Medium likelihood — *only if* the project goes professional)

**Description.** Phase 10 (real OIDC/SAML, RBAC enforcement, on-prem
packaging, validation suite, TLS, secrets, audit UI) is a chunky phase
on its own. Underestimating means a "we're shipping next month" delay.

**Mitigation.**
- Seams are real from day one ([Decision 16](./decisions.md)) — the
  conversion is *additive*, not a rewrite.
- Treat Phase 10 as its own deliverable with its own estimate, not a
  cleanup pass at the end.

**Trigger.** Stakeholder commitment to "professional" → re-baseline
Phase 10 estimate.

---

## R10 — Java + frontend dev split (Low impact, lifetime certainty for solo)

**Description.** The stack now spans two distinct ecosystems (Java/Spring
backend + TypeScript/React/Vite frontend) plus Postgres + Docker. For a
solo developer this is real context-switching cost.

**Mitigation.**
- Keep the backend boring (Spring conventions, no exotic patterns).
- Generate the frontend client from the backend's OpenAPI spec ([US-INFRA-03](./user-stories.md)) so contract changes propagate automatically.
- One feature at a time end-to-end, not parallel half-finished work in
  both layers.

**Trigger.** Any "fix this on both sides" change taking >1× expected effort
→ check the OpenAPI generation flow.

---

## R11 — Determinism violations (High impact for reproducibility, Low likelihood)

**Description.** SRS §5.4.1 requires bit-identical propagation on the
same platform version. Sources of non-determinism (unseeded RNG,
parallel reductions with nondeterministic order, system time use,
floating-point flag changes) creep in.

**Mitigation.**
- All randomness seeded from a configured seed; never `new Random()`
  without an explicit seed.
- Avoid parallel-reduction-based math in propagation hot paths until
  ordering is controlled.
- Pin Orekit settings (gravity-field degree, integrator tolerances) in
  the scenario body — never read from process environment.
- Reproducibility test in CI: run the same scenario twice, byte-compare
  results.

**Trigger.** CI reproducibility test fails.

---

## R12 — Streaming contract version skew (Medium impact, Medium likelihood)

**Description.** Frontend and backend evolve at different rates. A
contract change on one side that the other hasn't caught up to causes
runtime errors mid-session.

**Mitigation.**
- Every WebSocket message includes a contract version.
- Backend rejects connections from incompatible client versions with a
  clear error.
- OpenAPI-generated client ([US-INFRA-03](./user-stories.md)) catches REST
  drift at build time.

**Trigger.** Any runtime failure during stream handshake.

---

## R13 — Monte Carlo compute cost (Medium impact, Medium likelihood, only at Phase 9)

**Description.** 500-sample Monte Carlo with high-fidelity propagation
over a 24-hour scenario can be expensive — single-threaded, single-user,
this could be tens of minutes.

**Mitigation.**
- Decision deferred ([decisions.md](./decisions.md)) on the execution
  model.
- Architecture allows a worker-pool design; Spring's task executor is
  available.
- UI presents progress and async result delivery.

**Trigger.** Phase 9 perf review.

---

## R14 — Time scale and leap-second handling (Low impact, Low likelihood)

**Description.** Mixing UTC and TAI or forgetting leap seconds causes
silent ~30-second errors in long propagations. Tempting to use raw JS
`Date` (which has no leap-second concept).

**Mitigation.**
- All time on the backend uses Orekit's `AbsoluteDate` + time-scale
  factories.
- Frontend `Date` → backend `AbsoluteDate` conversion happens at the API
  boundary, never in the middle of computation.
- Time-scale-tagging convention: any time variable's name or comment
  notes its scale.

**Trigger.** Any ~30 s anomaly in propagation results.

---

## R15 — Frame-tag discipline lapses (Medium impact, Medium likelihood)

**Description.** Decision 12 requires every state vector to carry its
frame tag. Discipline lapses (raw `Vector3` passing through code without a
frame) re-introduce the bug class the design tried to eliminate.

**Mitigation.**
- Type-system enforcement: a `StateVector` value type that bundles the
  frame, not a bare tuple of doubles.
- Code review focuses on frame-tag presence at every interface.
- Frame mismatches at runtime fail loudly (assertion / exception), not
  silently.

**Trigger.** Any code that introduces a raw position/velocity tuple
without a frame field.

---

## Watch list (not yet risks)

Things to keep an eye on; promote to a numbered risk if they grow:

- **Cesium ↔ three.js sync drift** — both views reading the same clock
  slice should be enough, but quality of synchronization at 60 fps merits
  measurement.
- **Browser bundle size** — Cesium ~3 MB + three.js ~600 KB + app code.
  Acceptable for a tool of this kind, but watch first-load time.
- **TLE source reliability** — CelesTrak occasionally has outages.
  Cache locally for resilience (deferred to ops).
- **Docker on the shared GPU server** — installation pattern (rootless?
  daemon?) hasn't been picked yet; revisit at Phase 1 start.
