# Measured-data ingestion (plan)

The **HOW** for importing real measured telemetry as scenarios. The **WHY** is
[Decision 26](./decisions.md). This is a feature track (not a numbered roadmap
phase) opened mid-Phase-8 when real flight data (TELEOS-2 WOD CSVs) arrived; it
generalizes two long-deferred items — CCSDS OEM import (Decision 19 / US-SCN-06)
and CCSDS AEM measured attitude (Decision 24 / R17).

> **Status: Slice 1 complete & verified end-to-end (2026-06-22).** Slices 2–3
> planned. Backend 119 tests green; frontend type-check + build green.

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
- **Attitude is still modeled (LVLH)**, not measured — slice 2.

---

## Slice 2 — measured attitude (NEXT)

Stream the satellite's real orientation instead of the modeled LVLH estimate.

1. **Reader:** extend `WodCsvReader` to also pick up `SW_TM_ADCS_EST_ATTD_Q1..Q4`
   (the estimated-attitude quaternion, one channel each, aligned to the same timestamps).
   Extend `MeasuredEphemeris.Sample` (or a parallel attitude series) + the codec blob format
   (bump an internal codec version; the DB column is opaque `bytea`, no migration).
2. **Schema:** `AttitudeProfile.mode = "measured"` (resolves to the role's dataset; no new id).
3. **Stream:** in `ScenarioStreamService.sampleAttitude` (and the per-deputy inline attitude in
   `encodeRelative`), when mode is `measured`, **SLERP-interpolate the dataset's quaternions**
   at the sample times instead of computing the LVLH-from-orbit quaternion. Reuse the existing
   `att` stride-5 stream field — frontend `orientation.ts bodyOrientationAt` already consumes it
   and the legend flips "modeled"→"measured".
4. **THE RISK (R15 / R20):** the WOD quaternion's frame convention (almost certainly ECI→body)
   must be confirmed and converted to the **three.js streaming convention** used by
   `FrameService.bodyQuaternionInLvlh` (which builds an LVLH-scene quaternion). Pin it with a
   **signed-axis test** exactly like the Phase-7 quaternion — get this wrong and the craft points
   the wrong way silently. Cross-check against `STS_BF_Q*` (star-tracker, body-frame) as an
   independent source if needed.
5. **Frontend:** none beyond the legend (attitude already streams + SLERPs). Optionally surface
   the gyro body-rates (`EST_RATE_*`) later for GN&C (Gita).

Resolves R17 fully for measured craft (orientation modeled→measured; lighting stays Phase 8).

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
| Reader + normalized artifact | `backend/.../io/WodCsvReader.java`, `io/MeasuredEphemeris.java` |
| Storage | `backend/.../scenario/MeasuredDataset.java`, `MeasuredDatasetRepository.java`, `MeasuredDatasetCodec.java`, `db/migration/V5__measured_dataset.sql` |
| Import (audited) | `backend/.../scenario/ScenarioService.java` (`importMeasured`, `resolveRole`/`buildBody` merge), `api/ScenarioController.java` |
| Serve (the Ephemeris branch + interp lesson) | `backend/.../stream/ScenarioStreamService.java` (`prepareEphemerisRole`, `EPHEMERIS_INTERP_POINTS`) |
| Schema | `backend/.../scenario/ScenarioBody.java` (`InitialState`, v4) |
| Frontend | `frontend/src/store/useStore.ts` (`importMeasuredScenario`), `scenario/ScenarioPanel.tsx`, `App.css` |
| Config | `application.yml` (`orbit.import.allowed-root`), `docker-compose.yml` (shared-folder mount) |

A scratch validation spike (SGP4/numerical drift vs the measured TELEOS-2 track) lived in
`MeasuredEphemerisServeTest`'s neighborhood during development; throwaway sample CSVs are under
`_teleos_samples/` (gitignored).
