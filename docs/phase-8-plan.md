# Phase 8 — Environment & Events (plan)

The **HOW** for Phase 8 (roadmap §7; SRS §3.7 / §3.11.3 / §3.12.1 / §3.12.3;
US-ENV-01/02/03, US-EVT-02/03/04; UC-5, UC-7). The **WHY** will be
[Decision 25](./decisions.md) (to be written with the implementation).
Status: **planned — not yet started.** Phase 7 complete (113 backend tests green).

## What it adds

Phase 8 makes the scene *environmental*. Phase 7 left two explicit holes (Decision 24,
risk [R17](./risks.md)): the proximity view's lighting is **flat / non-physical** (no Sun
vector), and sensor **sun-keep-out** + Sun occlusion were deferred "to Phase 8 with the
Sun vector." Phase 8 closes those and adds the rest of roadmap §7: Sun/Moon positions,
per-spacecraft eclipse (umbra/penumbra), Sun-consistent illumination, conjunction
detection, constraint checks (sun-keep-out + approach corridor), and the timeline event
annotations that have been accumulating (eclipse bands, conjunction ticks, violation
markers) — Frank's UC-5 (eclipse/lighting) and UC-7 (conjunction screening), and the rest
of Gita's UC-4 (sun-keep-out).

It rides the Phase-7 architecture almost exactly: new sampled-trajectory computers in the
`analysis/` package (no re-propagation), additive `scenario-relative` envelope fields
(`VERSION` stays `"1"`), a forward-additive `ScenarioBody` schema bump (no DB migration),
and all scenario edits through the single audited `ScenarioService`. The Sun/Moon
ephemeris is **already wired** — Orekit `CelestialBodyFactory.getSun()/getMoon()` drive
third-body + SRP in [`NumericalPropagation`](../backend/src/main/java/space/orbit/backend/prop/NumericalPropagation.java)
— so Phase 8 reuses it rather than adding anything.

## Decisions taken (Decision 25)

- **Sun/Moon:** sample each body's **direction in the chief-LVLH scene** on the render
  grid and stream it (additive). Drives a real DirectionalLight in the proximity view.
- **Eclipse:** conical umbra + penumbra, computed in **geocentric ECI** from sample arrays
  captured for free in the existing per-step loop (no re-propagation), mirroring
  `SensorEventComputer`. Earth-shadow only (lunar eclipse of a satellite is negligible).
- **Conjunctions: both** — **intra-scenario** pairwise closest-approach in the live stream
  (cheap, formation-safety) **and catalog screening** (UC-7) as a separate request→response
  REST endpoint (screens scenario craft vs the full SGP4 catalog; one-shot sorted list, not
  playback).
- **Constraints: sun-keep-out + approach corridor.** Plume impingement deferred (needs
  per-burn plume geometry).

## Invariants to preserve

- **R12 — additive contract.** Every proximity-stream addition is a new optional field in
  the one `scenario-relative` envelope; `StreamContract.VERSION` stays `"1"`; encoder
  helpers omit empty fields so older clients ignore unknowns. New REST (constraints,
  screening) is pure addition → `gen:api` regenerates; stream fields stay WebSocket-only.
- **R11 — determinism.** Fixed iteration counts (bisection 24, golden-section 60 — reuse
  the existing constants), ordered grid scans, no RNG/wall-clock; the ephemeris is
  deterministic. New sampling passes run in **increasing time order** so the live
  propagators are never queried out of order (the maneuvered-deputy hazard, Decision 24).
  **Caveat:** 8C catalog screening is *not* byte-identical across catalog refreshes — it
  screens the live catalog (refreshed every 6 h). Each screening result is tagged with the
  catalog refresh epoch; it is a snapshot analysis, not a reproducible scenario artifact.
- **R15 — frame discipline.** Sun/Moon direction, conjunction range, and constraint angles
  live in the **LVLH scene** (use `Transform.transformVector` for directions — never
  `transformPosition`). Eclipse is **geocentric ECI**. A distinct `SampledGeocentricCraft`
  (field `posEci`) vs the existing LVLH `SampledCraft` (field `pos`) enforces the split at
  the type level.
- [Decision 9](./decisions.md) (frontend never propagates), [16](./decisions.md) (single
  audited mutation path), [5](./decisions.md) (ephemeris in stream buffers, read per-frame
  in the rAF loop — never Zustand).

## 8A — Sun/Moon vectors + Sun-consistent lighting + eclipse

Stream-only, fully additive. Lowest risk; de-risks the geocentric-sampling decision before
constraints depend on it. Resolves the R17 lighting item.

**Backend.**
- Sample the Sun (and Moon) **direction in the chief-LVLH scene** on the position grid in
  `ScenarioStreamService.encodeRelative` (after the chief attitude is sampled), via a new
  helper mirroring `sampleAttitude`: per step
  `eci.getTransformTo(lvlh, date).transformVector(sunEci.normalize())`, stored as a
  stride-4 `[t,sx,sy,sz, ...]` unit-vector array. Add a small testable
  `FrameService.directionInLvlh(...)` beside `bodyQuaternionInLvlh`. Keep the un-normalized
  Sun ECI position for eclipse.
- New `analysis/` classes mirroring
  [`SensorEventComputer`](../backend/src/main/java/space/orbit/backend/analysis/SensorEventComputer.java):
  `EclipseEvent(type, noradId, epoch)` (`type ∈ {umbra-ingress, umbra-egress,
  penumbra-ingress, penumbra-egress}`); `SampledGeocentricCraft(noradId, posEci, posStride)`
  (**separate from `SampledCraft`** — positions are geocentric ECI, not LVLH, R15);
  `EclipseEventComputer.compute(crafts, sunEci, firstT, step, steps, epoch, durationSec)`.
  Conical shadow predicate per craft per sample (ECI): anti-sun axis `u = (−S).normalize()`;
  `P·u ≤ 0` → lit; else miss `d = |P − (P·u)u|`, umbra `rU = R_e − s·tan(asin((R_sun−R_e)/|S|))`,
  penumbra `rP = R_e + s·tan(asin((R_sun+R_e)/|S|))` (Orekit `Constants.SUN_RADIUS` /
  `WGS84_EARTH_EQUATORIAL_RADIUS`); classify `d<rU→umbra, d<rP→penumbra, else lit`. Emit on
  each 3-state transition + the same fixed 24-iter bisection refine — on the **interpolated
  samples**, never re-propagating.
- **Geocentric arrays (the key decision):** build per-craft geocentric ECI position arrays
  **transiently inside the existing per-step loop** — the deputy loop already holds the
  deputy ECI state; capture `.getPosition()` for free. Add a chief geocentric pass
  (`chief.provider().getPVCoordinates(date, eci)` — only the scalar `chiefRadiusM` is
  sampled today) in increasing-time order. These feed `EclipseEventComputer` and are **not
  streamed**. (Rejected: reconstructing geocentric from the LVLH samples — needs the chief's
  full time-varying ECI pose per sample, strictly more work re-deriving raw data we have.)
- Widen
  [`RelativeStateEncoder.encodeRelative`](../backend/src/main/java/space/orbit/backend/stream/RelativeStateEncoder.java)
  with additive trailing params (`sunVector`, `moonVector`, `eclipses`) + `write*` helpers
  ("omit when empty", like `writeEvents`). No new message type, no `EncodedScenario` change.

**Frontend.**
- [`relativeBuffer.ts`](../frontend/src/stream/relativeBuffer.ts): `sunVectorAt(t)` /
  `moonVectorAt(t)` (linear interp on the stride-4 array, like `deputyAttitudeAt`); pair
  eclipse ingress/egress into per-craft `EclipseInterval[]`.
- [`ProximityView.tsx`](../frontend/src/views/ProximityView.tsx): replace the fixed
  `keyLight.position.set(1,1,1)` "flat non-physical lighting" rig with the per-frame Sun
  direction from the buffer (read in the existing rAF loop — Decision 5); drop ambient /
  hemisphere toward a low fill. The `MeshStandardMaterial` Earth
  ([`earthBackdrop.ts`](../frontend/src/proximity/earthBackdrop.ts)) then shows a correct
  day/night terminator for free; expose its `emissiveIntensity` so the dark limb dims.
- [`spacecraftModel.ts`](../frontend/src/proximity/spacecraftModel.ts): a `getMaterials()`
  accessor so the rAF loop drops `emissiveIntensity` when `currentTime` is inside a craft's
  umbra/penumbra interval (UC-5 step 4).
- [`Timeline.tsx`](../frontend/src/timeline/Timeline.tsx): eclipse umbra/penumbra bands
  (reuse the AOS/LOS-window band pattern, color-coded).
- [`Globe.tsx`](../frontend/src/components/Globe.tsx): enable Cesium day/night lighting
  (`scene.globe.enableLighting = true`); the built-in terminator satisfies UC-5's
  sun-direction confirmation.

## 8B — Intra-scenario conjunctions + constraints + timeline events

Adds schema v4 + audited mutations + REST for constraint config (US-EVT-02/03/04).

**Backend.**
- `analysis/ConjunctionEvent(aNoradId, bNoradId, tcaEpoch, missDistanceM)` (canonical
  `a<b`) + `ConjunctionEventComputer.compute(crafts, thresholdM, ...)`: every unordered
  craft pair (chief `pos=null` → origin), pairwise **LVLH** range (frame-invariant) on the
  grid; bracket the in-window minimum; if `< thresholdM`, golden-section-refine it —
  reuse the closed-form refine in `ScenarioStreamService.refineTca` (fixed 60 iters) but on
  the **sampled arrays** (interpolate, never re-propagate). No geocentric arrays needed.
- `ScenarioBody` schema **v4**, forward-additive exactly like v2→v3
  ([`ScenarioBody.java`](../backend/src/main/java/space/orbit/backend/scenario/ScenarioBody.java)):
  `Role` gains `List<Constraint> constraints` (null-coalesced; thread through the existing
  `withManeuvers/withSensors/withAttitude` copy helpers + a `withConstraints`); add an
  optional top-level `Double missDistanceThresholdM`; bump `CURRENT_SCHEMA_VERSION = 4`;
  `parse()` re-stamps non-current bodies → **no DB migration**. New record
  `Constraint(id, kind, hostNoradId, sensorId, targetNoradId, limitDeg, rangeM)`,
  `kind ∈ {sun-keep-out, approach-corridor}` (leave room for `plume-impingement`).
- [`ScenarioService`](../backend/src/main/java/space/orbit/backend/scenario/ScenarioService.java)
  gains `addConstraint`/`removeConstraint`/`setMissDistanceThreshold` (each one version +
  one audit row, cloned from `addSensor`/`removeSensor`); new free-VARCHAR audit actions
  `CONSTRAINT_ADD`/`CONSTRAINT_REMOVE`/`MISS_DISTANCE_SET` (no migration). Validation → 422:
  host exists, known `kind`, `limitDeg ∈ (0,180)`, sun-keep-out's `sensorId` on the host,
  corridor's `targetNoradId` exists and ≠ host. `ScenarioController` adds
  `POST/DELETE /scenarios/{id}/constraints`, `PUT /scenarios/{id}/miss-distance` with a
  bean-validated DTO → web-agnostic `ConstraintDraft` (mirrors `SensorDraft`).
- `analysis/ConstraintViolationEvent(type, constraintId, hostId, sensorId, targetId, epoch,
  valueDeg, limitDeg)` + `ConstraintChecker.compute(crafts, sunVector, ...)`:
  **sun-keep-out** reuses `SensorEventComputer.attAt` + `rotateByQuat` (body boresight →
  LVLH), then `angle(boresight, sunDir) < limitDeg` → violation (crossing + 24-iter
  bisection); free given 8A's `sunVector` + Phase-7 attitude/sensors (completes UC-4 step 7).
  **approach-corridor** is the `SensorEventComputer.visible` bearing test with the predicate
  inverted ("outside the corridor cone while within `rangeM` of the chief").
- Extend the encoder signature further (`conjunctions`, `violations`) + `write*` helpers;
  thread the threshold from the body into `encodeRelative` (null-safe default ~5 km).
  Assemble the `SampledCraft` list once for all four computers.

**Frontend.**
- [`relativeBuffer.ts`](../frontend/src/stream/relativeBuffer.ts): parse `conjunctions` +
  `constraintViolations`.
- [`Timeline.tsx`](../frontend/src/timeline/Timeline.tsx): conjunction TCA ticks + violation
  markers (reuse the TCA-tick pattern, color-keyed).
- New `scenario/EnvironmentPanel.tsx` (cloned from `SensorPanel`/`ManeuverPanel`, on the
  audited store actions): add/remove constraints (sun-keep-out / approach-corridor, with
  presets), set the conjunction miss-distance threshold, eclipse/Sun-vector display toggles.
  Mounted in `App.tsx` when a scenario is active; reuse `useCollapsed`/`usePanelTab`.
- `gen:api` regenerated for the new constraint REST endpoints.

## 8C — Catalog conjunction screening (UC-7)

A separate request→response REST analysis endpoint (not the stream — it produces a
one-shot list, not playback).

**Backend.** `POST /scenarios/{id}/screening?thresholdKm=…` → `analysis/ScreeningService`:
(1) rebuild the scenario roles (reuse `bodyForStream` + `PropagationService`) and propagate
over the window; (2) get the live SGP4 set from `CatalogService` (add a `tracked()`
accessor; parallelize like `buildCatalogMessage`'s `parallelStream`); (3) **two-stage
prune** to keep ~14,500 tractable — coarse apogee/perigee-shell + along-track time-window
filter, then fine sampled closest-approach + golden-section refine on survivors; (4) return
a sorted `List<ConjunctionResult>(scenarioNoradId, catalogNoradId, name, tcaEpoch,
missDistanceM)`, tagged with the catalog refresh epoch (R11 caveat above). Optionally
persist a summary to the audit log per UC-7 step 5.

**Frontend.** A "Screen against catalog" action (in `EnvironmentPanel` or the scenario
panel): threshold input + an async REST call (spinner/progress) → a **sortable results
table** (closest first; scenario craft, third-party, time, miss distance), click-row to
scrub the timeline to the TCA (highlight the third-party in the global view), and **CSV
export** (UC-7 step 5). Typed via the regenerated OpenAPI client.

## Verification (targets)

- Backend `./gradlew test` green (113 → ~130+). New synthetic-sampled-trajectory unit
  tests (no Orekit), each with a determinism rerun, mirroring `SensorEventComputerTests`:
  - `EclipseEventComputerTests` — sunlit→penumbra→umbra→out sequence; always-sunlit → no
    events; determinism.
  - `ConjunctionEventComputerTests` — V-shaped range: below-threshold → one event at the
    vertex, above → none; `a<b`; refined miss ≤ coarse grid min; determinism.
  - `ConstraintCheckerTests` — sun-keep-out ingress/egress as the Sun sweeps into the cone;
    no violation when the Sun is behind the boresight; corridor exit/return; determinism.
  - `ScenarioServiceTests` — constraint add/remove writes a v4 body + exactly one audit row;
    422 for unknown host/sensor + bad angle; "editing a maneuver preserves constraints."
  - `RelativeStateEncoderTests` / `ScenarioStreamServiceTests` — new additive fields present
    + `contractVersion == "1"`; Sun-vector samples unit-norm; eclipse over a ≥1-orbit window;
    **extend `encodingIsBitIdenticalOnRerun`** to include the new fields. Update existing
    `encodeRelative` call sites for the widened signature.
  - 8C `ScreeningServiceTests` — a craft on a known close pass with a planted catalog TLE is
    found; pruning drops far shells; results sorted ascending.
- Frontend `npm run type-check` + `npm run build` green; `npm run gen:api` regenerated
  (constraint + screening REST; stream fields stay WebSocket-only).
- Dev stack (`docker compose up -d --build`): load a scenario → the WS frame carries
  `sunVector`/`moonVector`, the DirectionalLight tracks the Sun, the Earth shows a
  terminator; a ≥1-orbit scenario → eclipse bands on the timeline + spacecraft darken in
  umbra (UC-5); add a sun-keep-out constraint → 200 (in the v4 body), a violating attitude →
  a violation event + marker, a bad angle → 422; two deputies on a close pass with the
  threshold set → an intra-scenario conjunction tick; "Screen against catalog" at e.g. 5 km
  → a sorted results table, click a row scrubs to the TCA, CSV exports.

## Future improvements (deferred)

Plume impingement (needs per-burn thrust-plume geometry coupled to the maneuvering deputy's
attitude — leave `kind="plume-impingement"` room in the v4 record); gimbaled sensors +
frustum/polygonal FOV (still, from Phase 7); CCSDS AEM **measured** attitude (orientation
stays *modeled*, R17); Monte-Carlo dispersion / covariance ellipsoids / link-budget &
SNR overlays (Phase 9); GPU-depth occlusion of the drawn FOV volume; eclipse/conjunction
annotations on the **global-view** CZML (Phase 8 emits events in the relative envelope; the
global-view timeline wiring can follow).

## Docs to update alongside the implementation

- **Decision 25** in [decisions.md](./decisions.md) (Context / Decision / Why / Alternatives
  / Consequences, Decision-24 style) + the table of contents; resolve the deferred "flat
  lighting" and "Sun occlusion + sun-keep-out (Phase 8)" items.
- [acceptance-criteria.md](./acceptance-criteria.md) — draft the Phase-8 checklist from
  US-ENV/US-EVT + the SRS clauses.
- [risks.md](./risks.md) R17 — lighting now resolved; orientation still modeled-not-measured.
- [architecture-and-roadmap.md](./architecture-and-roadmap.md) §7 — mark Phase 8 status.
- `CLAUDE.md` "Current phase" line.
