# Glossary

Domain terms with precise definitions. The SRS lists acronyms (§1.3); this
expands them and adds the broader vocabulary the project needs. Use this when
a term in a conversation feels fuzzy — define it once here, reference from
there.

Sections:
- [Reference frames](#reference-frames)
- [Time systems](#time-systems)
- [Orbital mechanics fundamentals](#orbital-mechanics-fundamentals)
- [Orbital regimes](#orbital-regimes)
- [Propagators](#propagators)
- [Maneuvers and ΔV](#maneuvers-and-v)
- [RPO approach geometries](#rpo-approach-geometries)
- [Relative-motion shapes](#relative-motion-shapes)
- [Sensors and observability](#sensors-and-observability)
- [Environment](#environment)
- [Data formats](#data-formats)
- [System terms](#system-terms)

---

## Reference frames

**ECI** — Earth-Centered Inertial. Origin at Earth's center; axes fixed
relative to the stars (do not rotate with Earth). Used for orbital dynamics
(Newton's laws apply in inertial frames). Common realization: J2000 / ICRF.

**ECEF** — Earth-Centered Earth-Fixed. Origin at Earth's center; axes
rotate with Earth. Greenwich, London has the same (X, Y, Z) at all times.
Standard realization: ITRF, with IERS Earth-orientation corrections (polar
motion, UT1-UTC).

**Geodetic frame.** Latitude, longitude, altitude above the WGS84 reference
ellipsoid. Human-readable; what maps use.

**TEME** — True Equator Mean Equinox. The ECI variant that SGP4 outputs.
Close to J2000/ICRF; small frame-correction needed for precise work.

**LVLH** — Local Vertical Local Horizontal. A frame centered on a reference
spacecraft (the chief), with axes:
- **R** — radial, from Earth's center through the chief (away from Earth =
  positive).
- **In-track / S** — along the chief's velocity vector.
- **Cross-track / W** — orbit-normal (perpendicular to the orbital plane).

**RIC** — Radial / In-track / Cross-track. The same frame as LVLH,
sometimes with sign conventions differing. RIC = (R, I, C) where R is
radial-out, I is along-velocity, C is cross-track.

**Body frame.** Per-spacecraft, defined by the spacecraft's geometry (e.g.,
+Z out the nose). Driven by the attitude profile (quaternions or Euler
angles).

**Sensor frame.** Per-sensor, the camera frame for that sensor. Pointing
direction defines the boresight; FOV is expressed in this frame.

---

## Time systems

**UTC** — Coordinated Universal Time. Civil time standard with leap seconds.

**TAI** — International Atomic Time. Continuous (no leap seconds);
UTC = TAI − leap second offset.

**UT1** — Solar mean time at Greenwich. Drifts from UTC by up to ±0.9 s
before a leap-second correction.

**TT** — Terrestrial Time. Used for ephemerides; TT = TAI + 32.184 s.

**GMST** — Greenwich Mean Sidereal Time. Earth's rotation angle relative to
the vernal equinox. Used to rotate ECI → ECEF.

**Sidereal day.** ~23h 56m 4s, the time for Earth to rotate once relative
to the stars (vs the 24h solar day relative to the Sun).

**JD** — Julian Date. A single floating-point count of days since 4713 BC.
Useful because it's a single number (no calendar weirdness), monotonic, and
unambiguous about UTC.

**J2000 epoch.** 2000-01-01T12:00:00 TT (Julian Date 2451545.0). Reference
moment for the J2000 inertial frame.

---

## Orbital mechanics fundamentals

**State vector.** Position + velocity. Six numbers expressed in a specified
frame at a specified epoch. The propagator's input and output.

**Orbital elements (Keplerian).** Six numbers describing a two-body orbit:
- *Semi-major axis (a)* — orbit size.
- *Eccentricity (e)* — orbit shape (0 = circle, →1 = parabola).
- *Inclination (i)* — tilt of the orbital plane from the equator (deg).
- *Right ascension of ascending node (RAAN, Ω)* — orientation of the plane
  in inertial space.
- *Argument of perigee (ω)* — orientation of the ellipse within the plane.
- *True anomaly (ν)* — position along the orbit at the given epoch.

**Perigee / apogee.** Closest and farthest points of an Earth orbit
(perigee = perihelion-equivalent for Earth; apogee = aphelion-equivalent).
General names: **periapsis / apoapsis**.

**Orbital period.** Time for one complete orbit. For a circular orbit
of radius r: T = 2π √(r³/μ), where μ is Earth's gravitational parameter
(~398,600 km³/s²).

**Mean motion.** Angular rate around the orbit, often quoted in revolutions
per day. Related to period: n = 2π / T.

**Epoch.** A specific moment in time. State vectors and TLEs are anchored
to an epoch.

**Vis-viva equation.** v² = μ (2/r − 1/a). Relates speed to position and
semi-major axis; the workhorse of impulsive maneuver math.

**Two-body problem.** Two point masses interacting only gravitationally;
yields closed-form Keplerian orbits.

**Perturbations.** Forces beyond ideal two-body: non-spherical gravity
(Earth oblateness, higher harmonics), atmospheric drag, solar radiation
pressure, third-body gravity (Sun, Moon).

**J2 / J4.** Coefficients of the Earth's gravity-field harmonic expansion;
J2 (Earth's equatorial bulge) is dominant for LEO. J4 means "include terms
through J4."

**SRP** — Solar Radiation Pressure. Force from sunlight on the spacecraft;
small but accumulates.

---

## Orbital regimes

**LEO** — Low Earth Orbit. Altitude ~160–2000 km. Periods 90–120 min.
Atmospheric drag matters. ISS (~400 km), most Starlink (~550 km), Earth
observation.

**MEO** — Medium Earth Orbit. ~2,000–35,786 km. GPS (~20,200 km), Galileo,
GLONASS.

**GEO** — Geostationary Earth Orbit. 35,786 km altitude, equatorial. Period
~24 h. Spacecraft appears stationary from the ground.

**HEO** — Highly Elliptical Orbit. Eccentric, with long apogee dwell times.

**Sun-synchronous orbit (SSO).** A specific LEO where the orbital plane
precesses at the same rate Earth orbits the Sun, so the spacecraft passes
over each ground point at the same local solar time.

**Polar orbit.** Inclination ~90°; passes over the poles.

---

## Propagators

**Propagator.** An algorithm that advances a state vector forward (or
backward) in time, modeling the chosen forces.

**SGP4 / SDP4** — Simplified General/Deep-space Perturbations 4. Analytic
propagators that take a TLE and produce position+velocity. SGP4 for
LEO/MEO, SDP4 for periods ≥225 minutes. Accurate to ~1–3 km for ~1 week,
degrading further out.

**Numerical propagator.** Integrates the equations of motion step by step,
modeling chosen perturbations (gravity field, drag, SRP, third-body). High
fidelity, slower.

**DP8(7)** — Dormand-Prince 8(7). An adaptive-step Runge-Kutta integrator
common in high-fidelity propagation.

**Kepler propagator.** Two-body analytic (closed-form) propagator. Fast and
exact for the two-body assumption; ignores all perturbations.

**Clohessy-Wiltshire (CW) equations.** Linearized equations for relative
motion of a deputy with respect to a chief in a circular reference orbit.
Closed-form state transition matrix. Accurate for separations ≲10 km over
~1 orbital period.

**NRLMSISE-00.** A semi-empirical atmospheric density model used in drag
calculations. Inputs include solar and geomagnetic activity indices.

---

## Maneuvers and ΔV

**ΔV (delta-V).** A velocity change applied to a spacecraft. Units: m/s.
Cumulative ΔV is the proxy for fuel cost.

**Impulsive maneuver.** Idealization: instant ΔV at a single epoch. Good
approximation for short burns relative to the orbital period.

**Finite burn.** A burn extending over real time; parameterized by thrust
(N), specific impulse (Isp, seconds), duration. Required when burn time is
significant compared to orbital period.

**Specific impulse (Isp).** A measure of propulsion efficiency. Effectively
"seconds of thrust per unit weight of propellant." Higher = more efficient.

**Hohmann transfer.** Two-burn coplanar transfer between two circular
orbits at different altitudes. ΔV-optimal for circular-to-circular
coplanar transfers. The textbook intro to orbital maneuvers.

**Phasing maneuver.** Same-orbit adjustments to catch up to (or fall back
from) a target on the same orbit. Drop into a slightly different orbit for
N revolutions, then return.

**Plane change.** Changing the inclination or RAAN. Expensive — ΔV scales
with orbital velocity (~7.5 km/s at LEO) and the angle change. Cross-plane
rendezvous is dominated by this cost.

**Lambert's problem.** Given two position vectors and a time of flight,
find the transfer orbit connecting them. The targeting tool for "depart A,
arrive at B at time T." Iteratively solved.

**Glideslope.** A controlled descent profile — maintain a fixed approach
rate (m/s per m of range) as the deputy closes on the chief. Common for
docking.

**Station-keeping.** Maintenance burns to hold a spacecraft in a desired
orbit or relative position against drift.

---

## RPO approach geometries

**V-bar approach.** Approach along the chief's velocity direction (in-track
axis of LVLH). Common for ISS visiting vehicles, typically slow-closing
from behind or ahead.

**R-bar approach.** Approach along the radial direction. Coming from below
(R-bar from below) or above. Used for some docking profiles.

**H-bar approach.** Along the orbit-normal direction (cross-track). Rarely
used; ΔV-expensive due to plane-change geometry.

**Co-elliptic phasing.** Deputy in a slightly different orbit
(same plane, different altitude) so it phases relative to the chief over
multiple revolutions.

**Hold point.** A specific relative position (in LVLH) the deputy parks at
to wait for a Go.

**Walking safety ellipse.** A relative orbit shape that satisfies passive
abort safety: even with no further maneuvers, the deputy doesn't collide.

---

## Relative-motion shapes

**Football.** The closed elliptical relative trajectory traced by a deputy
in a CW-natural orbit when viewed in LVLH (R-S plane). Looks like an oval.

**NMC** — Natural Motion Circumnavigation. A relative orbit where the
deputy circles the chief naturally (without thrust) due to CW dynamics.
Shapes vary with the geometry.

**Baseball orbit.** A specific NMC shape; named for its trajectory pattern
in LVLH.

**The bubble.** Casual term for the volume around the chief defined by
safety constraints (approach corridor, keep-out zones).

---

## Sensors and observability

**FOV** — Field of View. The angular extent a sensor can observe. Often
specified as cone half-angle or rectangular HxV degrees.

**Boresight.** The center axis of a sensor's FOV.

**Pointing.** The direction the sensor is aimed. Can be **body-fixed**
(stuck to spacecraft body) or **gimbaled** (steered by an actuator).

**Acquisition / loss-of-sight.** Events: deputy enters/exits a sensor's
FOV (and the LoS is unobstructed).

**Occlusion.** Something (another spacecraft, Earth, Sun) blocks the line
of sight.

**Sun keep-out angle.** Minimum angle between the sensor boresight and the
Sun; staring at the Sun damages cameras.

**Link budget.** Engineering accounting of RF or optical link gains and
losses; output is the signal-to-noise ratio at the receiver.

**Plume impingement.** Hot exhaust gas from one spacecraft's thruster
hitting another spacecraft.

---

## Environment

**Umbra.** Full Earth shadow — Sun completely blocked.

**Penumbra.** Partial shadow — Sun partly blocked by Earth.

**Day/night terminator.** The dividing line on Earth between illuminated
and dark hemispheres.

**Albedo.** Reflectance of Earth's surface; affects illumination of
spacecraft in proximity views.

---

## Data formats

**TLE** — Two-Line Element set. The classic compact format for orbital
elements:
- Line 1: NORAD ID, epoch, drag terms.
- Line 2: inclination, RAAN, eccentricity, argument of perigee, mean
  anomaly, mean motion.

A TLE is paired with the SGP4 propagator; not directly readable as a state
vector.

**CCSDS** — Consultative Committee for Space Data Systems. The standards
body for interchange formats.

**OEM** — Orbit Ephemeris Message. CCSDS format for sequences of state
vectors over time. The standard handoff format for propagated trajectories.

**OPM** — Orbit Parameter Message. CCSDS format for a single state at a
single epoch.

**AEM** — Attitude Ephemeris Message. CCSDS format for attitude over time
(quaternions or Euler angles).

**CZML** — Cesium Markup Language. JSON-based time-dynamic data format,
native to Cesium. Used for streaming positions, orientations, and other
time-varying properties to the global view.

---

## System terms

**Chief.** The designated reference spacecraft in a scenario. Origin of the
LVLH frame. Visualized at the proximity view's origin.

**Deputy / deputies.** Other spacecraft in a scenario, expressed relative
to the chief.

**Catalog.** The live set of ~14,500 active satellites with public TLEs.
Served as a shared real-time backend stream; the composition source for
scenarios.

**Scenario.** The central persistent artifact — one chief + deputies +
initial states + maneuvers + sensors + attitude. Versioned, owner-tagged,
auditable.

**Composer / scenario composer.** The frontend state tracking the
in-progress scenario being built: which chief, which deputies, dirty flag,
etc.

**Global view.** The Cesium-rendered Earth+orbits viewport.

**Proximity view.** The three.js-rendered LVLH viewport.

**Streaming contract.** The REST + WebSocket + CZML interface between
backend (propagation) and frontend (rendering). The decoupling seam.

**Catalog mode / scenario mode.** The two backend propagation/streaming
profiles. Catalog = one shared SGP4 pass over all active sats; Scenario =
per-user, ≤10 spacecraft, selected fidelity.

**Click-to-inspect.** UX pattern: clicking a satellite shows its details in
the info panel without committing it to any scenario role. Role assignment
is an explicit button click.
