# Phase 11 — Polish & ship (plan + live status)

The **HOW** for Phase 11 (roadmap §11; SRS §4.2 / §5.1 / §5.6 / §4.3.3;
US-UX-01..04, US-IO-01/02 + new US-IO-06/07; UC-3/UC-8 export steps). Sliced
**11A / 11B / 11C**. Full design rationale in the approved planning doc
`~/.claude/plans/plan-out-phase-11-zesty-pebble.md`; the **WHY** is Decision 29.
This file is the **resume point**.

Scope decisions taken with the user up front: complete SRS §4.2 by pulling in
**CCSDS OEM export** (§4.2.1) + **events JSON/CSV export** (§4.2.2) alongside the
roadmap's PNG/MP4; MP4 via **WebCodecs + `mp4-muxer`** (dependency approved);
samples seeded **per user on first login**; the live k8s cluster install stays a
Phase-10 follow-up (not Phase 11 scope).

## Status

- **11B — Export (§4.2): ✅ done.** PNG snapshots, deterministic frame-stepped
  MP4 (WebCodecs H.264 + `mp4-muxer`), events JSON/CSV (client-side from the
  stream buffer), CCSDS OEM export (backend, Orekit `OemWriter`, audited as
  `EXPORT_OEM`). Closes the long-deferred "media export §4.2.3" decision.
- **11A — Usability (§5.6): ✅ done.** Per-user demo seeding on first
  provisioning (5 demos, 3 new); Help overlay + first-run hint; tooltip audit —
  every interactive control carries a `title`/`aria-label`.
- **11C — Perf + docs: ✅ done (measurements pending a browser run).**
  `lib/perf.ts` + PerfHud (FPS per view, scrub latency, load timer, §5.1
  thresholds highlighted); OpenAPI info bean + `@Tag`/`@Operation` on all 31
  endpoints; `docs/user-guide.md`; root `README.md` (new — none existed).

Backend `./gradlew test` = **217 green** (was 203 at Phase 10). Frontend
`type-check` + `vite build` green. `gen:api` regenerated twice (OEM endpoint;
then doc-only annotation churn — no type drift). Dev stack verified: rebuilt
backend seeds all 5 demos at startup; rebuilt frontend (image + anon-volume
drop for the new dep) serves `mp4-muxer`; the OEM endpoint is live and
401-gated unauthenticated in oidc mode.

## What landed (file map, for resume)

### 11B — Export (§4.2)

- **NEW** [frontend/src/export/captureRegistry.ts](../frontend/src/export/captureRegistry.ts) —
  the capture seam: each viewport registers `{canvas, renderNow(), setExportMode()}`
  (module singleton, Decision-5 idiom). Pixel reads are same-task after `renderNow()`
  — **no `preserveDrawingBuffer`** (Decision 29; two-line escape hatch if a driver misbehaves).
- **MOD** [frontend/src/components/Globe.tsx](../frontend/src/components/Globe.tsx) —
  registers `{viewer.scene.canvas, viewer.render(), useDefaultRenderLoop toggle}`
  (an explicit render draws at store time via the existing preRender clock copy);
  perf marks (postRender) + stream-ready marks for the load timer.
- **MOD** [frontend/src/views/ProximityView.tsx](../frontend/src/views/ProximityView.tsx) —
  rAF body factored into `drawFrame(now)`; loop idles in export mode; registers
  the capture source; per-frame perf mark.
- **NEW** [frontend/src/export/capture.ts](../frontend/src/export/capture.ts) —
  `snapshotPng` (global / proximity / side-by-side composite, caption strip,
  ≤3840 px) + shared `computeLayout`/`renderComposite` (even dims for H.264).
- **NEW** [frontend/src/export/mp4Exporter.ts](../frontend/src/export/mp4Exporter.ts) —
  offline frame-stepped render: pause via `seek` → `setCurrentTime` per frame
  (the existing writer path; clockEngine only writes while playing) → synchronous
  composite → `VideoFrame` → `VideoEncoder` (codec ladder `avc1.640028` →
  `4d0028` → `42E01F`, `isConfigSupported` gate) → `mp4-muxer` → Blob. Encode-as-
  you-go backpressure; cancel + `finally` restore; ≤1800 frames; ≤1920 px; sim-time chip.
- **NEW** [frontend/src/export/eventsExport.ts](../frontend/src/export/eventsExport.ts) —
  pure builders: `orbit.scenario-events.v1` JSON + flat CSV over all five event
  kinds (sensor AOS/LOS, eclipse, conjunction, constraint, closest-approach),
  names resolved from the body, epoch-sorted. (Link-budget *series* + screening
  CSV deliberately excluded — not events / already covered.)
- **NEW** [frontend/src/export/ExportPanel.tsx](../frontend/src/export/ExportPanel.tsx) —
  panel-chrome card: PNG ×3, MP4 (source/range/speed/fps, frame-count clamp
  note, progress + Cancel, WebCodecs-missing tooltip), OEM download (typed
  client + `parseAs:'text'` so the Bearer middleware applies), events JSON/CSV.
- **NEW** [frontend/src/export/download.ts](../frontend/src/export/download.ts) —
  shared Blob-download + slug/timestamp helpers.
- **NEW** [backend/.../io/OemExportService.java](../backend/src/main/java/space/orbit/backend/io/OemExportService.java) —
  the ScreeningService pattern (owner-gated `exportView`, rebuild the real
  providers: TLE→SGP4/numerical-with-impulses incl. finite burns, measured→
  `MeasuredEphemerisFactory` with the grid **intersected to the data span**,
  CW→SGP4-absolute + COMMENT), sampled EME2000/UTC on the stream's effective-step
  grid, one `OemSegment` per craft, written via `WriterBuilder().buildOemWriter()`
  + `KvnGenerator`. Determinism: header creation date pinned to the **version
  stamp**. Domain exits truncate the segment honestly (never HOLD states in an
  interchange file); a never-valid role → 422.
- **MOD** [backend/.../scenario/ScenarioService.java](../backend/src/main/java/space/orbit/backend/scenario/ScenarioService.java) —
  `ExportView`/`exportView(id)` + `recordOemExport(id, summary)` (one `EXPORT_OEM`
  audit row, **no version row** — the audited-export precedent, Decision 29).
- **MOD** [backend/.../api/ScenarioController.java](../backend/src/main/java/space/orbit/backend/api/ScenarioController.java) —
  `GET /scenarios/{id}/export/oem` (text/plain attachment).
- **NEW test** [io/OemExportServiceTests](../backend/src/test/java/space/orbit/backend/io/OemExportServiceTests.java) —
  round-trip through Orekit's `OemParser` (frame/time-system/window; mid-grid
  state vs an independent propagator <1 m), **byte-identical rerun** (R11),
  maneuvered-deputy trajectory is the real post-burn track, audit recorded.
- **MOD test** ScenarioControllerTests (+2: attachment 200, 404),
  ScenarioServiceTests (+1: one `EXPORT_OEM` audit row, no version),
  SecurityConfigTests (new controller dep mocked).
- **DEP** `mp4-muxer` ^5.2.2 (approved; image rebuilt + anon volume dropped per
  the frontend/README workflow).

### 11A — Usability (§5.6)

- **NEW** [backend/.../scenario/UserProvisioner.java](../backend/src/main/java/space/orbit/backend/scenario/UserProvisioner.java) +
  [UserProvisionedEvent](../backend/src/main/java/space/orbit/backend/scenario/UserProvisionedEvent.java) —
  first-sight user creation in its own `REQUIRES_NEW` transaction (also fixes the
  latent provisioning-inside-a-readOnly-tx flush quirk), publishing one event per
  user; race-safe on the email UNIQUE constraint.
- **MOD** [UserService.java](../backend/src/main/java/space/orbit/backend/scenario/UserService.java) —
  `getOrCreateByEmail` delegates creation to the provisioner.
- **MOD** [SampleScenarioSeeder.java](../backend/src/main/java/space/orbit/backend/scenario/SampleScenarioSeeder.java) —
  `seedAll(ownerId)` (per-demo try/catch); startup path keeps seeding the dev
  user; `@TransactionalEventListener(AFTER_COMMIT)` seeds every newly provisioned
  user (§5.6.1 under OIDC — the list is owner-scoped). **Three new demos**:
  *inspection & link budget* (chief imager, boresight +Y ram, 35° cone, 50 m–5 km,
  RF link budget → recurring AOS/LOS + SNR band), *eclipse & lighting* (6 h ≈ 4
  orbits → guaranteed umbra), *V-bar station* (deputy 2 km behind on V-bar —
  the hold/glideslope launchpad). Stale "corrector is later-phase work" comment
  freshened (9A shipped it).
- **MOD** [ScenarioService.seedIfAbsent](../backend/src/main/java/space/orbit/backend/scenario/ScenarioService.java) —
  now `REQUIRES_NEW` (per-demo isolation + the AFTER_COMMIT-listener commit trap).
- **NEW test** [scenario/UserProvisioningSeedTests](../backend/src/test/java/space/orbit/backend/scenario/UserProvisioningSeedTests.java) —
  full-context (Testcontainers): new user → 5 demos + one SEED audit row each,
  idempotent re-resolve, **readOnly-tx provisioning regression**, dev-user startup seed.
- **MOD test** [SampleScenarioFormationTests](../backend/src/test/java/space/orbit/backend/scenario/SampleScenarioFormationTests.java) —
  +3: imager acquires/loses the deputy (`SensorEventComputer` on stream-shaped
  samples), the 6 h window contains umbra passes (`EclipseEventComputer`), the
  V-bar station holds −2 km in-track (|R|,|C| < 300 m) over 2 orbits.
- **NEW** [frontend/src/components/HelpOverlay.tsx](../frontend/src/components/HelpOverlay.tsx) —
  `?` top-bar button → modal (quick start / controls / mini-glossary, Esc closes,
  no deps) + [FirstRunHint.tsx](../frontend/src/components/FirstRunHint.tsx) —
  one-time callout (dismissed by ×, opening Help, or loading a scenario;
  `orbit.help.seen`).
- **MOD (tooltip audit)** — `title=` (units + meaning) added to **76 controls**
  across 13 files (ManeuverPanel 25, SensorPanel 15, EnvironmentPanel 9,
  MonteCarloPanel 9, ScenarioPanel 5, ProximityView 5, …); audit script shows
  zero untitled interactive elements (§5.6.2).

### 11C — Performance pass + docs polish

- **NEW** [frontend/src/lib/perf.ts](../frontend/src/lib/perf.ts) — module
  singleton (never touches Zustand per-frame): frame rings per view → FPS avg +
  worst recent frame; `markSeek` closed by each live view's next rendered frame
  → scrub last/p95; `markLoadStart`/`markStreamReady` → scenario-load timer.
  Hooks: Globe postRender, ProximityView `drawFrame`, store `seek`/`step`/
  `loadScenario`, first CZML chunk + relative payload.
- **NEW** [frontend/src/components/PerfHud.tsx](../frontend/src/components/PerfHud.tsx) —
  2 Hz overlay with the §5.1 thresholds highlighted red when missed; toggled by
  the stats-overlay **⏱** (persisted) or `?perf=1`.
  **MOD** StatsOverlay (toggle + mount).
- **NEW** [backend/.../api/OpenApiConfig.java](../backend/src/main/java/space/orbit/backend/api/OpenApiConfig.java) —
  Info (title/version/description incl. the WebSocket companions + per-mode auth
  note) + bearer `SecurityScheme`. **MOD** ScenarioController + HealthController —
  `@Tag` groups (Scenarios / Versions & audit / Maneuvers / Sensors & attitude /
  Environment & constraints / Analysis / Export / Health) + `@Operation` summaries
  on all 31 endpoints. Doc-only: regenerated `schema.d.ts` is comment churn;
  type-check proves no client drift.
- **NEW** [docs/user-guide.md](./user-guide.md) — 14 sections mapped to UC-1..8 +
  §4.2/§5.6 (demos table, interface tour, every workflow through export).
- **NEW** [README.md](../README.md) — root readme (none existed): pitch, quick
  start, docs map, architecture paragraph, status.

## Invariants preserved

- **Streaming contract additive, `VERSION="1"` (R12)** — no payload change at all;
  export reads existing buffers/providers. New REST regenerated the client.
- **Determinism (R11)** — OEM export byte-identical on rerun (creation date =
  version stamp, fixed grid; proven by test). MP4 is frame-stepped off sim time,
  not wall clock.
- **One clock writer (D11)** — the MP4 exporter pauses and steps through the
  existing `seek`/`setCurrentTime` actions; `clockEngine.tick` only writes while
  playing. Render-loop suspension is per-view export mode, restored in `finally`.
- **Single audited mutation path (D16)** — seeding still flows through
  `seedIfAbsent`; the only extension is the narrow audited-export precedent
  (`EXPORT_OEM`, no version row — Decision 29).
- **Frontend never propagates (D9)** — events/PNG/MP4 consume streamed buffers;
  OEM is computed backend-side.
- **Ephemeris outside Zustand (D5)** — captureRegistry + perf.ts are module
  singletons; per-frame work never touches the store.

## Verification

- Backend: `./gradlew test` → **217 green** (OEM round-trip/rerun/maneuvered/audit;
  provisioning-seed chain incl. the readOnly-tx regression; 4 formation-geometry
  tests; controller/security slices).
- Frontend: `npm run type-check` green; `vite build` green (built to a scratch
  outDir — the repo's `dist/` is root-owned from a container build).
- Dev stack (OIDC overlay): backend rebuilt → logs show all **5 demos ensured**;
  `/v3/api-docs` carries the new title + 8 tag groups + the OEM path;
  `GET /scenarios/**` and the OEM endpoint return **401 unauthenticated** (gating
  intact); frontend image rebuilt + anon volume dropped → dev server resolves
  `mp4-muxer` and serves the exporter module.
- **Remaining manual click-through** (needs a browser on the dev stack): PNG
  snapshot pixels + composite at several split ratios; a ~10 s MP4 of the NMC
  demo (plays in Chrome/VLC; cancel restores playback); OEM download via the
  panel in oidc mode + `EXPORT_OEM` visible in the Audit panel; a fresh OIDC user
  (`gita/gita`) sees the 5 demos on first list; first-run hint/Help flows; and
  the **§5.1 PerfHud readings on reference hardware** (record numbers below when
  taken).

### §5.1 evidence (to record from the PerfHud, reference hardware)

| Target | Check | Measured |
|---|---|---|
| Proximity ≥60 fps (§5.1.1) | NMC demo + a 10-craft composed scenario, 60 s sustained | _pending_ |
| Global ≥30 fps (§5.1.2) | full catalog + scenario layer (capped at `targetFrameRate` 30) | _pending_ |
| Scrub ≤200 ms (§5.1.3) | timeline drag, HUD p95 | _pending_ |
| 24 h load ≤5 s (§5.1.4) | demo window widened to 24 h, numerical fidelity | _pending_ |

Ranked fix candidates if a number misses (pre-scoped, Decision 29): ribbon
`setSplit` throttling; parallel per-role sampling in `loadAndEncode` (bounded
pool + ordered collect — the MonteCarloService trick); catalog `CallbackProperty`
work. None applied yet — measure first.

## Deferred (Decision 29)

- Link-budget SNR *series* export (not an event; the timeline shows it).
- OEM/AEM **import** + browser upload — measured-data track slice 3 (Decision 26).
- MediaRecorder/WebM fallback for non-WebCodecs browsers (feature-gated tooltip
  is the v1 posture; acceptance on Chromium).
- Live k8s cluster install — remains the Phase-10 follow-up (R9 go-live trigger).
- Frontend bundle code-splitting (the 1.06 MB main chunk warning — watch-list).

**Phases 1–11 complete** — the roadmap is shipped; remaining items are the
tracked follow-ups above + the deferred registry in decisions.md.
