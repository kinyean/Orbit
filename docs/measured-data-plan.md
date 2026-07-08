# Measured-data ingestion (plan)

The **HOW** for importing real measured telemetry as scenarios. The **WHY** is
[Decision 26](./decisions.md). This is a feature track (not a numbered roadmap
phase) opened mid-Phase-8 when real flight data (TELEOS-2 WOD CSVs) arrived; it
generalizes two long-deferred items — CCSDS OEM import (Decision 19 / US-SCN-06)
and CCSDS AEM measured attitude (Decision 24 / R17).

> **Status: Slices 1 & 2 complete & verified end-to-end (2026-06-22).** Slice 3
> planned. Backend 126 tests green; frontend type-check + build green.
>
> A **presenter demo suite** (six TELEOS-2 scenarios for a `demo` account, built
> re-runnably by [scripts/seed-teleos-demos.sh](../scripts/seed-teleos-demos.sh)) rides
> this track — walkthrough in [measured-demos.md](./measured-demos.md). Building it landed
> two backend changes (2026-07-08, backend → 224 tests): Monte Carlo now resolves the chief
> through `ChiefStateResolver` (runs against a measured chief), and
> `PropagationService.stabilizeForRepeatedSampling` makes a maneuvered numerical propagator
> order-independent across `ScenarioStreamService`'s two sampling passes (see the Decision-26
> demo-suite addendum).

## What it adds

The project until now built scenarios only from **modeled** sources (a frozen TLE
per role, propagated by SGP4/numerical/CW). This adds **measured** sources: a
satellite's real GNSS ECI position/velocity (and, later, attitude quaternions)
over a bounded window, imported from a "Whole-Orbit Data" (WOD) CSV. The measured
craft becomes a **read-only chief** you compose hypothetical (or other measured)
deputies around.

## Core principle (Decision 26)

One internal artifact, many readers. `WodCsvReader` normalizes a WOD CSV into a
`MeasuredEphemeris` (frame-tagged EME2000 pos/vel samples); the samples are frozen
into an immutable, content-hashed `MeasuredDataset` (a Postgres `bytea` blob, out
of the small jsonb body); a scenario role references it by id via
`InitialState{kind:"ephemeris", datasetId}`. The backend serves it through an
Orekit tabulated `Ephemeris` (a `PVCoordinatesProvider`), so the whole sampling /
encoding / streaming pipeline is **unchanged**. Measured-ness is a property of a
**role** (`initialState.kind`), NOT a scenario-wide `Fidelity` — so a measured chief
coexists with TLE/numerical deputies.

---

## Slice 1 — reader → dataset → playable measured position ✅ DONE

Upload-by-server-path (the WOD files are large — TELEOS-2 is ~570 MB — and already
live on the server under `/shared_folder`; a server-path request is plain JSON, so
the generated typed client works with no multipart plumbing).

**Backend (new):**
- `io/WodCsvReader` + `io/MeasuredEphemeris` — streaming single-pass parse of the
  stacked-block WOD format; extracts the 6 ECI pos/vel channels
  (`SW_TM_ODCS_GNSS_ECI_POS_X/Y/Z`, `..._VEL_X/Y/Z`), km→m, drops invalid `(0,0,0)`
  GNSS fixes, aligns the channels by onboard timestamp, sorts ascending. EME2000
  (verified to ~1 m via an ECI→ECEF self-check against the file's own ECEF channels).
- `scenario/MeasuredDataset` (entity) + `MeasuredDatasetRepository` +
  `MeasuredDatasetCodec` (gzipped binary: `int count` then per sample `long epochMillis`
  + 6 `double`s; deterministic → R11; SHA-256 content hash).
- `db/migration/V5__measured_dataset.sql` — the `measured_dataset` table.

**Backend (modified):**
- `ScenarioBody.InitialState` → `(kind, tle, datasetId)`, schema **v4**, forward-additive
  (a 2-arg convenience ctor keeps existing `new InitialState("tle", tle)` call sites).
- `ScenarioService.importMeasured(path, noradId?)` — one `@Transactional` on the audited
  path: validate the path is within `orbit.import.allowed-root` (traversal guard) → read
  → resolve NORAD (catalog name-lookup `CatalogService.findNoradByName`, else the
  supplied override, else 422) → insert dataset → create scenario (chief = measured craft,
  `kind:"ephemeris"`, window = data span, `fidelity:"numerical"` for later deputies) with
  audit action `IMPORT_MEASURED`. **Also** `update()` now merges against the current body
  (`buildBody(draft, existing)` + `resolveRole`) so editing a measured scenario PRESERVES
  the ephemeris chief instead of rebuilding it from the catalog (a real latent bug —
  without this, adding a deputy via the composer destroyed the imported chief).
- `api/ScenarioController` → `POST /scenarios/import/measured` `{path, noradId?}` (OpenAPI-documented).
- `ScenarioStreamService.prepareRole` branches on `kind=="ephemeris"` → `prepareEphemerisRole`
  builds `List<SpacecraftState>` (EME2000, WGS84 μ) → `new Ephemeris(states, 2)`; period/
  inclination/ecc from the first state's `KeplerianOrbit`. The per-sample HOLD was fixed so a
  **leading** gap (the sample margin BEFORE the first measured sample) retries until data
  starts, while a **trailing** decay still holds (the `firstValid >= 0` guard in the 3 catch
  sites). `application.yml` gains `orbit.import.allowed-root` (default `/shared_folder`).

**Frontend:**
- `useStore.importMeasuredScenario(path, noradId?)` → `api.POST('/scenarios/import/measured')`
  → `loadScenario(id)`. A collapsible "Import measured data" block in `ScenarioPanel.tsx`
  (path + optional NORAD + hint); flex layout with `box-sizing:border-box` so nothing clips.
  `gen:api` regenerated.

**Infra:** `docker-compose.yml` bind-mounts `/mnt/disk_large/shared_folder → /shared_folder:ro`
on the backend + sets `IMPORT_ALLOWED_ROOT`.

**Tests:** `WodCsvReaderTest` (parse/align/units/invalid-drop, fixture
`backend/src/test/resources/wod-sample.csv`), `MeasuredDatasetCodecTest` (round-trip +
deterministic encode + hash), `MeasuredEphemerisServeTest` (Ephemeris serves the measured
state at a node + **regression guard** `interpolatesStablyBetweenNodes` — a circular orbit
must stay on-radius BETWEEN nodes; fails at the old 4-point interpolation).

**Verified on the dev stack:** TELEOS-2 (570 MB) imports in ~3.2 s → dataset (55,744 samples,
2.8 MB blob), NORAD auto-resolved 56310, schema v4; the stream serves the real orbit
(`chiefRadiusM` = 6,952,848 m = the measured radius; orbit radius holds 6952–6954 km all
the way round); bad/outside-root/blank paths → 422. Two co-launch deputies (LUMELITE-4 56309,
POEM-2 56308 — same PSLV-C55 plane) added via the preserving `update()`; LUMELITE-4 makes a
real ~76 km closest approach.

### Lessons / caveats from slice 1 (read before slice 2/3)
- **Interpolation degree matters (the bug that produced a 977-billion-km orbit).** Orekit's
  `Ephemeris(states, N)` fits a degree-(2N−1) Hermite (states carry velocity) through N
  nodes. At ~5-min / ~22°-of-arc spacing, N=4 (degree-7) overshoots violently BETWEEN nodes
  (Runge) even though nodes are exact. **Keep `EPHEMERIS_INTERP_POINTS = 2`** (cubic Hermite
  per segment — stable + accurate for dense ephemeris). Guarded by `interpolatesStablyBetweenNodes`.
- **Measured chief is read-only truth** — it has no TLE and can't be maneuvered; the
  proximity (relative) view needs at least one deputy to be meaningful.
- **Catalog deputies on a measured chief are illustrative.** Their TLEs are current-epoch
  (months from the data window), so SGP4 in the window gives a *co-planar but phase-approximate*
  orbit — fine for a demo, not a real RPO pair. A genuine measured pair needs two datasets (slice 3).
- **Attitude is measured (slice 2 ✅)** — the chief flies its real `EST_ATTD` quaternion.

---

## Slice 2 — measured attitude ✅ DONE (2026-06-22)

Streams the satellite's real orientation instead of the modeled LVLH estimate.

1. **Reader:** `WodCsvReader` also extracts `SW_TM_ADCS_EST_ATTD_Q1..Q4_8` into a **parallel
   attitude series** (`MeasuredEphemeris.AttitudeSample` — its own timestamps; the ADCS cadence
   differs from GNSS, so it's NOT intersected with position). Components kept **raw** (the
   convention conversion lives in code); non-unit rows (ADCS dropouts / all-zero) dropped.
2. **Codec:** `MeasuredDatasetCodec` gains the attitude series with a **backward-compatible
   version sentinel** — a leading negative `int = -2` (a v1 count is always ≥ 0) marks the v2
   layout; legacy position-only blobs still decode (empty attitude). Deterministic (R11); opaque
   `bytea`, no DB migration.
3. **Schema:** `AttitudeProfile.mode = "measured"` — set on the chief at import when the file
   carries attitude (no quaternion stored here; it resolves to the role's dataset). No shape
   change → `CURRENT_SCHEMA_VERSION` stays 4. `update()`'s `resolveRole` preserves it on edit.
4. **Stream:** `prepareEphemerisRole` converts the raw quaternions to a body→ECI three.js series
   (stride-5, absolute epoch seconds) and hangs it on `PreparedRole`. `bodyAttitude` (used by both
   the chief `sampleAttitude` and the deputy loop) **SLERPs** it at `epoch + t` and feeds the
   result through the existing `"fixed"` path (`FrameService.bodyQuaternionInLvlh`) — measured and
   modeled share one code path. SLERP extracted to `prop/QuaternionSamples` (also used by
   `SensorEventComputer`). Reuses the `att` stride-5 stream field — additive, `VERSION="1"` (R12),
   deterministic (R11).
5. **Convention (R15 / R20) — RESOLVED empirically + pinned.** No vendor spec; resolved from the
   TELEOS-2 telemetry (spike in scratch): the quaternion is **unit, ECI-referenced** (the recurring
   "home" value is held at many orbit positions = a fixed *inertial* attitude), `EST_ATTD` ≡
   `STS_BF` (same convention), and **scalar-last (Q4 = w), body→ECI** is favored by both the
   pointing geometry and the only positive `FOG_RATE_BF` angular-velocity correlation. Since
   three.js `(x,y,z,w)` is also scalar-last, the converter (`prop/MeasuredAttitude`) is the identity
   reorder `(Q1,Q2,Q3,Q4)`. Pinned by a **signed-axis test** (`MeasuredAttitudeTest`); the physical
   convention is **flippable in one place** (two named constants) and confirmed visually on the dev
   stack. (The gyro *magnitude* test was inconclusive — attitude is 5-min sampled but slews are
   fast; a data limitation, not the convention.)
6. **Frontend:** `orientation.ts` already SLERP-consumes `att` (no change). Added a toggleable
   **body-axis triad** per craft (`spacecraftModel.ts` `setAxesVisible`/`setAxesWorldLength`, a
   "Body axes" control in `ProximityView`) — the orientation read, since the model is too symmetric
   and a far craft is just a marker dot. The triad is **camera-distance-scaled** so it stays a
   readable on-screen size even when the view is zoomed out to fit a km-scale FOV cone (a fixed-length
   triad vanished there). Legend reads **"measured"** (from the loaded body's `attitude.mode`), else
   modeled/estimated, shown for all craft. Also fixed the proximity **auto-frame** so a lone measured
   chief (no deputy) seeds the working scale from a craft-scale floor + the chief's own sensor ranges
   instead of a 1 km default — at the old default the ~10 m model framed as a sub-pixel marker dot,
   forcing a manual zoom.

**Verified on the dev stack:** re-import TELEOS-2 → 201, schema v4, chief `attitude.mode=measured`;
the `scenario-relative` frame carries the chief's measured `att` (4983 samples, varying, not
identity). **Resolves R17 fully for measured craft** (orientation modeled→measured; lighting stays
Phase 8). **Tests:** `WodCsvReaderTest` (attitude parse/align/unit-drop), `MeasuredDatasetCodecTest`
(v2 round-trip + legacy-v1 decode + deterministic), `MeasuredAttitudeTest` (signed-axis pin),
`MeasuredEphemerisServeTest.measuredAttitudeSlerpsTheConvertedQuaternion`.

**The one thing to eyeball:** the *physical* convention (is the craft pointing the right way, not
mirrored/inverted) — if wrong in the proximity view, flip `MeasuredAttitude.SCALAR_LAST` or
`CONJUGATE` (one line each).

## Slice 3 — measured deputies, numerical handoff, more readers

- **Measured deputy:** import a dataset as a *deputy* in an existing scenario (a second
  `import` variant or a `datasetId` on the add-deputy path). Two measured craft over the same
  window = a **real measured chief+deputy RPO pair** — the genuinely valuable case.
- **Numerical handoff beyond the window:** today out-of-window samples HOLD at the boundary.
  Instead, seed `NumericalPropagation.build` from the last measured state and propagate forward
  (the ~5 km/day along-track drift measured in the validation spike). Optional drag-fit
  (estimate Cd·A/m from the measured week — the differential corrector on the Phase-9 list)
  to do better.
- **Browser upload:** the deferred multipart path — raise Spring multipart limits, XHR upload
  progress, async parse with a job/progress indicator (the reader already takes an `InputStream`,
  so this is additive; server-path import stays for files already on the box).
- **OEM/AEM readers:** add CCSDS `Oem`/`Aem` readers (Orekit-native) feeding the SAME
  `MeasuredEphemeris`/dataset, so any standard ephemeris/attitude file imports through the same
  pipeline (closes US-SCN-06 properly + gives Frank his OEM handoff, UC-3/UC-8).

---

## Key files (where things are)

| Concern | Path |
|---|---|
| Reader + normalized artifact | `backend/.../io/WodCsvReader.java`, `io/MeasuredEphemeris.java` (pos `Sample` + `AttitudeSample`) |
| Storage | `backend/.../scenario/MeasuredDataset.java`, `MeasuredDatasetRepository.java`, `MeasuredDatasetCodec.java` (v1/v2), `db/migration/V5__measured_dataset.sql` |
| Import (audited) | `backend/.../scenario/ScenarioService.java` (`importMeasured`, `resolveRole`/`buildBody` merge), `api/ScenarioController.java` |
| Serve (Ephemeris + interp lesson) | `backend/.../stream/ScenarioStreamService.java` (`prepareEphemerisRole`, `buildMeasuredAttitude`, `bodyAttitude`, `EPHEMERIS_INTERP_POINTS`) |
| Measured attitude (slice 2) | `backend/.../prop/MeasuredAttitude.java` (convention), `prop/QuaternionSamples.java` (shared SLERP) |
| Schema | `backend/.../scenario/ScenarioBody.java` (`InitialState` v4, `AttitudeProfile.mode="measured"`) |
| Frontend | `frontend/src/store/useStore.ts` (`importMeasuredScenario`), `scenario/ScenarioPanel.tsx`, `proximity/spacecraftModel.ts` (body-axis triad), `views/ProximityView.tsx` (Body-axes toggle + measured legend) |
| Config | `application.yml` (`orbit.import.allowed-root`), `docker-compose.yml` (shared-folder mount) |

A scratch validation spike (SGP4/numerical drift vs the measured TELEOS-2 track) lived in
`MeasuredEphemerisServeTest`'s neighborhood during development; throwaway sample CSVs are under
`_teleos_samples/` (gitignored).
