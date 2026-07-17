# TELEOS-2 measured-data demo suite (presenter guide)

Six scenarios that demo the platform's capabilities against **real flown
telemetry** — the TELEOS-2 WOD dataset (1–7 Jan 2026, 55,744 GNSS/ADCS states) —
**plus a synthetic collision-avoidance conjunction** (#7, US-MAN-12) — all owned by a
dedicated **`demo`** account so a presentation starts from a clean scenario list. Built
(re-runnably) by [scripts/seed-teleos-demos.sh](../scripts/seed-teleos-demos.sh); the
measured-data mechanics are [Decision 26](./decisions.md) /
[measured-data-plan.md](./measured-data-plan.md), the avoidance maneuver is
[Decision 30](./decisions.md#30-collision-avoidance-maneuver-the-inverse-of-rendezvous-us-man-12).
The `demo` account is the home for curated feature demos (per CLAUDE.md "Demos"): add new
ones to the seed script, not the per-user onboarding seeder.

> **Status: built & verified on the dev OIDC stack (2026-07-08).** Backend 224
> tests green (two backend changes landed here: Monte Carlo on a measured chief,
> and a maneuvered-trajectory stream-consistency fix — see the follow-ups below;
> six new tests between them, including an OEM burn-at-t0 regression guard). Verified end-to-end: corrector converged
> (#3 miss 0.12 m / #6 0.20 m), the OEM exports carry the full rendezvous
> (global min ≈ 0.1 m), the rendered stream now closes to **0 m refined / ≤1 m
> on the sample grid** (table, graph and 3-D agree with the audit trail), and a
> WebSocket probe of #5 confirmed 6 sensor acquisitions + 8 sun-keep-out
> violations + the SNR series on the real attitude. The browser click-through
> items at the end are the remaining manual pass.

## Setup

Everything below assumes the OIDC overlay stack (the demo account is an IdP
login, so plain stub mode won't show these scenarios):

```
docker compose -f docker-compose.yml -f docker-compose.oidc.yml up -d --build
```

1. **`.env`** needs `DEMO_PASSWORD` (dev convention: `demo`, like `maya`/`maya`).
   See `.env.example`.
2. **Keycloak realm** — `deploy/keycloak/orbit-realm.json` now carries the
   `demo` user (roles `mission_planner` + `flight_dynamics_engineer`) and an
   `orbit-cli` client (dev-only, direct-access-grants) the seed script uses to
   mint tokens. A **running** Keycloak imported the realm before these entries
   existed; either recreate it (`docker compose -f docker-compose.yml -f
   docker-compose.oidc.yml up -d --force-recreate keycloak keycloak-init` —
   wipes console-made users) or add them live via kcadm:

   ```
   docker compose exec keycloak /opt/keycloak/bin/kcadm.sh config credentials \
     --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" \
     --password "$KC_BOOTSTRAP_ADMIN_PASSWORD"
   ... kcadm.sh create users -r orbit -s username=demo -s enabled=true \
     -s email=demo@orbit.local -s emailVerified=true -s firstName=Demo -s lastName=Presenter
   ... kcadm.sh add-roles -r orbit --uusername demo \
     --rolename mission_planner --rolename flight_dynamics_engineer
   ... kcadm.sh set-password -r orbit --username demo --new-password "$DEMO_PASSWORD"
   ... kcadm.sh create clients -r orbit -s clientId=orbit-cli -s publicClient=true \
     -s directAccessGrantsEnabled=true -s standardFlowEnabled=false -s enabled=true
   ```
3. **Build the suite**: `./scripts/seed-teleos-demos.sh`. Idempotent — existing
   demo scenarios are skipped; `FORCE=1` archives and rebuilds them. In OIDC mode
   it builds natively as `demo`; against a stub-mode stack it builds as the dev
   user and reassigns ownership via SQL. It also archives the five synthetic
   `Demo — *` scenarios from the demo account so the list holds only this suite.
4. **Present**: browse `https://174.75.16.25:8443/` (self-signed cert — accept
   the one-time warning), sign in as **`demo` / `demo`**. Seven scenarios — the six
   TELEOS-2 measured scenarios (top to bottom = the demo arc) + the synthetic
   collision-avoidance demo (#7).

## The suite at a glance

| # | Scenario | Deputy | Shows |
|---|---|---|---|
| 1 | flown week (measured orbit & attitude) | — | measured import, real ADCS attitude, eclipse, lighting |
| 2 | co-launch neighbourhood | LUMELITE-4 + POEM-2 (real TLEs) | conjunctions, distance chart, catalog screening |
| 3 | inspector rendezvous | INSPECTOR-1 (synthetic, ~100 km behind) | templates, differential corrector, ΔV budget, OEM export |
| 4 | close-range ops (V-bar approach) | INSPECTOR-2 (synthetic, ~5 km behind) | CW hold/glideslope templates |
| 5 | inspection sensor & link budget | INSPECTOR-3 (synthetic, ~10 km behind) | FOV on real attitude, AOS/LOS, SNR, sun-keep-out |
| 6 | approach dispersion (Monte Carlo) | INSPECTOR-4 (synthetic, ~30 km behind) | seeded Monte Carlo + covariance around measured truth |
| 7 | collision avoidance (conjunction) | DEMO INTRUDER (synthetic, single close pass) | **avoidance maneuver** (US-MAN-12) — cross-track/radial/in-track ΔV to open a flagged miss |

**Why synthetic inspectors?** By Jan 2026 vs today's catalog, near-equatorial
RAAN drift (~9.5°/day at TELEOS-2's i ≈ 10°) has scattered every real object's
plane 9–19° away from the measured truth — the co-launch craft included — so a
real-TLE chaser would need km/s-class cross-plane ΔV (R19: back-propagated TLEs
are illustrative). An inspector *launched into the chief's plane* is what a real
RPO mission would fly, and synthetic frozen-TLE craft are exactly how the five
seeded `Demo — *` scenarios work (NORAD 99001+; these use 99101–99104). The
inspectors are derived from the measured chief's own state and placed trailing
on the V-bar; scenario #2 keeps the honest real-catalog geometry.

## Walkthrough, scenario by scenario

### 1 — TELEOS-2 — flown week (measured orbit & attitude)

*The opener: "everything you see here was flown — this is not a simulation of
TELEOS-2, it's TELEOS-2."*

- Load it. The globe plays the real orbit; the proximity view shows the craft at
  the LVLH origin flying its **measured ADCS attitude** (legend reads
  *measured*). Toggle **Body axes** — the triad wobbles and slews in ways an
  idealized LVLH-pointing model never would; around imaging passes you can see
  deliberate slews.
- Timeline: **eclipse bands** (umbra/penumbra) recur each ~96-min orbit —
  computed from the real trajectory. The Earth backdrop's terminator and the
  spacecraft lighting follow the same streamed Sun vector (craft visibly dim in
  umbra).
- Scrub anywhere in the six days; playback at 100×–1000× reads well. The ⏱
  PerfHud (or `?perf=1`) shows the §5.1 targets live if asked about performance.

**Purpose.** Establish credibility before anything else: everything that follows
is anchored to *flown* data, not a made-up orbit. Shows: measured import
(Decision 26), real attitude, eclipse/lighting environment, smooth playback.

**Demo flow.**
1. Load the scenario; let it play at 100× for a few seconds — the globe flies
   the real orbit, the proximity view holds TELEOS-2 at the LVLH origin.
2. Turn **Body axes** on. Point at the triad: *"this orientation is telemetry —
   55,744 GNSS/ADCS states from the spacecraft, one week in January."*
3. Point at the timeline's eclipse bands; scrub into an umbra band — the craft
   dims and the Earth terminator matches.
4. Scrub freely across the six days (instant — the trajectory is precomputed
   server-side and streamed; the client never propagates).

### 2 — TELEOS-2 — co-launch neighbourhood (LUMELITE-4 & POEM-2)

*Real neighbours, real distances: formation monitoring + conjunction screening.*

- The two PSLV-C55 co-launch companions ride their **current catalog TLEs**
  back-propagated into the window — honest but *illustrative* geometry (R19).
  The 24 h window is centered on the LUMELITE-4 closest approach (~113 km when
  built; the one suite number that drifts as the catalog refreshes — the script
  re-discovers it per run).
- Relative readout → **Graph** tab: the distance-vs-time curves; the closest
  approach is annotated (table shows the refined TCA).
- The scenario's conjunction threshold is preset to **100 km**, so the timeline
  carries conjunction ticks for the flyby.

**Purpose.** Situational awareness around the real craft: multi-craft relative
monitoring, conjunction detection with a threshold, and live full-catalog
screening (Frank's UC-7). Also the one scenario with *real* deputies.

**Demo flow.**
1. Load it. Two deputies appear in the proximity view; the window is a day
   around the LUMELITE-4 flyby.
2. Open the relative readout → **Graph** tab: per-deputy range curves; point at
   the annotated closest approach (~113 km) and the conjunction tick on the
   timeline.
3. **Live analysis** — Environment panel → *Screen against catalog*, threshold
   100 km: ~15.9k objects screened in a few seconds; the top rows are Starlink
   crossings a few km from the real trajectory. Click a row — the clock scrubs
   to that TCA and the third-party satellite highlights on the globe.
4. Export the screening table as CSV (the deliverable Frank hands to the team).
5. Honesty beat if asked: the deputies here are current TLEs back-propagated
   into January — the geometry is illustrative (R19); the screening itself is a
   snapshot against the live catalog, refreshed every ~6 h.

### 3 — TELEOS-2 — inspector rendezvous (corrected two-impulse)

*The RPO headline: a chaser arrives at the real flown trajectory, closed-loop.*

- INSPECTOR-1 trails ~100 km on the V-bar. The maneuver plan holds a
  **corrected two-impulse rendezvous**: the differential corrector iterated the
  burns against the real propagators — the chief here being the **measured
  ephemeris itself** — until the arrival miss collapsed. As built: **Δv total
  29.66 m/s, arrival miss 0.12 m** (the Audit panel's `MANEUVER_TEMPLATE`
  entry), and the OEM export independently re-executes the plan to a ~0.1 m
  closest approach.
- Play the approach: ΔV glyphs at both burn epochs, cumulative budget in the
  maneuver panel (~30 m/s — a realistic inspector budget), distance chart
  collapsing from 100 km to ~zero at the ~01:56Z arrival. The on-screen closest
  approach, the audit entry, and the OEM export all agree at sub-metre.

**Purpose.** The RPO headline: trajectory *design* that closes the loop against
measured truth — template planning, the differential corrector, ΔV budgeting,
governance (audit trail), and the CCSDS OEM handoff (UC-1/UC-3/UC-8).

**Demo flow.**
1. Load it and open the Maneuvers panel: two pre-planned burns, Σ 30.67 m/s.
2. Play from the start at ~50×: departure burn glyph at 00:02, the transfer
   arc, braking burn at 01:56, range collapsing to ~zero (watch the distance
   graph or the readout).
3. Open the **Audit** panel: the `MANEUVER_TEMPLATE` entry reads *"Corrected
   rendezvous (Δv total 29.66 m/s, arrival miss 0.12 m)"* — the corrector
   iterated the burns against the real propagators, chief = the measured
   ephemeris itself. That 12 cm is the money quote — the on-screen view and
   the OEM export both confirm it.
4. **Live (optional)**: in the maneuver panel's *Rendezvous → arrival*, click
   **Find** — it runs the arrival×revolution ΔV search and fills the cheapest
   arrival; this is how the plan was picked. **Stop there — don't click
   Insert** in this scenario (it would stack a second plan on the first; if it
   happens, delete the extra burns with ✕).
5. Export panel → **CCSDS OEM**: downloads the propagated ephemerides (chief
   clipped to its data span, deputy carrying the post-burn numerical
   trajectory); the Audit panel now shows `EXPORT_OEM` — exports are audited.

### 4 — TELEOS-2 — close-range ops (V-bar approach)

*Close-range CW toolbox in its validity regime.*

- INSPECTOR-2 trails ~5 km in-track with a few-km radial/cross-track breathing
  (the synthetic TLE's mean-element residual — total range ~8–14 km, at the
  edge of the CW envelope). The plan parks it at a **−2 km V-bar hold** ~40 min
  in — as built **Σ|ΔV| ≈ 27 m/s** (if a rebuild ever falls back, the script
  swaps in a four-segment glideslope −5 km → −500 m and its log says so).
- Watch in the proximity view at 10–60× with trajectory ribbons on: the transfer
  arc, the burns, then station on the V-bar.

**Purpose.** Maya's close-range toolbox: the CW templates (hold here;
NMC / glideslope / station-keep available live) planning short-range geometry
around the real craft — the "sketch an approach in under two minutes" story.

**Demo flow.**
1. Load it; zoom the proximity view in (this is the close-range scenario —
   scale is km, not tens of km).
2. Play at 10–60× with ribbons on: the inspector leaves its trailing drift,
   flies the two-burn CW transfer, and parks **2 km behind on the V-bar** at
   00:42 — then holds.
3. Maneuvers panel: point at the two burns and the Σ|ΔV| ≈ 30 m/s budget.
4. **Live (this is the sandbox scenario — editing it is fine)**: insert an
   **NMC fly-around** or a **station-keep** from the Close-range (CW) template
   section and reload the stream to watch it. Two honest caveats to narrate:
   templates all plan from the *scenario start* (composability is a tracked
   deferred item in `decisions.md`), and CW is a linearized model — the UI
   banners when a scenario stretches its ~10 km validity envelope. Rebuild the
   pristine version any time with `FORCE=1 ./scripts/seed-teleos-demos.sh`.

### 5 — TELEOS-2 — inspection sensor & link budget (real attitude)

*Gita's UC-4 on real pointing: when can my imager actually see the target?*

- The chief carries an **Inspection imager** (60° cone, ≤200 km) with an RF
  **link budget** (S-band-ish 2.25 GHz, 6 dB threshold) and a **25°
  sun-keep-out** constraint — all attached to the *measured* attitude, so the
  FOV cone sweeps as the real spacecraft slewed.
- Timeline: **AOS/LOS bands** as INSPECTOR-3 (~10 km trailing) enters/leaves the
  cone — driven by telemetry attitude, not a pointing model; **SNR band** from
  the link budget (red under threshold); **violation marks** where the boresight
  came within 25° of the Sun. As built (verified from the stream): **6
  acquisitions** across the 6 h window (~one sweep per orbit) and **8
  sun-keep-out violations**.
- Proximity view: FOV volume rendered translucent; switch the camera to
  **sensor** mode to look along the boresight. The default body +Z boresight
  works with this dataset; if a different cut is wanted, re-run with
  `FORCE=1 SENSOR_BORESIGHT=<x,y,z>` (which body axis points where is
  telemetry, not something we chose).

**Purpose.** Gita's UC-4 on real pointing: sensor coverage, link viability and
sun-keep-out are only as good as the attitude driving them — and here the
attitude is telemetry, not a pointing model.

**Demo flow.**
1. Load it; the imager's translucent cone hangs off TELEOS-2 (adjust FOV
   opacity in the view controls if needed).
2. Play at ~50×: the cone sweeps as the real spacecraft slews; INSPECTOR-3
   (~10 km trailing) passes in and out of it. Talk track: *"when could this
   imager actually have seen the target? Not when a model says — when the real
   attitude says."*
3. Timeline: **AOS/LOS bands** (6 acquisitions across the window, ~one per
   orbit), the **SNR band** from the RF link budget (red below the 6 dB
   threshold), and **sun-keep-out violation marks** (8 of them — boresight
   within 25° of the Sun).
4. Switch the camera to **sensor** mode — you're looking along the boresight;
   scrub to an acquisition and the inspector is in frame.
5. Optional: open the Sensor panel to show the imager + link-budget parameters
   are ordinary editable scenario objects (adding/removing goes through the
   audited path like everything else).

### 6 — TELEOS-2 — approach dispersion (Monte Carlo)

*Frank's UC-6 against measured truth — enabled by this suite's one backend
change (Monte Carlo now resolves the chief through `ChiefStateResolver`).*

- INSPECTOR-4 trails ~30 km with a corrected rendezvous arriving mid-window
  (as built: Δv total 31.73 m/s, arrival miss 0.20 m; the rendered view agrees).

**Purpose.** Frank's UC-6: a plan is only as good as its error envelope.
Nominally this approach arrives 0.2 m from the real TELEOS-2; the Monte Carlo
shows what navigation and burn-execution uncertainty do to that — plus the
platform's reproducibility guarantee. (Enabled by this suite's one backend
change: Monte Carlo now resolves the chief through `ChiefStateResolver`.)

**Demo flow.**
1. Load it and **play the nominal approach first** for context: two burns
   (Σ 31.73 m/s), range 30 km → sub-km at ~02:09. One sentence: *"closed-loop
   corrected against the flown trajectory — nominal miss 0.2 m per the audit
   trail and the OEM export."*
2. Open the **Monte Carlo** panel: deputy INSPECTOR-4, 100–200 samples, pick a
   seed, 1-σ position 100 m / velocity 0.1 m/s, ΔV 2% / pointing 0.5–1°.
3. **Run dispersion.** Every sample perturbs the inspector's initial state and
   burn execution and re-propagates numerically. The overlay shows the
   trajectory cloud + **3σ covariance ellipsoids** strung along the approach —
   around the *real* trajectory. Expect ~15 s at 100 samples, ~a minute at 200
   (each sample is a full numerical propagation; R18).
4. Point at the panel's summary: the max 3σ extent (~15 km with the parameters
   above). The contrast is the demo: *nominal 0.14 m, 3σ envelope kilometres —
   that's why dispersion analysis exists.*
5. Closer: **Run again with the same seed** — the cloud is bit-identical
   (R11/R21). Change the seed — different cloud, same statistics. The run is
   analysis-only (nothing persists; **Clear** removes the overlay), so re-run
   freely.
6. Same caution as #3: don't **Insert** templates here — it would stack burns
   on the planned approach.

### 7 — Demo — collision avoidance (conjunction)

The one **synthetic** scenario (not TELEOS measured data): the inverse of rendezvous
(US-MAN-12, [Decision 30](./decisions.md#30-collision-avoidance-maneuver-the-inverse-of-rendezvous-us-man-12)).
A `DEMO CHIEF` (99001) with a `DEMO INTRUDER` (99005) that drifts up on the same plane and
makes a **single ~1.6 km close pass** ~01:17 into the 3 h window; the miss-distance threshold
is preset to **3 km**, so it loads flagged (conjunction tick ◆ on the timeline).

- **The point:** raise the miss with one small burn while keeping the orbit — "not too high
  or low." Open the **Maneuver** panel → "Collision avoidance": the detected conjunction is
  pre-listed. **Preview** with the default *cross-track* axis (altitude-neutral) → ~3 m/s
  clears the 3 km alert (miss 1.6 → 3.0 km); **Insert** and the timeline tick opens up.
- **Contrast the axes** (Preview each): *in-track* is cheapest (~0.5 m/s) but changes the
  altitude; *radial* ~2 m/s; *cross-track* is the altitude-neutral default. The preview shows
  the ΔV so the trade-off is explicit ([R22](./risks.md)).
- Sandbox scenario — editing/inserting is fine; the burn re-propagates and the miss visibly grows.

## Honesty box (what's real, what isn't)

- **Real:** the chief's trajectory and attitude in every scenario (55,744
  GNSS/ADCS states, served as a tabulated ephemeris — the frontend never
  propagates it); eclipse/lighting/Sun geometry; every event computed from the
  sampled trajectory.
- **Illustrative (R19):** #2's deputies are *current* catalog TLEs
  back-propagated ~6 months, so their in-window geometry (and the ~113 km TCA)
  drifts with every catalog refresh — re-runs of the script re-discover it.
- **Synthetic (by design):** #3–#6's inspectors are hypothetical craft co-planar
  with the measured chief (names say so). Their frozen TLEs live in the scenario
  bodies like any other role; templates, streams and analyses treat them
  normally.
- **CW caveat:** #4's templates solve linearized CW; the build keeps the
  starting range near the ~10 km validity envelope (~8–14 km total range, the
  in-track trail plus the synthetic TLE's radial/cross-track breathing). The UI
  banners if a CW scenario exceeds it.
- **Determinism:** scenario streams and OEM exports are byte-identical per
  version (R11); Monte Carlo is bit-identical per seed (R21); catalog
  *screening* (#2 live step) is a documented snapshot vs the live catalog — the
  one non-reproducible analysis.
- The `ranAt`/`generatedAt` stamps on screening/Monte-Carlo results are run
  metadata, not part of the reproducibility contract.

**Discovered follow-ups (pre-existing, characterized while building this
suite).**

1. **Burn on the OEM grid boundary — INVESTIGATED, not a real defect
   (2026-07-08).** Every maneuver template fires its first burn exactly at the
   scenario start, which is also the OEM export's first grid point — the case
   most at risk of an Orekit `ImpulseManeuver` (a date-detector root) not
   firing on a `propagate()` boundary. **Finding:** with any normal deputy seed
   epoch — i.e. a TLE epoch *before* the window, which every real catalog
   deputy and every demo inspector (seeded 3 h earlier) has — the burn **does**
   fire: the forward propagation from the seed crosses it in the interior, so
   the export follows the maneuvered track (verified ~80 km from the
   un-maneuvered orbit; ~metre-level boundary-timing residual at worst).
   Guarded by `OemExportServiceTests.burnAtTheWindowStartIsExported`. A *full*
   drop only occurs in the degenerate case where the deputy's seed epoch equals
   the burn epoch to the second (the burn sits on the propagator's own seed
   pivot) — not reachable by real scenarios, and not cleanly fixable without
   re-seeding the integrator (which would break stream/OEM/corrector trajectory
   identity), so left as-is. The seed script's 47 s window-start trim is
   therefore a **cosmetic nicety** (it removes the ~metre residual so the
   closest-approach numbers read sub-metre), *not* a workaround for a dropped
   burn. The original "silently drops the departure burn" claim here was
   inaccurate.
2. **Stream vs OEM/corrector maneuver-realization divergence — FIXED
   (2026-07-08).** The stream's rendered maneuvered trajectory disagreed with
   the OEM export's (and the corrector's) by hundreds of metres at arrival —
   the on-screen closest approach for #3/#6 read ~0.4–0.7 km while the audit +
   OEM agreed on ~0.1–0.2 m. **Root cause:** `loadAndEncode` samples each
   role's provider in *two* passes (the CZML pass, then the relative-state
   pass, plus the LVLH frame the relative pass builds from the chief). A
   maneuvered deputy's `NumericalPropagator` is *stateful* across its
   `ImpulseManeuver`: the first pass swept it forward past the burn to the grid
   end; the second pass jumped back to the grid start and re-swept, which
   re-integrates backward across the impulse and realizes it at a slightly
   different effective state — so the relative view saw a corrupted trajectory
   while the CZML view (pass 1) saw the correct one. Confirmed at the unit
   level (a scrambled-order re-sample of the raw propagator diverged ~60 m on
   an ISS test). **Fix:** `PropagationService.stabilizeForRepeatedSampling`
   freezes each numerical provider into an order-independent bounded
   (tabulated) ephemeris over the sampled span before the passes run; analytical
   /tabulated/CW providers are already order-independent and pass through
   untouched. Now every pass samples the identical trajectory (deterministic,
   R11; the bounded ephemeris is also faster — no re-integration). Guarded by
   `PropagationServiceTests.stabilizedManeuveredProviderIsOrderIndependentAcrossTheBurn`
   (scrambled-order re-sample identical to 1e-6 m; matches the forward sweep)
   and `ScenarioStreamServiceTests.maneuveredRelativeRangeMatchesCzmlAbsoluteRange`
   (the two views agree per step to within CZML's whole-metre rounding). The
   rendered closest approach for #3/#6 now reads 0 m refined / ≤1 m on the grid.

## Suggested 15-minute arc

1. (#1, 3 min) "This is real flight data" — attitude triad, eclipse bands.
2. (#2, 3 min) Neighbourhood + live catalog screening → CSV.
3. (#3, 4 min) Rendezvous: search map → corrected plan → play the arrival →
   audit trail → OEM export.
4. (#5, 3 min) Sensor cone on real attitude, AOS/LOS + SNR + sun-keep-out.
5. (#6, 2 min) Monte Carlo cloud + ellipsoids; same-seed determinism as the
   closer. (#4 as an encore if time allows.)

## Remaining manual pass

- [ ] Browser click-through of all seven as `demo` (six TELEOS + the synthetic
      collision-avoidance demo; list shows exactly seven).
- [ ] #1: *measured* legend + body-axis triad wobble visible.
- [ ] #2: conjunction ticks + distance chart + live screening round-trip.
- [ ] #3: ΔV glyphs, budget, distance collapse; OEM download; `EXPORT_OEM`
      audit row.
- [ ] #5: FOV sweeps the target (else iterate `SENSOR_BORESIGHT`); SNR band +
      violation marks render.
- [ ] #6: MC cloud + ellipsoids render; same-seed rerun matches.
