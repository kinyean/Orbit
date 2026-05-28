# Personas

The people whose work this tool serves. Each persona is a composite of real
roles in the flight-dynamics / RPO domain; they exist to make design decisions
concrete ("would Maya care about this?" beats "would a user care about this?").

Four primary personas. Frank and Maya are the *load-bearing* personas — most
decisions optimize for them. Gita and Omar are secondary but real consumers.

---

## Maya — Mission Planner *(primary)*

**Role.** Designs RPO missions before launch. Works months ahead of execution.
Loads reference scenarios, modifies them, evaluates fuel costs and timing,
iterates on approach geometries.

**Background.** Master's in aerospace engineering, ~4 years in industry.
Comfortable with orbital mechanics and the standard maneuver patterns
(Hohmann, V-bar / R-bar, NMC); rusty on detailed perturbation theory.
Not a software engineer — writes Python scripts occasionally but doesn't
build apps.

**Goals.**
- Sketch a candidate scenario quickly to see if an approach is feasible.
- Get a defensible delta-V budget for cost estimates.
- Compare 2–3 design variants of the same scenario.
- Hand off a clean scenario to Frank for high-fidelity validation.

**Pain points with existing tools.**
- STK is powerful but the learning curve is steep and licensing is expensive.
- GMAT is free but feels like a 2003 desktop app; UI is fiddly.
- Setting up a new scenario is slow — too many clicks before you see anything.
- "What if I changed this one parameter?" requires re-running everything.
- Visualization of LVLH-frame relative motion is the part she relies on most;
  she'd kill for one that scrubs smoothly.

**Optimize for her.**
- **Fast scenario authoring.** Click-to-compose from real catalog. Form-based
  for hypothetical missions, but kept short.
- Clear **ΔV budget** readout, prominent.
- **Scrubbable timeline** with maneuver epochs annotated.
- Easy "duplicate this scenario" + "swap deputy" workflows.
- **Templates** (Hohmann, two-impulse, NMC, V-bar hold, glideslope) that drop
  in with sensible defaults.

**Doesn't care about (mostly).**
- Implementation details of the propagator (trusts Orekit).
- Bit-identical reproducibility (Frank does).
- Auth/RBAC (until she shares scenarios with the team).

---

## Frank — Flight Dynamics Engineer *(primary)*

**Role.** Operational flight dynamics. Validates planned proximity ops against
high-fidelity propagation, runs dispersion analyses, signs off on safety
constraints, supports anomaly response during operations.

**Background.** PhD or senior MS in astrodynamics, ~10 years in operations.
Knows Orekit, MATLAB, internal propagation tools. Cares about validated
accuracy and traceability. Conservative.

**Goals.**
- Validate that Maya's scenario survives high-fidelity propagation (J4+, drag,
  SRP, third-body).
- Run Monte Carlo dispersions on initial state uncertainty + maneuver execution
  error; verify the trajectory cloud stays inside safety corridors.
- Check covariance evolution along the trajectory.
- Detect conjunction events, sensor acquisition windows, eclipse periods,
  constraint violations (approach corridor, sun keep-out, plume impingement).
- Reproduce a past analysis bit-for-bit when re-examined.

**Pain points with existing tools.**
- High-fidelity tools (STK Pro, FreeFlyer) exist but are expensive and
  scripted in ways that are hard to share with non-experts.
- Tracking which run produced which result is a perennial pain.
- Visualization of relative motion is usually weaker than analysis quality.
- Audit trails for compliance are bolted on, not native.

**Optimize for him.**
- **Validated** numerical propagation; reference-comparison test suite
  available.
- **Reproducibility** by construction: deterministic propagation, pinned
  settings, seeded RNG.
- **Audit log** capturing every scenario change with author + timestamp +
  diff.
- **Conjunction & event detection** with configurable thresholds.
- **Monte Carlo + covariance** rendered cleanly in the relative frame.
- **CCSDS OEM** export of computed ephemerides for downstream tools.

**Doesn't care about (mostly).**
- Beautiful 3D models — wants correctness over polish.
- Catalog browsing — usually loads scenarios from saved files.

---

## Gita — GN&C Analyst *(secondary)*

**Role.** Guidance, navigation, and control. Focused on sensor performance,
attitude profiles, and the boundary between trajectory design and onboard
behavior.

**Background.** MS in aerospace or controls; ~5 years experience. Thinks about
field-of-view geometry, sensor noise budgets, sun-keep-out angles, body-frame
considerations.

**Goals.**
- Evaluate when a deputy is in a sensor's FOV during an approach.
- Check sensor pointing against sun keep-out constraints.
- Compute link budget / SNR overlays for RF and optical sensors.
- See attitude effects on sensor coverage as the chief slews.

**Pain points with existing tools.**
- Sensor modeling is often an afterthought, not integrated with the
  trajectory.
- Body-frame and gimbal-frame visualization is rare or clunky.
- Coupling between attitude profile and sensor coverage is hard to see.

**Optimize for her.**
- **Sensor objects** as first-class scenario entities with type, FOV
  geometry, range limits, pointing model.
- **Sensor-frame view** — camera looking through a selected sensor.
- **FOV volumes** rendered as translucent geometry in the proximity view.
- **Occlusion** against other spacecraft, Earth, Sun computed and
  visualized.
- **Acquisition/loss events** annotated on the timeline.

---

## Omar — Operations Engineer *(secondary)*

**Role.** Runs actual missions and rehearses procedures. The pointy end:
turns plans into operational timelines.

**Background.** BS or MS aerospace; mix of engineering and ops experience.
Cares about playback realism, timeline annotations, "what does the operator
see at T-minus N?".

**Goals.**
- Rehearse a saved scenario at variable time scale; pause, step, rewind.
- See the timeline annotated with maneuvers, eclipses, sensor windows,
  events.
- Capture snapshots/MP4 for briefings and post-event analysis.

**Pain points with existing tools.**
- Rehearsal-quality playback is rare; most tools jump between frames rather
  than animating smoothly.
- Snapshot export is often "screenshot and crop."

**Optimize for him.**
- **Smooth, scrubbable playback** at rates 0.01x–10000x including reverse.
- **Timeline annotations** for every event class.
- **PNG snapshots and MP4 sequences** exported from rendered canvases.
- **Step-frame controls** for precise event inspection.

---

## Eli — Educational user *(aspirational / tertiary)*

Mentioned for completeness. A student or trainee learning RPO concepts. The
tool's visualization quality and click-to-explore catalog browser open the
door to this audience. We don't optimize *for* Eli (Maya and Frank lead), but
we don't actively gate them out: honest UI labels, in-app glossary tooltips,
and sample scenarios serve them at zero cost to the primary audience.

---

## Persona-driven design heuristics

Quick decision aids derived from the above:

- **"Maya can't get this done in <2 minutes" → simplify the flow.** Authoring
  speed matters most for her.
- **"Frank can't trust this" → fix it before shipping.** Validation,
  reproducibility, audit are first-class.
- **"This breaks Gita's sensor workflow" → reconsider.** Sensor modeling is
  integrated, not bolted on.
- **"Omar can't rehearse smoothly" → fix the playback path.** Animation
  quality is non-negotiable.
- **"Eli won't understand without docs" → add a tooltip.** Cheap; pays back.
