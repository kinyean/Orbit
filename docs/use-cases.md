# Use cases

End-to-end workflow narratives. Each use case shows a specific
[persona](./personas.md) accomplishing a specific goal, exercising specific
SRS capabilities. Use cases catch integration gaps the SRS misses (which is
feature-oriented, not flow-oriented) and feed the
[user-stories](./user-stories.md) backlog.

---

## UX patterns referenced throughout

These conventions apply across every use case. Pinned once here so each use
case can reference rather than restate.

**Click-to-inspect, then commit.** Clicking any satellite — in the catalog or
in a scenario — *inspects* it. The info panel populates with its details.
**No automatic role assignment.** Adding the satellite to a scenario role
(chief, deputy) is an explicit action via a button in the info panel.

**Always-visible info panel.** Right side, fixed. One satellite at a time.
"Selected" is the click target, regardless of whether it's been added to a
scenario.

**Scenario composer card.** Distinct from the info panel. Shows the *current
scenario state* — chief, deputies, save status. Persistent on screen during
authoring. Empty state: "No chief designated."

**Two-mode globe.** The globe view layers (a) the catalog (all ~14,500 active
satellites as small dots from the shared backend stream) and (b) the scenario
spacecraft (highlighted markers + orbits + ground tracks). Both visible
together.

**Two-viewport synchronization.** Global view and proximity view share one
clock. Scrub or play in either; both move in lockstep.

**Catalog filters narrow, never delete.** Filtering hides; the underlying
data is unchanged. Filter state persists across reloads.

**Maneuvers are scenario edits.** Adding/changing a maneuver creates a new
scenario version (audit trail). Re-propagation is automatic.

**Replace-with-confirm for destructive actions.** Replacing the chief, deleting
a deputy, dropping a maneuver — all prompt once.

---

## UC-1 — Compose a quick rendezvous analysis from the live catalog

**Actor:** Maya
**Trigger:** she wants a feasibility sketch of bringing Starlink-1234 close to
the ISS.
**SRS capabilities exercised:** §3.10.3 (TLE-sourced initial states), §3.5.3
(maneuver templates — Hohmann), §3.4.1 (relative state readout), §3.8 (global
view), §3.9 (proximity view).

1. Maya opens the app. Catalog browser on the left; globe view fills the
   center with ~14,500 dots; empty info panel on the right; composer card
   below ("No chief designated").
2. She types "ISS" in the search box. ISS highlights on the globe and the
   camera centers on it.
3. She clicks the ISS dot. **Info panel populates** with the ISS's details:
   name, NORAD ID, country, launch date, current lat/lon/alt, orbital
   parameters (altitude, period, inclination). Nothing else changes —
   inspection only.
4. She reviews, clicks **"Set as chief"**. Composer card updates to
   "Chief: ISS (25544)". Proximity view activates showing the ISS at LVLH
   origin.
5. She narrows the catalog to "Starlink" using the constellation filter,
   scrolls to Starlink-1234, clicks it. Info panel swaps to Starlink-1234.
6. She clicks **"Add as deputy"**. Composer card now reads
   "Chief: ISS · Deputies: [Starlink-1234]". Proximity view shows both
   spacecraft in LVLH; a relative-state readout appears (distance,
   range-rate, time of closest approach).
7. She opens the maneuver panel, picks the "Hohmann transfer to match deputy
   altitude" template. UI shows: ΔV = 6.2 km/s, transfer time = 45 min.
8. She raises an eyebrow at the ΔV — cross-plane, expensive — and discards
   the scenario without saving.

**Edge cases:**
- *Replacing the chief.* Clicking "Set as chief" with an existing chief
  prompts: "Replace ISS with X? Existing deputies will be re-expressed in
  the new chief's LVLH frame."
- *Add-as-deputy disabled.* Before a chief is set, "Add as deputy" is
  disabled with tooltip "Designate a chief first."
- *Satellite already in scenario.* Info panel shows "Remove from scenario"
  alongside (or in place of) the add buttons.

---

## UC-2 — Plan a formation-flying mission with hypothetical spacecraft

**Actor:** Maya
**Trigger:** designing a 3-spacecraft formation around a planned (not yet
launched) reference satellite.
**SRS capabilities exercised:** §3.10.4 (Keplerian-element initial states),
§3.5.3 (NMC ellipse template), §3.10.5 (versioning).

1. Maya clicks **"New scenario"** in the scenario panel.
2. A form appears: name, time range, fidelity selection. She names it
   "Formation-Demo-A," sets the time range to 24 h, picks "high-fidelity
   numerical."
3. She clicks **"Add chief from Keplerian elements"** in the empty composer.
   A modal accepts: semi-major axis, eccentricity, inclination, RAAN,
   argument of perigee, true anomaly, epoch. She enters reference-orbit
   values. Chief appears in the composer as "Hypothetical Chief".
4. She clicks **"Add deputy from template → NMC ellipse"**. Form prompts for
   radial separation (50 m) and orientation. Deputy added; composer updates.
5. Repeats step 4 twice more, varying the parameters. Three deputies, all on
   different NMC ellipses around the chief.
6. Proximity view renders the football-shaped relative orbits.
7. She clicks **"Save"**. Scenario versioned as v1.
8. Iterates: changes one ellipse parameter, saves again as v2. Version
   history visible in the scenario panel.

**Notes:**
- All Keplerian inputs use units shown explicitly (km, deg). Validation on
  bounds (e ∈ [0, 1), i ∈ [0°, 180°], etc.).
- NMC template fills in the deputy's initial state from the chief's
  reference orbit + the requested geometric parameters.

---

## UC-3 — Validate an imported scenario at high fidelity

**Actor:** Frank
**Trigger:** Maya hands him an exported scenario; he needs to verify it
under numerical propagation with full perturbations.
**SRS capabilities exercised:** §4.1.2 (CCSDS OEM import), §3.1.2–6
(high-fidelity propagation), §3.12.1 (conjunction detection), §3.12.3
(constraint violations), §5.4.1 (reproducibility).

1. Frank clicks **"Import scenario from file"** in the scenario panel.
   Selects a CCSDS OEM file Maya exported.
2. Backend parses the file (Orekit). A new scenario draft appears in the
   panel with the imported chief, deputies, and time range.
3. Frank reviews the scenario, switches fidelity from Maya's "SGP4" to
   "high-fidelity numerical (DP8(7), J4, NRLMSISE-00, SRP, Sun+Moon)."
4. He sets the conjunction miss-distance threshold to 100 m and approach
   corridor / sun-keep-out constraints.
5. Clicks **"Propagate"**. Backend runs the high-fidelity propagator over
   the time range. Status indicator shows progress.
6. On completion, the timeline annotates: 2 conjunction events flagged
   (84 m and 121 m). One constraint violation: sun-keep-out at T+02:14.
7. Frank scrubs to T+02:14 in the proximity view, confirms the geometry
   visually.
8. He **exports the propagated ephemeris** as a CCSDS OEM file for the
   downstream tool.
9. The session is logged in the audit trail with his identity and the run
   parameters; re-running tomorrow produces bit-identical results.

**Notes:**
- Frank's identity comes from the auth pipeline (real in production,
  stubbed in dev — Decision 16).
- Reproducibility relies on Decision 16: deterministic propagation,
  seeded dispersions, pinned settings.

---

## UC-4 — Sensor field-of-view analysis during an approach

**Actor:** Gita
**Trigger:** evaluating when the deputy is in a visible-band imager's FOV
during a V-bar approach.
**SRS capabilities exercised:** §3.6 (sensor modeling), §3.9.4 (FOV
volumes), §3.6.5 (occlusion), §3.6.6 (sensor-frame rendering), §3.12.2
(acquisition events).

1. Gita loads "ISS-VBar-Approach-v3" from the scenario panel.
2. She opens the **sensor panel** for the chief. Clicks **"Add sensor"**:
   - Type: optical
   - FOV: 20° × 15° rectangular
   - Range: 100 m – 50 km
   - Pointing: body-fixed +X
3. Proximity view now renders a translucent rectangular FOV cone projected
   from the chief.
4. She scrubs the timeline. The FOV swings with the chief's attitude
   profile. The deputy passes in and out of the cone.
5. Timeline annotates "acquisition" and "loss-of-sight" events.
6. She switches the proximity view's camera mode to **"sensor frame"** —
   the camera now looks along the sensor's pointing direction. Deputy
   appears centered when within FOV.
7. She **adds a sun-keep-out constraint** (avoid sun within 20° of
   boresight). A new constraint-violation event flags at T+00:47.
8. She adjusts the chief's attitude profile to avoid that geometry; the
   event clears.

**Notes:**
- Occlusion (Earth blocks line-of-sight; chief's own body blocks FOV) is
  computed and respected in the events log.
- Sensor frame view requires Phase 7 (sensor modeling) and Phase 8
  (environment).

---

## UC-5 — Eclipse and lighting analysis

**Actor:** Frank (or Omar pre-rehearsal)
**Trigger:** verifying when the deputy enters/exits Earth shadow during
operations.
**SRS capabilities exercised:** §3.7.1 (Sun/Moon positions), §3.7.2
(umbra/penumbra), §3.7.3 (illumination consistency), §3.11.3 (timeline
annotations).

1. Frank loads an existing scenario. Time range spans two orbital periods.
2. He toggles **"Show eclipse periods"** in the timeline panel.
3. Timeline highlights umbra (full shadow) and penumbra (partial shadow)
   bands per spacecraft. Color-coded.
4. Proximity view: deputy spacecraft visibly darken when inside the umbra,
   consistent with the Sun vector (§3.7.3).
5. Global view: a sun-direction indicator and the day/night terminator
   confirm geometry.
6. He scrubs to an eclipse entry, verifies sensor performance under shadow
   conditions (low-light passive sensors will lose acquisition).

---

## UC-6 — Monte Carlo dispersion on a maneuver plan

**Actor:** Frank
**Trigger:** quantifying trajectory uncertainty from initial-state
uncertainty + maneuver execution error.
**SRS capabilities exercised:** §3.12.4 (Monte Carlo), §3.12.5 (covariance
ellipsoids).

1. Frank loads a validated scenario.
2. Opens the **dispersion panel**. Sets:
   - Initial-state uncertainty: 1-σ in position (100 m), velocity (0.1 m/s).
   - Maneuver execution error: ΔV magnitude (1%), pointing (0.1°).
   - Sample count: 500.
   - Seed: explicit (for reproducibility).
3. Clicks **"Run dispersion"**. Backend runs 500 propagations in parallel.
4. Proximity view overlays the deputy's trajectory cloud — a fuzz of
   500 trajectories, plus a 1-σ covariance ellipsoid at each chosen epoch.
5. Frank checks the 99.7% (3-σ) envelope against the approach corridor.
   One ellipsoid extends past the corridor.
6. He revises the maneuver to add margin; reruns; the envelope clears.
7. He records the dispersion result (seed, parameters, summary stats) in
   the audit log via "Save dispersion result" — keyed to the scenario
   version.

---

## UC-7 — Conjunction screening across a scenario

**Actor:** Frank
**Trigger:** checking the planned trajectory against the catalog for
close approaches.
**SRS capabilities exercised:** §3.12.1 (conjunction detection),
§3.11.3 (event annotations).

1. Frank loads the scenario. Sets the conjunction threshold to 1 km.
2. Clicks **"Screen against catalog"**. Backend runs SGP4 over the active
   catalog within the scenario's time range, computing miss distances
   against each scenario spacecraft.
3. Results: 3 conjunctions flagged. Sortable list shown — closest first.
   Each row: scenario spacecraft, third-party satellite, time, miss
   distance.
4. Frank clicks a row. Timeline scrubs to the conjunction event. Both
   spacecraft visible in the global view; the third-party is highlighted.
5. He inspects the relative geometry, decides one is operationally
   significant, exports the event details as CSV for the team.

**Notes:**
- Catalog screening uses SGP4 (Decision 13); for the highest-confidence
  cases, Frank can re-run a tight time window with the high-fidelity
  propagator on the third-party.

---

## UC-8 — Save, share, and reload a scenario

**Actor:** Maya
**Trigger:** finishing a session, wants to hand off to Frank.
**SRS capabilities exercised:** §3.10.1 (persistence), §3.10.5 (versioning),
§4.2.1 (OEM export).

1. Maya finishes her edits, clicks **"Save"**. Scenario versioned; audit
   log entry recorded.
2. She copies the scenario URL (encodes scenario id + version).
3. She **exports the propagated ephemeris as CCSDS OEM** (the standard
   handoff format Frank's downstream tools accept).
4. She shares the URL + OEM file with Frank.
5. Frank pastes the URL into his app, sees the scenario, can replay it or
   re-validate at higher fidelity (UC-3).
6. Maya's audit trail shows the export event.

**Notes:**
- URL is shareable but access is gated by RBAC (Decision 16). Frank must
  be in the scenario's permitted-readers set.
- Export uses Orekit's CCSDS writer (Decision 15).

---

## Out-of-scope use cases (for now)

Worth noting so we don't accidentally drift into them:

- **Real-time telemetry ingestion** from live spacecraft (SRS §7.2).
- **Hardware-in-the-loop** integration with flight software (SRS §7.1).
- **Trajectory optimization** (delta-V minimization, time-optimal transfers)
  (SRS §7.3).
- **Multi-user collaborative editing** of one scenario in real time
  (SRS §7.4).
