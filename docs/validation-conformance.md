# §5.2 Validation & conformance

The **WHAT**: SRS §5.2 accuracy requirements. The **HOW WE MEET IT**: this
document + the tests it maps to. Written for Phase 10 (US-INFRA-05); the
verification source is the SRS §5.2 clauses and Orekit's own upstream validation.

## Posture (why this is defensible offline)

Orekit is the single propagation engine (Decision 7). It is an **upstream-validated**
flight-dynamics library: its SGP4, numerical integrator, gravity/drag/SRP/third-body
force models, and IERS-corrected frames are validated against reference solutions by
the Orekit project itself (including the AIAA 2006-6753 SGP4 verification cases).

Per **R2** (`docs/risks.md`), our conformance obligation is therefore *not* to
re-derive Vallado's or AIAA's numbers — it is to prove **we integrated Orekit
correctly**: the right frames and units, the force model actually engaged and
correctly signed, orbits that stay physically bounded (a wiring bug diverges
immediately), and byte-identical reruns (§5.4.1). That is exactly what the
`validation` + `prop` test suites assert, entirely offline (this environment is
firewalled — CelesTrak is blocked), which is why the **Orekit-reference** approach
was chosen over sourcing external golden vectors (see Decision 28).

**Pinned:** Orekit **13.1.5** (never silently upgraded — R2). Golden behaviour is
stable against this pin; a version bump re-runs this suite as the gate.

## Test → SRS §5.2 → AIAA 2006-6753 mapping

| SRS §5.2 clause | Requirement | Test(s) | What it asserts |
|---|---|---|---|
| §5.2.1 | SGP4/SDP4, AIAA 2006-6753 conformance | `Sgp4PropagationTests`; `ValidationConformanceTest.sgp4HoldsAStableLeoTrackOverAWeek` | OMM→TLE round-trip; a bound, physically-sane LEO track over a week. AIAA conformance **inherited from Orekit** (documented, not re-derived). |
| §5.2.2 | High-fidelity numerical, sub-km / 24 h LEO | `NumericalPropagationTests`; `ValidationConformanceTest.numericalPerturbationsAreEngagedYetTheOrbitStaysBounded` | DP8(7) + gravity(16×16) + NRLMSISE-00 drag + SRP + Sun/Moon actually engaged (24 h track diverges from two-body by ≫10 km) yet stays bounded in the LEO band. |
| §5.2.3 | CW sub-metre / 1 h for < 10 km separation | `CwPropagationTests`; `CwTargetingTest` | Closed-form CW STM: identity at Δt=0, closed bounded relative orbit, in-track drift; targeting lands on the commanded relative state. |
| §5.2.4 | Frame transforms precise to 1e-9 | `FrameServiceTests`; `FrameRelativeTests` | ECI↔ECEF↔geodetic round-trips to sub-millimetre; LVLH/RIC signed-axis convention pinned (radial→+R, in-track→+I, cross-track→+C). |
| §5.4.1 | Bit-identical reruns | `ValidationConformanceTest.numerical24hPropagationIsBitIdentical`; `NumericalPropagationTests.numericalPropagationIsBitIdenticalOnRerun`; `ScenarioStreamServiceTests.*IsBitIdenticalOnRerun` (SGP4, numerical, maneuvered, finite-burn); `MonteCarloServiceTests.sameSeedIsByteIdentical` | Same inputs + pinned settings + seeded RNG → byte-identical output, independent of thread/pool scheduling. |

## Reproducibility (§5.4.1) — how determinism is held

- **Pinned settings, in code** (`PropagationSettings.DEFAULT`) — never read from the
  environment (Decision 20).
- **Frozen inputs** — TLE snapshots (Decision 19) and measured-ephemeris blobs
  (Decision 26) are captured into the scenario, so the run doesn't drift on refresh.
- **No wall-clock, ordered scans** — every `analysis/` pass runs in increasing-time
  order with fixed iteration counts.
- **Seeded RNG** (Monte Carlo, the only randomness) — per-sample `SplittableRandom`
  from `mix(seed, i)` + index-ordered collect (R21), so the cloud is byte-identical
  regardless of pool scheduling.
- The **one documented exception**: catalog conjunction screening (§8C) is a snapshot
  against the live ~6 h-refreshed catalog, tagged with its run instant (R11 caveat).

## What is deferred

- **External AIAA/Vallado golden vectors** (the `SGP4-VER.TLE` / `tcppver.out` set).
  The Orekit-reference approach was chosen (Decision 28) because it is offline and
  matches R2; the external set can be added later as a second SGP4 fixture — it needs
  network sourcing and covers only SGP4.
- A published side-by-side accuracy report vs an independent tool (STK/GMAT) — an
  operational validation activity, out of scope for the automated suite.
