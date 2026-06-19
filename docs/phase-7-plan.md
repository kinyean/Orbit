# Phase 7 — Sensors & FOV (plan + record)

The **HOW** for Phase 7 (roadmap §7; SRS §3.6 / §3.9.4 / §3.12.2; US-SENSE-01..05,
US-EVT-01). The **WHY** is [Decision 24](./decisions.md#24-sensors--fov-modeled-backend-attitude--conerect-fov--sampled-occlusion-aware-events-phase-7).
Status: **complete (7A/7B)** — backend 113 tests green, frontend type-check + build
green, dev-stack verified (2026-06-18).

## What it adds

A **sensor** is a body-fixed camera/RF/lidar on a spacecraft: a FOV shape, a range
band, a boresight. The tool draws the FOV, reports when a target is observable
(acquisition / loss-of-sight), and offers a sensor-frame camera — Gita's UC-4
workflow. Because a FOV only means something relative to where the craft points,
Phase 7 also promotes **orientation** from a Phase-6 frontend estimate to a
**backend-authoritative modeled attitude** (one source of truth for both the drawn
FOV and the computed events — Decision 9).

## Decisions taken (Decision 24)

- **Sensor model:** cone (half-angle) + rectangular (H×V°), body-fixed pointing.
  Gimbal / frustum-polygonal / CCSDS AEM deferred.
- **Attitude:** backend-authoritative, modeled — `lvlh` (LVLH-aligned from the orbital
  state) + optional `fixed` (constant inertial). AEM (measured) deferred.
- **Occlusion / Sun:** Earth-only line-of-sight occlusion in Phase 7; the Sun
  (occlusion + sun-keep-out) is Phase 8.

## 7A — sensor model, modeled attitude, FOV rendering

**Backend.**
- `ScenarioBody` schema **v3**: `Sensor`/`Fov`/`Mount`/`AttitudeProfile` records;
  `Role` gains `sensors` + `attitude` (null-coalesced; `with*` copy helpers so editing
  a maneuver never wipes sensors). `parse()` re-stamps v3 — no DB migration.
- `ScenarioService.addSensor`/`removeSensor`/`setAttitude` (one version + one audit row
  each; `mapRole`/`mapAllRoles`/`withRoleSensors` helpers; validation → 422).
  `ScenarioController` + `SensorDraft`/`AttitudeDraft` + request DTOs.
- `FrameService.bodyQuaternionInLvlh` (+ a reuse overload) builds the body orientation
  in the chief-LVLH scene frame as a **three.js-convention quaternion** (explicit
  basis→quaternion math, pinned by a signed-axis test, R15). This same streamed quaternion
  is what 7B's event detector reads, so events match the drawn FOV.
- `ScenarioStreamService` samples attitude on the position grid (inline for deputies —
  reuses the per-step transform; a small loop for the chief), HOLDing past a domain exit.
  `RelativeStateEncoder` writes a top-level `chief` block + per-deputy `att` + `sensors`
  descriptors. Additive — `VERSION="1"` (R12), determinism intact (R11).

**Frontend.**
- `relativeBuffer.ts`: `DeputyRelative.attitude`/`sensors`, a `chief` block, and
  `deputyAttitudeAt` (SLERP, allocation-free).
- `proximity/sensors.ts`: `createSensorFov` → translucent cone / rect-pyramid volume,
  oriented to the boresight, attached under each craft's `root` (true-metre, rides the
  body frame). `orientation.ts` `bodyOrientationAt` consumes the streamed quaternion,
  falls back to the derived estimate; legend "estimated"→"modeled".
- `ProximityView.tsx`: builds FOV groups per craft, drives orientation from the stream,
  adds Sensors view/opacity controls. `SensorPanel.tsx` (cloned from ManeuverPanel) with
  **type presets** + a per-craft attitude (`lvlh`/`fixed`) toggle, on the audited store
  actions (`addSensor`/`removeSensor`/`setAttitude`). Mounted in `App.tsx`.

## 7B — acquisition/loss events, occlusion, sensor-frame camera

**Backend.** New `analysis/` package: `SensorEventComputer` detects acquisition/loss
per (sensor, target) — in-FOV ∧ in-range ∧ Earth-unobstructed — over the
**already-sampled** position + attitude (a `SampledCraft`), in the chief-LVLH scene
frame, on the sample grid + bisection refine (deterministic). It does NOT re-propagate:
reusing the rendered samples keeps events consistent with the drawn FOV + the closest
approach, and fixes a bug where re-propagating a maneuvered deputy (a stateful Orekit
numerical+`ImpulseManeuver` propagator) out of order gave events that disagreed with the
trajectory by minutes. `SensorEvent`/`SampledCraft` records. `ScenarioStreamService` builds
the `SampledCraft` list from the just-built samples (only when a sensor exists) and emits
an additive top-level `events` array via the encoder. v1 simplifications: circular-cone
FOV test (a rect uses its larger half-angle); Earth-only occlusion.

**Frontend.** `relativeBuffer` parses `events`; `Timeline.tsx` pairs acquisition→loss into
translucent in-view bands; `cameraModes.ts` gains a `sensor` mode (anchor behind the apex,
look along the boresight), wired into the proximity camera `<select>`.

## Verification

- Backend `./gradlew test` — 113 green. New: `FrameRelativeTests` (basis→quaternion
  convention + LVLH/fixed attitude, R15), `ScenarioServiceTests` (sensor/attitude
  mutations + audit + validation + "maneuver edit preserves sensors"),
  `SensorEventComputerTests` (synthetic sampled trajectory: cone acquisition→loss,
  no-sensor→no-events, determinism, narrower-cone-acquires-later),
  `RelativeStateEncoderTests`/`ScenarioStreamServiceTests` (chief block + att + sensors +
  events; `VERSION` still `"1"`; **maneuvered scenario: events deterministic and event
  range == sampled range at each epoch** — the regression guard for the re-propagation bug).
- Frontend `npm run type-check` + `npm run build` green; `npm run gen:api` regenerated.
- Dev stack: add sensor → 200 (sensor in body); bad FOV → 422; the WS `scenario-relative`
  frame carries the chief block + attitude (645 samples) + sensors; a wide cone on the
  NMC-demo chief yields an acquisition of deputy 99002 at range 565 m.

## Future improvements (deferred)

CCSDS AEM measured attitude (a new `AttitudeProfile.mode`); gimbaled pointing (extend
`Mount`); frustum/polygonal FOV (extend `Fov.type`); exact rectangular containment for
events (needs a sensor-frame roll matching the renderer); **Sun occlusion + sun-keep-out
(Phase 8)**; GPU-depth occlusion of the drawn FOV volume; cost bounds for sensor events
over long numerical scenarios (R18).
