# Phase 6 — Proximity visualization

> Planning artifact, written ahead of implementation (same workflow as
> [phase-4-plan.md](./phase-4-plan.md), [phase-5-plan.md](./phase-5-plan.md)).
> Phase 5 shipped and was audited complete (2026-06-16). Sliced **6A / 6B / 6C**
> to match the project's 4A/4B, 5A/5B/5C cadence. Current status:
> [acceptance-criteria.md](./acceptance-criteria.md) and `CLAUDE.md`.

## Context

Phase 4–5 gave the three.js proximity view ([views/ProximityView.tsx](../frontend/src/views/ProximityView.tsx))
a chief at the LVLH origin and deputies as fixed-pixel `THREE.Points`, animating
in lockstep with the global view off the one shared clock, plus ΔV glyphs and the
relative readout/graph. Phase 6 (roadmap §7; SRS §3.9.3/.5/.8) turns that point
cloud into a real proximity *scene*: spacecraft 3D models with articulable parts
(US-PROX-01/02), past/predicted trajectory ribbons (US-PROX-03), selectable
camera modes (US-PROX-04), and an Earth backdrop for orientation context
(US-PROX-05).

The relative-state stream already buffers the **full past+future sample set** over
the scenario window (± half-orbit margin), so ribbons need no backend work; the
single backend touch is one additive field for the Earth backdrop. This is the
Maya/Omar-facing "see the close-range geometry as it really looks" slice — plus
the honest scaffolding (named joints, a GLTF-swap seam, a derived-orientation
source) that Phase 7 (attitude + sensors) and Phase 8 (sun + lighting) will drive.

Decisions to respect throughout: frontend never propagates (Decision 9); single
authoritative clock, one rAF writer, the render loop only READS `currentTime`
(Decision 11); high-freq ephemeris stays in `relativeBuffer`, out of Zustand
(Decision 5); frame discipline — orientation is a *labeled estimate* from streamed
velocity, not a re-derived frame (R15); the streaming contract stays `VERSION="1"`,
additive only (R12); prefer built-ins, ask before adding deps (none added — `three`
already present; `Line2`/`GLTFLoader` are `three/examples/jsm` modules).

**Confirmed scope decisions (asked + answered during planning):**
- **Earth backdrop** — *correct* Earth: add the chief's geocentric radius as one
  additive field on the `scenario-relative` envelope (the only backend change).
- **Models** — procedural placeholder craft **plus** a `GLTFLoader` path that swaps
  in `/public/models/spacecraft.glb` when present (R6: never blocked on art assets).
- **Articulation** — static *deployed* pose: named joints with a rotation API
  ready, no faked physics (no sun-tracking until Phase 8, no attitude until Phase 7).

Recorded as **Decision 23** (which also resolves the two long-deferred items: the
spacecraft asset pipeline and the Earth-backdrop choice).

---

## Slice 6A — Spacecraft models + orientation (US-PROX-01, US-PROX-02)

Replace the single `THREE.Points` cloud with one `Group` per spacecraft (chief +
each deputy), positioned + oriented each frame. A far-distance LOD keeps the
colored dot so there is **no regression** at 100 km.

- New [proximity/spacecraftModel.ts](../frontend/src/proximity/spacecraftModel.ts)
  — `createSpacecraftModel(color)` returns `{ root, joints{arrayPort,arrayStarboard,dish},
  radius, setMarkerOpacity/Visible, setModelVisible/Scale, dispose }`. Procedural
  parts hierarchy (box bus + two solar arrays on hinge `Group`s + a dish gimbal,
  `MeshStandardMaterial` tinted with the palette slot), parked at a fixed deployed
  pose. A single fixed-pixel `Points` marker child is the far-LOD representation.
  A shared one-shot `GLTFLoader` loads `/models/spacecraft.glb` if present and
  swaps it in; otherwise the primitive stands. `dispose()` frees procedural
  geometries/materials and traverses any loaded GLTF.
- New [proximity/orientation.ts](../frontend/src/proximity/orientation.ts) —
  `deriveBodyQuaternion(out6, hasVelocity, outQuat)` (allocation-free): ram-pointing
  (+Y nose along LVLH velocity, +Z up toward +R) when stride 7; a fixed LVLH pose
  (+Y along +I) when stride 4 or for the chief (origin, no relative velocity). Never
  differentiates position to fake velocity. Drives both the model and the body cameras.
- Edit `ProximityView.tsx`: a shared minimal **light rig** (Ambient + Hemisphere +
  one fixed key light) so the MeshStandard models/Earth are visible; build/replace a
  per-craft model array in `rebuild()`; per frame set deputy position
  (`deputyPositionAt`/`deputyStateAt`) + orientation; LOD marker↔model crossfade by
  apparent on-screen size with a near-plane scale clamp. Legend caveat
  "orientation: estimated".
- **Backend: none.**

## Slice 6B — Trajectory ribbons + camera modes (US-PROX-03, US-PROX-04)

- New [proximity/ribbons.ts](../frontend/src/proximity/ribbons.ts) — `createRibbon(samples,
  stride, color)` → `{ past, predicted, setSplit(t), dispose }`. A depth-tested,
  sliding-window `THREE.Line` trail: a ±WINDOW_SECONDS window around `currentTime`
  (past solid, predicted dashed) selected per frame via `BufferGeometry.setDrawRange`
  (allocation-free; geometry built once), **Catmull-Rom densified** so coarsely-sampled
  long scenarios read as smooth curves, not faceted chords. Plotting the whole
  multi-orbit span is an unreadable spirograph that smears over the Earth (Decision 22's
  windowing lesson); plain depth-tested `THREE.Line` respects the renderer's logarithmic
  depth buffer so the Earth occludes correctly. (Initial `Line2` + `depthTest:false`
  overlay was replaced — it smeared the ephemeris across the planet and z-fought.)
- New [proximity/cameraModes.ts](../frontend/src/proximity/cameraModes.ts) — a
  `CameraRig` over the one OrbitControls. `external` = free orbit around the chief;
  `chief`/`deputy` ride the focus craft's derived body frame (offset kept in body
  space, re-applied each frame; the user's drag/zoom read back through the body
  quaternion after `controls.update()`), with a ~0.4 s eased target transition on
  switch (`performance.now()` — presentation-only, not clock-coupled). Known
  trade-off: a slow roll over a long pass (like Decision 18's ENU follow); switchable
  back to `external`.
- Edit `ProximityView.tsx`: enable the renderer's **logarithmic depth buffer** (the
  scene spans 1 m–100,000 km; a normal depth buffer z-fights — flickering Earth);
  build ribbons in `rebuild()`, `setSplit(t)` per frame; a camera-mode `<select>` +
  Earth/Stars/Off backdrop segmented control in a new `.proximity-controls` overlay
  (React state mirrored to refs the loop reads — no Zustand at 60 fps).
- **Backend: none** (ribbons are pure client-side `samples`).

## Slice 6C — Earth backdrop (US-PROX-05)

- Backend (the one additive change): `stream/ScenarioStreamService.encodeRelative`
  computes the chief's geocentric radius at the epoch and passes it to
  `RelativeStateEncoder.encodeRelative`, which writes `chiefRadiusM`.
  `StreamContract.VERSION` stays `"1"` (R12); determinism unaffected (R11). It is a
  WebSocket-payload field, not a REST/OpenAPI surface — `gen:api` is a no-op.
- Frontend: parse `chiefRadiusM` in `relativeBuffer.ts` (default 0 → representative
  LEO fallback). New [proximity/earthBackdrop.ts](../frontend/src/proximity/earthBackdrop.ts)
  — `createEarthBackdrop()` → `{ root, setChiefRadius(m), setMode('earth'|'stars'|'off'),
  dispose }`: a true-scale single-sphere Earth centered at `(−Rc, 0, 0)` along −R
  (correct limb at LEO, small disc at GEO), plus a deterministic starfield. Procedural
  blue material (no texture asset — firewalled, R6; a real texture is a later drop-in);
  no additive atmosphere shell (it z-fought the surface and read as a flickering blue
  haze — the logarithmic depth buffer keeps the bare limb crisp). Flat non-physical
  lighting (the Sun vector is Phase 8). Earth/Stars/Off toggle (Off = Frank's "pure space").

---

## Verification

- `cd frontend && npm run type-check` green; `npm run build` green (TS strict on). ✅
- `cd backend && ./gradlew test` green (91 tests; the new `chiefRadiusM` assertions
  fold into `RelativeStateEncoderTests` + `ScenarioStreamServiceTests`, the latter
  exercising the real propagator → Earth-scale radius). Reruns stay byte-identical
  (R11) via the existing relative-determinism tests. ✅
- In-browser pass over the dev stack (`docker compose up -d --build`; host
  5174/8081): load a ≥2-deputy scenario and confirm — **6A** models with deployed
  arrays/dish (dots remain at far zoom, model fades in on zoom-in), lockstep
  play/scrub; **6B** solid-past/dashed-predicted ribbons whose split rides the clock,
  constant line width on zoom + after a divider-drag resize, smooth camera-mode
  switching; **6C** Earth limb below at a plausible altitude, Earth/Stars/Off toggle.
  No frontend test framework (consistent with Phases 4–5) — the in-browser pass is
  the remaining manual nicety.

## Critical files

- Edit [frontend/src/views/ProximityView.tsx](../frontend/src/views/ProximityView.tsx)
  — scene/loop/rebuild/dispose host; all five stories wire in here.
- Edit [frontend/src/stream/relativeBuffer.ts](../frontend/src/stream/relativeBuffer.ts)
  — parse the additive `chiefRadiusM` field.
- Edit [frontend/src/App.css](../frontend/src/App.css) — `.proximity-controls` overlay.
- New `frontend/src/proximity/{spacecraftModel,orientation,ribbons,cameraModes,earthBackdrop}.ts`
  — each owns its create/update/dispose lifecycle.
- Edit backend [stream/RelativeStateEncoder.java](../backend/src/main/java/space/orbit/backend/stream/RelativeStateEncoder.java)
  + [stream/ScenarioStreamService.java](../backend/src/main/java/space/orbit/backend/stream/ScenarioStreamService.java)
  (+ the two stream tests) — the `chiefRadiusM` field.

## Risks / pitfalls (carryovers + phase-specific)

- **R6 (model sourcing)** — mitigated by procedural-first + the GLTF-swap seam.
- **Dispose/leak** — `rebuild()` runs on every deputy-set change; each factory returns
  `dispose()`, called from both the rebuild teardown and unmount; GLTF scenes traversed.
- **`LineMaterial.resolution` on resize** — set in the `ResizeObserver` (the divider
  drag fires it constantly; wrong resolution = wrong line width).
- **Scale extremes** — LOD marker floor prevents "invisible at 100 km"; model scale
  clamps when the camera is inside ~1.5× the model radius; near/far stay 0.1 / 2e8.
- **stride-4 (no velocity)** — orientation falls back to the fixed LVLH pose (detected
  via `dep.hasVelocity`).
- **Honesty caveats surfaced in UI** — orientation *estimated* (Phase 7),
  articulation *parked* (Phase 8), Earth lighting *non-physical flat* (Phase 8).
- **Lockstep (Decision 11)** — no Zustand/clock writes from the loop; toggles are refs;
  the camera-switch easing uses `performance.now()` (decoupled from the sim clock).
- **Unprocessable / decaying scenarios handled** — a body that leaves the propagator's
  valid domain (decay, or a maneuver below the surface → Orekit "point is inside
  ellipsoid") previously crashed the whole stream (1011 → reconnect storm, blank view).
  Now `sampleRole`/`encodeRelative` **HOLD** the last valid point per-sample (bailing on
  the first failure) so a body that decays *partway* still loads with its trail ending;
  only a body that is *never* valid in the window (e.g. a degenerate 12 km/s maneuver)
  throws `ScenarioStreamUnprocessableException` → clean 4422 + a logged reason + the
  client's `scenarioStreamError` banner. Long *valid* scenarios load (e.g. 11-day SGP4).
  Pre-existing physics/bad-data, surfaced by Phase 6's blank view.

## Future improvements / follow-ups

Captured while building + validating Phase 6 (see also risks R16–R18). Not in scope for
the phase; listed so they're not lost.

1. **Rendezvous differential corrector** (proper fix for R16). The Lambert template is an
   open-loop two-body *sketch*: it plans in two-body but executes with SGP4 (chief) +
   numerical (deputy), so it misses by tens of km. Iterate the two burns against the
   *real* propagators (finite-difference Newton on Δv1 vs the arrival miss, fixed
   iterations for R11 determinism) so the closest approach converges to ~0. This is the
   single biggest credibility win for the maneuver feature.
2. **Robust Lambert solver.** Replace/augment Orekit `IodLambert` with a min-ΔV branch
   search (short-way vs long-way × multi-revolution) to remove the wild-ΔV spikes at
   certain arrival times (currently only *flagged* by the ≥5 km/s warning).
3. **Real GLTF spacecraft models** (R6). Drop licensing-clean `.glb` into
   `/public/models/` via the existing `GLTFLoader` swap seam; map the model's named
   nodes to the articulation joints (`arrayPort`/`arrayStarboard`/`dish`).
4. **Phase 7 attitude.** Replace the derived ram/LVLH orientation estimate with real
   attitude profiles; drive the articulation joints; add body/sensor frames. The
   orientation seam (`deriveBodyQuaternion`, `FrameService.body`) is the plug-in point.
5. **Phase 8 lighting.** Real sun vector → Earth terminator / day-night shading,
   physically-consistent spacecraft illumination, eclipse — replacing the flat
   non-physical light rig.
6. **Ribbon polish.** Optional fade-to-transparent at the window edge (comet-tail) in
   place of the current hard cut; tune `WINDOW_SECONDS` / the Catmull-Rom subdivision per
   feedback.
7. **Numerical-cost guardrail** (R18). Warn or cap very long numerical windows, and/or
   stream with progress for heavy loads, so a long high-fidelity scenario can't read as a
   hang (the ≤5 s/24 h target is for a 24 h window).
