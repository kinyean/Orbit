package space.orbit.backend.stream;

import jakarta.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.errors.OrekitException;
import org.orekit.frames.Frame;
import org.orekit.frames.Transform;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.Ephemeris;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;
import org.orekit.utils.PVCoordinatesProvider;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.analysis.ConjunctionEvent;
import space.orbit.backend.analysis.ConjunctionEventComputer;
import space.orbit.backend.analysis.ConstraintChecker;
import space.orbit.backend.analysis.ConstraintViolationEvent;
import space.orbit.backend.analysis.EclipseEvent;
import space.orbit.backend.analysis.EclipseEventComputer;
import space.orbit.backend.analysis.SampledCraft;
import space.orbit.backend.analysis.SampledGeocentricCraft;
import space.orbit.backend.analysis.SensorEvent;
import space.orbit.backend.analysis.LinkBudgetComputer;
import space.orbit.backend.analysis.LinkBudgetSeries;
import space.orbit.backend.analysis.SensorEventComputer;
import space.orbit.backend.io.MeasuredEphemeris;
import space.orbit.backend.prop.CwPropagation;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.MeasuredAttitude;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.QuaternionSamples;
import space.orbit.backend.scenario.MeasuredDataset;
import space.orbit.backend.scenario.MeasuredDatasetCodec;
import space.orbit.backend.scenario.MeasuredDatasetRepository;
import space.orbit.backend.scenario.MeasuredEphemerisFactory;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioService;

/**
 * Turns a saved scenario into a precomputed ephemeris payload for the
 * per-scenario stream (Phase 4, US-STREAM-02). Scenarios are bounded (≤11 sats,
 * finite {@code [start,end]}), so the whole ephemeris is computed once on
 * connect and pushed; client playback is then pure clock math over the samples
 * (Decision 11 — backend authoritative on state, frontend owns playback).
 *
 * <p>Determinism (R11, SRS §5.4.1): sequential ordered sampling (chief, then
 * deputies in body order), pinned settings, frozen TLEs, no wall-clock / RNG —
 * so {@link #loadAndEncode} is a pure function of {@code (body)} and supports a
 * byte-compare rerun test.
 */
@Service
@DependsOn("orekitConfig")
public class ScenarioStreamService {

    private final ScenarioService scenarioService;
    private final PropagationService propagationService;
    private final FrameService frames;
    private final CzmlEncoder encoder;
    private final RelativeStateEncoder relativeEncoder;
    private final ScenarioStreamProperties props;
    private final MeasuredDatasetRepository measuredDatasets;

    private TimeScale utc;
    private SensorEventComputer sensorEvents;
    private EclipseEventComputer eclipseEvents;
    private ConjunctionEventComputer conjunctionEvents;
    private ConstraintChecker constraintChecker;
    private LinkBudgetComputer linkBudgetComputer;

    /** Default intra-scenario conjunction threshold (m) when the body sets none (Phase 8). */
    private static final double DEFAULT_MISS_THRESHOLD_M = 5000.0;

    public ScenarioStreamService(ScenarioService scenarioService,
                                 PropagationService propagationService,
                                 FrameService frames,
                                 CzmlEncoder encoder,
                                 RelativeStateEncoder relativeEncoder,
                                 ScenarioStreamProperties props,
                                 MeasuredDatasetRepository measuredDatasets) {
        this.scenarioService = scenarioService;
        this.propagationService = propagationService;
        this.frames = frames;
        this.encoder = encoder;
        this.relativeEncoder = relativeEncoder;
        this.props = props;
        this.measuredDatasets = measuredDatasets;
    }

    @PostConstruct
    void init() {
        utc = TimeScalesFactory.getUTC(); // safe: @DependsOn orekitConfig
        sensorEvents = new SensorEventComputer(); // pure: works on the sampled trajectory, no propagation
        eclipseEvents = new EclipseEventComputer(); // pure: works on the sampled trajectory (Phase 8)
        conjunctionEvents = new ConjunctionEventComputer(); // pure (Phase 8)
        constraintChecker = new ConstraintChecker(); // pure (Phase 8)
        linkBudgetComputer = new LinkBudgetComputer(); // pure (Phase 9D)
    }

    /**
     * Load the scenario (owned by {@code callerEmail}), propagate every role over
     * its time range at the effective step, and encode the global-view CZML.
     *
     * @throws space.orbit.backend.scenario.ScenarioNotFoundException missing / soft-deleted / not owned
     * @throws ScenarioStreamUnprocessableException non-TLE state, or a TLE that won't parse
     */
    public EncodedScenario loadAndEncode(UUID id, String callerEmail) {
        ScenarioBody body = scenarioService.bodyForStream(id, callerEmail);

        Fidelity fidelity = Fidelity.fromString(body.fidelity());
        Instant start = parseInstant(body.timeRange().start());
        Instant end = parseInstant(body.timeRange().end());
        long durationSec = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
        AbsoluteDate startDate = new AbsoluteDate(start, utc);

        // Prepare each role up front so we know the periods before sampling. In CW
        // mode (US-REL-03, Phase 5C) the chief propagates normally (SGP4 — CW models
        // the *relative* dynamics) and each deputy is a closed-form CW provider
        // seeded from its own state at the start epoch.
        Fidelity chiefFidelity = fidelity == Fidelity.CW ? Fidelity.SGP4 : fidelity;
        PreparedRole chief = prepareRole(body.chief(), chiefFidelity);

        // Propagation can fail when a spacecraft leaves the model's valid domain over
        // the time range — most commonly numerical decay (drag) or a maneuver driving
        // the orbit below the surface, which surfaces as an Orekit "point is inside
        // ellipsoid". Treat that as an UNPROCESSABLE scenario (clean 4422 close) rather
        // than a server error (1011), so the client stops instead of hammering reconnects.
        try {
            List<PreparedRole> roles = new ArrayList<>();
            roles.add(chief);
            for (ScenarioBody.Role deputy : body.deputies()) {
                roles.add(fidelity == Fidelity.CW
                        ? prepareCwDeputy(deputy, chief, startDate)
                        : prepareRole(deputy, fidelity));
            }

            // Sample a margin (the longest half-trail) before start and after end so
            // the path's one-orbit window always has data — otherwise the trail is
            // short near the start and "grows" as you scrub forward (R8 stays: the
            // step still respects the sample cap, now over the margined span).
            double maxPeriod = roles.stream().mapToDouble(PreparedRole::periodSeconds).max().orElse(5400.0);
            long marginSec = (long) Math.ceil(PATH_PERIODS / 2.0 * maxPeriod);
            long spanSec = durationSec + 2L * marginSec;
            int effectiveStep = effectiveStep(spanSec);
            int steps = (int) (spanSec / effectiveStep);
            double firstT = -marginSec; // seconds relative to the scenario start (the CZML epoch)

            List<ScenarioSatelliteSamples> samples = new ArrayList<>();
            for (PreparedRole role : roles) {
                samples.add(sampleRole(role, startDate, firstT, effectiveStep, steps));
            }
            String czml = encoder.encodeScenario(start, effectiveStep, samples);

            // Relative-state (proximity view, 4B): chief-LVLH R/I/C per deputy, on the
            // SAME time grid so both views interpolate to the same instants. Also carries
            // each deputy's closest approach over [start,end] (US-REL-02, Phase 5A) and a
            // CW validity hint (US-REL-03, Phase 5C).
            String relative = encodeRelative(roles, startDate, firstT, effectiveStep, steps, start,
                    durationSec, body.fidelity(), chief.eccentricity(), body.missDistanceThresholdM());

            return new EncodedScenario(czml, relative, effectiveStep);
        } catch (OrekitException oe) {
            throw new ScenarioStreamUnprocessableException(
                    "a spacecraft leaves the propagation model's valid domain within the time range"
                            + " (orbital decay or a maneuver puts it below the surface) — shorten the"
                            + " time range or revise the maneuver: " + oe.getMessage());
        }
    }

    /**
     * Encode each deputy's position (and optionally velocity) in the chief's LVLH
     * frame. R15: the frame is built ONCE from the <em>live</em> chief propagator
     * and applied per step — never via {@code FrameService.toRelativeState}, whose
     * single-epoch constant provider drops the frame rotation rate and would give
     * a wrong relative <em>velocity</em>.
     */
    private String encodeRelative(List<PreparedRole> roles, AbsoluteDate startDate,
                                  double firstT, int step, int steps, Instant epoch,
                                  long durationSec, String fidelity, double chiefEccentricity,
                                  Double missThresholdM) {
        PreparedRole chief = roles.get(0);
        Frame eci = frames.eci();
        Frame lvlh = frames.lvlh(chief.provider()); // rotating LVLH over the live chief orbit
        // Chief geocentric radius at the epoch (Phase 6 / US-PROX-05) — places the
        // Earth backdrop along −R in the LVLH scene. A single representative value
        // (eccentric chiefs vary it slightly over the orbit) is enough for context.
        double chiefRadiusM = chief.provider().getPVCoordinates(startDate, eci).getPosition().getNorm();
        boolean withVel = props.includeRelativeVelocity();
        int stride = withVel ? 7 : 4;
        int degree = Math.max(1, Math.min(5, steps));
        // Absolute epoch seconds of the CZML epoch — the SLERP key base for a measured
        // attitude series (whose times are absolute), since loop-time t is seconds-since-epoch.
        double epochSec = epoch.toEpochMilli() / 1000.0;

        // Chief body attitude in its own LVLH frame (Phase 7) so the chief's FOV
        // volumes render. The chief is the LVLH origin (no relative position). Its
        // geocentric ECI position is captured for free (Phase 8 eclipse input).
        double[] chiefGeoEci = new double[(steps + 1) * 4];
        double[] chiefAttitude =
                sampleAttitude(chief, eci, lvlh, startDate, firstT, step, steps, epochSec, chiefGeoEci);
        // Geocentric ECI tracks for eclipse (Phase 8) — captured in increasing time
        // order inside the existing loops (no extra propagation, R11/R15).
        List<SampledGeocentricCraft> geoCrafts = new ArrayList<>();
        geoCrafts.add(new SampledGeocentricCraft(chief.role().noradId(), chiefGeoEci));

        double maxSeparation = 0.0; // over all deputies, within the scenario window (CW hint)
        List<RelativeSamples> deputies = new ArrayList<>();
        for (int i = 1; i < roles.size(); i++) { // skip chief (the LVLH origin)
            PreparedRole prepared = roles.get(i);
            PVCoordinatesProvider depProp = prepared.provider();
            ScenarioBody.Role role = prepared.role();
            double[] tmpQ4 = new double[4]; // reused SLERP scratch for measured attitude
            double[] s = new double[(steps + 1) * stride];
            double[] a = new double[(steps + 1) * ATT_STRIDE]; // [t,qx,qy,qz,qw, ...]
            double[] geo = new double[(steps + 1) * 4]; // [t,x,y,z, ...] geocentric ECI (eclipse)
            // Coarse closest-approach bracket comes free from the sampling loop:
            // |rel| is the chief-relative range (frame-invariant). Restrict to the
            // scenario window [0, durationSec] (the sampled span also has margin).
            double coarseMinDist = Double.POSITIVE_INFINITY;
            double coarseMinT = 0.0;
            // HOLD the last valid relative state past a decay / domain exit (see sampleRole).
            int firstValid = -1;
            boolean decayed = false; // once a step leaves the domain, stop re-propagating
            double hR = 0, hI = 0, hC = 0, hvR = 0, hvI = 0, hvC = 0;
            double hqx = 0, hqy = 0, hqz = 0, hqw = 1; // held attitude quaternion
            double hex = 0, hey = 0, hez = 0; // held geocentric ECI position (eclipse)
            for (int k = 0; k <= steps; k++) {
                double t = firstT + (double) k * step;
                int base = k * stride;
                s[base] = t;
                a[k * ATT_STRIDE] = t;
                geo[k * 4] = t;
                if (!decayed) {
                    try {
                        AbsoluteDate date = startDate.shiftedBy(t);
                        Transform toLvlh = eci.getTransformTo(lvlh, date);
                        PVCoordinates depEci = depProp.getPVCoordinates(date, eci);
                        PVCoordinates rel = toLvlh.transformPVCoordinates(depEci);
                        Vector3D p = rel.getPosition();
                        Vector3D pe = depEci.getPosition(); // geocentric ECI (eclipse)
                        hex = pe.getX();
                        hey = pe.getY();
                        hez = pe.getZ();
                        hR = p.getX(); // radial
                        hI = p.getY(); // in-track
                        hC = p.getZ(); // cross-track
                        if (withVel) {
                            Vector3D v = rel.getVelocity(); // carries the LVLH rotation rate (R15)
                            hvR = v.getX();
                            hvI = v.getY();
                            hvC = v.getZ();
                        }
                        // Body attitude in the chief-LVLH scene frame (Phase 7) — reuse the
                        // same state + transform (no extra propagation). Measured roles
                        // (slice 2) SLERP their dataset quaternions; else modeled lvlh/fixed.
                        double[] q = bodyAttitude(prepared, depEci, toLvlh, epochSec + t, tmpQ4);
                        hqx = q[0];
                        hqy = q[1];
                        hqz = q[2];
                        hqw = q[3];
                        if (firstValid < 0) {
                            firstValid = k;
                        }
                        if (t >= 0.0 && t <= durationSec) {
                            double d = p.getNorm();
                            if (d < coarseMinDist) {
                                coarseMinDist = d;
                                coarseMinT = t;
                            }
                            maxSeparation = Math.max(maxSeparation, d);
                        }
                    } catch (OrekitException leftDomain) {
                        // Trailing decay/re-entry (we already have a valid sample): HOLD the
                        // rest, stop re-propagating (costly). A leading gap (no valid sample
                        // yet — e.g. margin before a measured ephemeris starts) keeps trying.
                        if (firstValid >= 0) {
                            decayed = true;
                        }
                    }
                }
                s[base + 1] = hR;
                s[base + 2] = hI;
                s[base + 3] = hC;
                if (withVel) {
                    s[base + 4] = hvR;
                    s[base + 5] = hvI;
                    s[base + 6] = hvC;
                }
                a[k * ATT_STRIDE + 1] = hqx;
                a[k * ATT_STRIDE + 2] = hqy;
                a[k * ATT_STRIDE + 3] = hqz;
                a[k * ATT_STRIDE + 4] = hqw;
                geo[k * 4 + 1] = hex;
                geo[k * 4 + 2] = hey;
                geo[k * 4 + 3] = hez;
            }
            if (firstValid < 0) {
                throw new ScenarioStreamUnprocessableException("deputy "
                        + roles.get(i).role().noradId() + " never reaches a valid relative state");
            }
            backfillLeading(s, firstValid, stride);
            backfillLeadingAttitude(a, firstValid);
            backfillLeading(geo, firstValid, 4);
            geoCrafts.add(new SampledGeocentricCraft(role.noradId(), geo));
            // Refine the closest approach on the live propagators around the coarse
            // bracket (US-REL-02). Distance is frame-invariant — compute it in ECI.
            double[] tca = refineTca(depProp, chief.provider(), eci, startDate,
                    Math.max(0.0, coarseMinT - step), Math.min((double) durationSec, coarseMinT + step),
                    coarseMinT, coarseMinDist);
            Instant tcaEpoch = epoch.plusMillis(Math.round(tca[0] * 1000.0));

            deputies.add(new RelativeSamples(role.noradId(), role.name(), degree, s, tcaEpoch, tca[1],
                    a, role.sensors()));
        }

        // Environment pass (Phase 8, US-ENV-01/02): Sun/Moon LVLH unit directions on the
        // grid (lighting) + the Sun's geocentric ECI per step (eclipse). Analytic ephemeris
        // + one frame transform per step — no craft propagation, so determinism holds (R11)
        // and the live propagators are never queried out of order. Directions go through
        // transformVector (rotation only, R15). Eclipse runs on the geocentric ECI tracks.
        // Computed before the constraint check so sun-keep-out can use the Sun vector.
        //
        // The LVLH frame wraps the chief provider, so `eci.getTransformTo(lvlh, date)` throws
        // for a MEASURED (tabulated-ephemeris) chief over the margin steps OUTSIDE its data
        // window. Guard per step with the same leading-gap-retry / trailing-HOLD pattern as the
        // position/attitude samplers (a bare throw here would 4422 the whole stream). The Sun
        // ECI itself is analytic — always available — so eclipse stays correct over the margin.
        double[] sunVector = new double[(steps + 1) * 4];
        double[] moonVector = new double[(steps + 1) * 4];
        double[] sunEci = new double[(steps + 1) * 4]; // un-normalized (the predicate needs |S|)
        int firstValidEnv = -1;
        boolean envDecayed = false;
        double hsx = 0, hsy = 0, hsz = 0, hmx = 0, hmy = 0, hmz = 0; // held LVLH directions
        for (int k = 0; k <= steps; k++) {
            double t = firstT + (double) k * step;
            AbsoluteDate date = startDate.shiftedBy(t);
            sunVector[k * 4] = t;
            moonVector[k * 4] = t;
            sunEci[k * 4] = t;
            // Sun ECI is analytic (no chief dependency) — fill it at every step for eclipse.
            Vector3D sunPos = frames.sunPosition(date);
            sunEci[k * 4 + 1] = sunPos.getX();
            sunEci[k * 4 + 2] = sunPos.getY();
            sunEci[k * 4 + 3] = sunPos.getZ();
            if (!envDecayed) {
                try {
                    Transform eciToLvlh = eci.getTransformTo(lvlh, date);
                    double[] sd = FrameService.directionInLvlh(sunPos, eciToLvlh);
                    double[] md = FrameService.directionInLvlh(frames.moonPosition(date), eciToLvlh);
                    hsx = sd[0];
                    hsy = sd[1];
                    hsz = sd[2];
                    hmx = md[0];
                    hmy = md[1];
                    hmz = md[2];
                    if (firstValidEnv < 0) {
                        firstValidEnv = k;
                    }
                } catch (OrekitException leftDomain) {
                    // Leading margin (before a measured ephemeris starts): keep trying.
                    // Trailing margin (after it ends): HOLD the last valid direction.
                    if (firstValidEnv >= 0) {
                        envDecayed = true;
                    }
                }
            }
            sunVector[k * 4 + 1] = hsx;
            sunVector[k * 4 + 2] = hsy;
            sunVector[k * 4 + 3] = hsz;
            moonVector[k * 4 + 1] = hmx;
            moonVector[k * 4 + 2] = hmy;
            moonVector[k * 4 + 3] = hmz;
        }
        // Backfill any leading held directions with the first valid sample.
        backfillLeading(sunVector, Math.max(firstValidEnv, 0), 4);
        backfillLeading(moonVector, Math.max(firstValidEnv, 0), 4);

        List<EclipseEvent> eclipses =
                eclipseEvents.compute(geoCrafts, sunEci, firstT, step, steps, epoch, durationSec);

        // One SampledCraft list (LVLH position + attitude + sensors) feeds all three
        // LVLH-frame analyses — sensor events, conjunctions, constraints — over the SAME
        // rendered samples (NOT a second propagation), so every event is consistent with
        // the drawn scene + the closest approach (Decision 24). Chief pos=null → LVLH origin.
        List<SampledCraft> craftsLvlh = new ArrayList<>(deputies.size() + 1);
        craftsLvlh.add(new SampledCraft(chief.role().noradId(), null, stride, chiefAttitude, chief.role().sensors()));
        for (RelativeSamples d : deputies) {
            craftsLvlh.add(new SampledCraft(d.noradId(), d.samples(), stride, d.attitude(), d.sensors()));
        }

        // Acquisition / loss-of-sight (Phase 7, US-EVT-01) — only when some craft has a sensor.
        List<SensorEvent> events = anySensors(craftsLvlh)
                ? sensorEvents.compute(craftsLvlh, firstT, step, steps, epoch, durationSec, chiefRadiusM)
                : List.of();
        // Intra-scenario conjunctions (Phase 8, US-EVT-02) — always evaluated (cheap pairwise,
        // formation safety); only pairs closer than the threshold are reported.
        double threshold = missThresholdM != null ? missThresholdM : DEFAULT_MISS_THRESHOLD_M;
        List<ConjunctionEvent> conjunctions =
                conjunctionEvents.compute(craftsLvlh, threshold, firstT, step, steps, epoch, durationSec);
        // Constraint violations (Phase 8, US-EVT-03) — sun-keep-out + approach-corridor.
        List<ScenarioBody.Constraint> constraints = gatherConstraints(roles);
        List<ConstraintViolationEvent> violations = constraints.isEmpty()
                ? List.of()
                : constraintChecker.compute(craftsLvlh, sunVector, constraints, firstT, step, steps, epoch, durationSec);
        // Link-budget SNR series (Phase 9D, US-EVT-05) — only when a sensor carries one.
        List<LinkBudgetSeries> linkBudgets = anyLinkBudget(craftsLvlh)
                ? linkBudgetComputer.compute(craftsLvlh, firstT, step, steps)
                : List.of();

        return relativeEncoder.encodeRelative(epoch, step, chief.role().noradId(),
                chiefAttitude, chief.role().sensors(), deputies, withVel,
                fidelity, maxSeparation, chiefEccentricity, chiefRadiusM, events,
                sunVector, moonVector, eclipses, conjunctions, violations, linkBudgets);
    }

    /** True when any craft in the scene carries a sensor (gates sensor-event computation). */
    private static boolean anySensors(List<SampledCraft> crafts) {
        for (SampledCraft c : crafts) {
            if (c.sensors() != null && !c.sensors().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** True when any sensor carries a link budget (gates link-budget computation, Phase 9D). */
    private static boolean anyLinkBudget(List<SampledCraft> crafts) {
        for (SampledCraft c : crafts) {
            if (c.sensors() == null) {
                continue;
            }
            for (ScenarioBody.Sensor s : c.sensors()) {
                if (s.linkBudget() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Gather every role's constraints into one flat list (Phase 8). */
    private static List<ScenarioBody.Constraint> gatherConstraints(List<PreparedRole> roles) {
        List<ScenarioBody.Constraint> all = new ArrayList<>();
        for (PreparedRole r : roles) {
            if (r.role().constraints() != null) {
                all.addAll(r.role().constraints());
            }
        }
        return all;
    }

    /** Attitude sample stride: {@code [t,qx,qy,qz,qw]} (matches the encoder). */
    private static final int ATT_STRIDE = 5;

    /**
     * Sample a role's body attitude (three.js-convention quaternion in the chief-LVLH
     * scene frame) on the position grid (Phase 7). HOLDs the last valid quaternion past
     * a domain exit, mirroring the position sampler. Used for the chief (the deputy loop
     * computes its own attitude inline to reuse the per-step transform).
     *
     * <p>{@code geoEciOut} (Phase 8) is a caller-allocated stride-4 array
     * {@code [t,x,y,z, ...]} filled with the role's geocentric ECI position per step
     * (captured for free from the same {@code pv} — no extra propagation), with the
     * same HOLD/backfill semantics; it feeds eclipse detection. Pass {@code null} to skip.
     */
    private double[] sampleAttitude(PreparedRole role, Frame eci, Frame lvlh, AbsoluteDate startDate,
                                    double firstT, int step, int steps, double epochSec, double[] geoEciOut) {
        double[] a = new double[(steps + 1) * ATT_STRIDE];
        double[] tmp4 = new double[4];
        int firstValid = -1;
        boolean decayed = false;
        double hqx = 0, hqy = 0, hqz = 0, hqw = 1;
        double hex = 0, hey = 0, hez = 0; // held geocentric ECI position
        for (int k = 0; k <= steps; k++) {
            double t = firstT + (double) k * step;
            a[k * ATT_STRIDE] = t;
            if (geoEciOut != null) {
                geoEciOut[k * 4] = t;
            }
            if (!decayed) {
                try {
                    AbsoluteDate date = startDate.shiftedBy(t);
                    PVCoordinates pv = role.provider().getPVCoordinates(date, eci);
                    Transform toLvlh = eci.getTransformTo(lvlh, date);
                    double[] q = bodyAttitude(role, pv, toLvlh, epochSec + t, tmp4);
                    hqx = q[0];
                    hqy = q[1];
                    hqz = q[2];
                    hqw = q[3];
                    Vector3D pe = pv.getPosition();
                    hex = pe.getX();
                    hey = pe.getY();
                    hez = pe.getZ();
                    if (firstValid < 0) {
                        firstValid = k;
                    }
                } catch (OrekitException leftDomain) {
                    // Same leading-gap vs trailing-decay handling as the position sampler.
                    if (firstValid >= 0) {
                        decayed = true;
                    }
                }
            }
            a[k * ATT_STRIDE + 1] = hqx;
            a[k * ATT_STRIDE + 2] = hqy;
            a[k * ATT_STRIDE + 3] = hqz;
            a[k * ATT_STRIDE + 4] = hqw;
            if (geoEciOut != null) {
                geoEciOut[k * 4 + 1] = hex;
                geoEciOut[k * 4 + 2] = hey;
                geoEciOut[k * 4 + 3] = hez;
            }
        }
        // If the chief never reached a valid state the whole stream already fails in
        // sampleRole; here we just backfill any leading held identity quaternions.
        backfillLeadingAttitude(a, Math.max(firstValid, 0));
        if (geoEciOut != null) {
            backfillLeading(geoEciOut, Math.max(firstValid, 0), 4);
        }
        return a;
    }

    /**
     * Body-attitude quaternion (three.js body→scene-LVLH) for a role at this step, given
     * the craft's ECI state + ECI→LVLH transform already computed for the step. A
     * <b>measured</b> role (slice 2) SLERP-interpolates its dataset quaternions
     * (HOLD-clamped at the ends) at the step's absolute epoch seconds {@code qSec} and
     * feeds the result through the same {@code "fixed"} path (body→ECI → scene basis), so
     * measured and modeled attitude share one code path. Non-measured roles use the
     * modeled {@code lvlh}/{@code fixed} attitude. {@code tmp4} is a reused scratch buffer.
     */
    private double[] bodyAttitude(PreparedRole role, PVCoordinates craftEci, Transform eciToLvlh,
                                  double qSec, double[] tmp4) {
        double[] measured = role.measuredAttXyzw();
        if (measured != null) {
            QuaternionSamples.sampleAt(measured, qSec, tmp4);
            return frames.bodyQuaternionInLvlh(craftEci, eciToLvlh, "fixed", tmp4);
        }
        return frames.bodyQuaternionInLvlh(craftEci, eciToLvlh,
                attitudeMode(role.role()), attitudeQuat(role.role()));
    }

    /** Copy the first valid quaternion back over any leading held-identity samples. */
    private static void backfillLeadingAttitude(double[] att, int firstValidIdx) {
        if (firstValidIdx <= 0) {
            return;
        }
        double qx = att[firstValidIdx * ATT_STRIDE + 1];
        double qy = att[firstValidIdx * ATT_STRIDE + 2];
        double qz = att[firstValidIdx * ATT_STRIDE + 3];
        double qw = att[firstValidIdx * ATT_STRIDE + 4];
        for (int k = 0; k < firstValidIdx; k++) {
            att[k * ATT_STRIDE + 1] = qx;
            att[k * ATT_STRIDE + 2] = qy;
            att[k * ATT_STRIDE + 3] = qz;
            att[k * ATT_STRIDE + 4] = qw;
        }
    }

    private static String attitudeMode(ScenarioBody.Role role) {
        ScenarioBody.AttitudeProfile a = role.attitude();
        return (a == null || a.mode() == null || a.mode().isBlank()) ? "lvlh" : a.mode();
    }

    private static double[] attitudeQuat(ScenarioBody.Role role) {
        ScenarioBody.AttitudeProfile a = role.attitude();
        return a == null ? null : a.quaternion();
    }

    /**
     * Golden-section refine of the chief-relative minimum range over {@code [lo,hi]}
     * (seconds relative to {@code startDate}). Fixed iteration count → deterministic
     * (R11). Falls back to the coarse bracket when the interval is degenerate.
     *
     * @return {@code [tBestSeconds, minDistanceMetres]}
     */
    private double[] refineTca(PVCoordinatesProvider dep, PVCoordinatesProvider chief, Frame eci,
                               AbsoluteDate startDate, double lo, double hi, double coarseT, double coarseDist) {
        if (!(hi > lo)) {
            return new double[] {coarseT, coarseDist};
        }
        final double gr = (Math.sqrt(5.0) - 1.0) / 2.0; // 0.6180339887...
        double a = lo;
        double b = hi;
        double c = b - gr * (b - a);
        double d = a + gr * (b - a);
        double fc = relRange(dep, chief, eci, startDate, c);
        double fd = relRange(dep, chief, eci, startDate, d);
        for (int it = 0; it < 60; it++) {
            if (fc < fd) {
                b = d;
                d = c;
                fd = fc;
                c = b - gr * (b - a);
                fc = relRange(dep, chief, eci, startDate, c);
            } else {
                a = c;
                c = d;
                fc = fd;
                d = a + gr * (b - a);
                fd = relRange(dep, chief, eci, startDate, d);
            }
        }
        double tBest = 0.5 * (a + b);
        double fBest = relRange(dep, chief, eci, startDate, tBest);
        // Keep the coarse grid point if the refine somehow overshot it.
        return fBest <= coarseDist ? new double[] {tBest, fBest} : new double[] {coarseT, coarseDist};
    }

    /** Chief-relative range (metres) at {@code t} seconds after {@code startDate}, in ECI.
     *  Returns +∞ if either body has left the propagator's valid domain at {@code t}
     *  (a decayed point is not a closest approach) so the golden-section refine stays robust. */
    private double relRange(PVCoordinatesProvider dep, PVCoordinatesProvider chief, Frame eci,
                            AbsoluteDate startDate, double t) {
        try {
            AbsoluteDate date = startDate.shiftedBy(t);
            Vector3D pDep = dep.getPVCoordinates(date, eci).getPosition();
            Vector3D pChief = chief.getPVCoordinates(date, eci).getPosition();
            return Vector3D.distance(pDep, pChief);
        } catch (OrekitException leftDomain) {
            return Double.POSITIVE_INFINITY;
        }
    }

    /** Total orbit-path length, in orbital periods (must match the encoder's lead+trail). */
    private static final double PATH_PERIODS = 1.5;

    /**
     * A role with its built state provider + period (+ eccentricity for the CW
     * validity hint), prepared before sampling. The provider is a {@link Propagator}
     * for SGP4/numerical and a closed-form CW provider for a CW deputy — sampling
     * only needs the {@link PVCoordinatesProvider} interface, so both fit uniformly.
     */
    private record PreparedRole(ScenarioBody.Role role, PVCoordinatesProvider provider,
                                double periodSeconds, double eccentricity, double inclinationDeg,
                                double[] measuredAttXyzw) {
        /** Non-measured roles (TLE/numerical/CW) carry no measured-attitude series. */
        private PreparedRole(ScenarioBody.Role role, PVCoordinatesProvider provider,
                             double periodSeconds, double eccentricity, double inclinationDeg) {
            this(role, provider, periodSeconds, eccentricity, inclinationDeg, null);
        }
    }

    private PreparedRole prepareRole(ScenarioBody.Role role, Fidelity fidelity) {
        ScenarioBody.InitialState state = role.initialState();
        if (state != null && "ephemeris".equals(state.kind())) {
            return prepareEphemerisRole(role, state.datasetId());
        }
        TLE tle = rebuildTle(role);
        // A maneuvered role is propagated numerically with the impulses attached
        // (Phase 5B); the 3-arg overload no-ops to the plain dispatch when empty.
        Propagator propagator = propagationService.propagatorFor(tle, fidelity, toImpulses(role.maneuvers()));
        // Orbital period from the TLE mean motion (rad/s); ≥60 s guards odd elements.
        double periodSeconds = Math.max(60.0, 2.0 * Math.PI / tle.getMeanMotion());
        return new PreparedRole(role, propagator, periodSeconds, tle.getE(), Math.toDegrees(tle.getI()));
    }

    /**
     * Prepare a role backed by a stored measured ephemeris (Decision: measured-data
     * ingestion): decode the dataset's samples into Orekit {@link SpacecraftState}s
     * (EME2000) and serve them through a tabulated {@link Ephemeris} — a
     * {@link PVCoordinatesProvider}, so the sampling loop is unchanged. Outside the
     * data window the ephemeris throws; the per-sample HOLD in {@link #sampleRole}
     * (leading gap retries until data starts, trailing holds the last state) covers
     * the margin. Period/inclination/eccentricity come from the first state.
     */
    private PreparedRole prepareEphemerisRole(ScenarioBody.Role role, String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new ScenarioStreamUnprocessableException(
                    "ephemeris role " + role.noradId() + " has no datasetId");
        }
        MeasuredDataset ds;
        try {
            ds = measuredDatasets.findById(UUID.fromString(datasetId))
                    .orElseThrow(() -> new ScenarioStreamUnprocessableException(
                            "measured dataset " + datasetId + " not found"));
        } catch (IllegalArgumentException badUuid) {
            throw new ScenarioStreamUnprocessableException("invalid datasetId \"" + datasetId + "\"");
        }
        MeasuredDatasetCodec.Decoded decoded = MeasuredDatasetCodec.decode(ds.getSamples());
        List<MeasuredEphemeris.Sample> samples = decoded.samples();
        if (samples.size() < 2) {
            throw new ScenarioStreamUnprocessableException(
                    "measured dataset " + datasetId + " has too few states");
        }
        // Build the tabulated ephemeris via the shared factory so the interpolation-degree
        // invariant (INTERP_POINTS = 2, R19) lives in ONE place — the maneuver templates and
        // rendezvous search resolve a measured chief through the same factory.
        MeasuredEphemerisFactory.Built built = MeasuredEphemerisFactory.build(samples, frames.eci(), utc);
        KeplerianOrbit k0 = built.firstOrbit();
        double periodSeconds = Math.max(60.0, k0.getKeplerianPeriod());
        return new PreparedRole(role, built.ephemeris(), periodSeconds, k0.getE(), Math.toDegrees(k0.getI()),
                buildMeasuredAttitude(role, decoded.attitude()));
    }

    /**
     * Build the role's measured body-attitude series (slice 2) as a stride-5
     * {@code [absEpochSec, qx,qy,qz,qw, ...]} array (three.js body→ECI quaternion,
     * via {@link MeasuredAttitude}) — or {@code null} when the role isn't in
     * {@code "measured"} mode or the dataset carries no attitude (then the stream
     * falls back to the modeled LVLH attitude). Times are absolute epoch seconds so
     * the stream loop can SLERP at {@code epoch + t}. Samples are already ascending.
     */
    private static double[] buildMeasuredAttitude(ScenarioBody.Role role,
                                                  List<MeasuredEphemeris.AttitudeSample> attitude) {
        if (attitude == null || attitude.isEmpty() || !"measured".equals(attitudeMode(role))) {
            return null;
        }
        double[] series = new double[attitude.size() * QuaternionSamples.STRIDE];
        int j = 0;
        for (MeasuredEphemeris.AttitudeSample a : attitude) {
            double[] q = MeasuredAttitude.wodEstAttdToBodyEciXyzw(a.q1(), a.q2(), a.q3(), a.q4());
            series[j] = a.epochMillis() / 1000.0; // absolute epoch seconds (the SLERP key)
            series[j + 1] = q[0];
            series[j + 2] = q[1];
            series[j + 3] = q[2];
            series[j + 4] = q[3];
            j += QuaternionSamples.STRIDE;
        }
        return series;
    }

    /**
     * Prepare a CW deputy (Phase 5C, US-REL-03): seed its relative state at the start
     * epoch from its own SGP4 state — transformed into the chief's live LVLH frame so
     * the relative <em>velocity</em> carries the frame rotation rate (R15) — then
     * advance it with the closed-form CW STM about the chief's mean motion.
     */
    private PreparedRole prepareCwDeputy(ScenarioBody.Role role, PreparedRole chief, AbsoluteDate startDate) {
        TLE tle = rebuildTle(role);
        Propagator depSgp4 = propagationService.propagatorFor(tle, Fidelity.SGP4);
        Frame eci = frames.eci();
        Frame lvlh = frames.lvlh(chief.provider());
        PVCoordinates rel0 = eci.getTransformTo(lvlh, startDate)
                .transformPVCoordinates(depSgp4.getPVCoordinates(startDate, eci));
        double n = 2.0 * Math.PI / chief.periodSeconds(); // chief mean motion (rad/s)
        PVCoordinatesProvider cw = CwPropagation.deputyProvider(
                lvlh, startDate, rel0, n, toImpulses(role.maneuvers()));
        return new PreparedRole(role, cw, chief.periodSeconds(), 0.0, Math.toDegrees(tle.getI()));
    }

    /** Convert a role's persisted maneuvers (RIC ΔV) into prop-layer impulses (Phase 5B). */
    private List<Impulse> toImpulses(List<ScenarioBody.Maneuver> maneuvers) {
        if (maneuvers == null || maneuvers.isEmpty()) {
            return List.of();
        }
        List<Impulse> impulses = new ArrayList<>(maneuvers.size());
        for (ScenarioBody.Maneuver m : maneuvers) {
            if (m.deltaV() == null || m.epoch() == null) {
                continue;
            }
            AbsoluteDate epoch = new AbsoluteDate(parseInstant(m.epoch()), utc);
            impulses.add(new Impulse(epoch, m.deltaV().r(), m.deltaV().i(), m.deltaV().c(),
                    m.thrustN(), m.ispSec()));
        }
        return impulses;
    }

    /**
     * Raise the step so {@code samples = steps + 1 ≤ maxSamplesPerSat}; never go
     * below the requested step. Echoed in the envelope so a clamp is visible,
     * never a silent truncation (R8).
     */
    private int effectiveStep(long durationSec) {
        int requested = Math.max(1, props.stepSeconds());
        int maxSamples = Math.max(2, props.maxSamplesPerSat());
        // ceil(durationSec / (maxSamples - 1)) is the smallest step that fits.
        long minStep = (durationSec + (maxSamples - 2)) / (maxSamples - 1);
        return (int) Math.max(requested, minStep);
    }

    private ScenarioSatelliteSamples sampleRole(PreparedRole role, AbsoluteDate startDate,
                                                double firstT, int step, int steps) {
        PVCoordinatesProvider provider = role.provider();
        Frame ecef = frames.ecef();
        double[] cartesian = new double[(steps + 1) * 4];
        // A body can leave the propagator's valid domain partway through the window
        // (numerical decay / a maneuver → Orekit "point is inside ellipsoid"). HOLD the
        // last valid position so the scenario still LOADS and the trail simply ends
        // there, instead of failing the whole stream (the time grid is unchanged, R8).
        int firstValid = -1;
        boolean decayed = false; // once a step leaves the domain, stop re-propagating
        double hx = 0, hy = 0, hz = 0;
        for (int k = 0; k <= steps; k++) {
            double t = firstT + (double) k * step; // relative to startDate; negative before start
            int base = k * 4;
            cartesian[base] = t;
            if (!decayed) {
                try {
                    Vector3D p = provider.getPVCoordinates(startDate.shiftedBy(t), ecef).getPosition();
                    hx = p.getX();
                    hy = p.getY();
                    hz = p.getZ();
                    if (firstValid < 0) {
                        firstValid = k;
                    }
                } catch (OrekitException leftDomain) {
                    // Trailing decay/re-entry (we already have a valid sample): HOLD the last
                    // valid point and stop re-propagating — past decay every call re-fails
                    // (costly). A leading gap (no valid sample yet — e.g. the margin before a
                    // measured ephemeris's first sample) keeps trying until valid data starts.
                    if (firstValid >= 0) {
                        decayed = true;
                    }
                }
            }
            cartesian[base + 1] = hx;
            cartesian[base + 2] = hy;
            cartesian[base + 3] = hz;
        }
        if (firstValid < 0) {
            throw new ScenarioStreamUnprocessableException(
                    "role " + role.role().role() + " (" + role.role().noradId()
                            + ") never reaches a valid state within the time range");
        }
        backfillLeading(cartesian, firstValid, 4);
        List<ScenarioBody.Maneuver> maneuvers = role.role().maneuvers();
        boolean maneuvered = maneuvers != null && !maneuvers.isEmpty();
        return new ScenarioSatelliteSamples(
                role.role().role(), role.role().noradId(), role.role().name(),
                role.periodSeconds(), role.inclinationDeg(), maneuvered, cartesian);
    }

    /** Copy the first valid (x,y,z) back over any leading held-at-origin samples. */
    private static void backfillLeading(double[] samples, int firstValidIdx, int stride) {
        if (firstValidIdx <= 0) {
            return;
        }
        double x = samples[firstValidIdx * stride + 1];
        double y = samples[firstValidIdx * stride + 2];
        double z = samples[firstValidIdx * stride + 3];
        for (int k = 0; k < firstValidIdx; k++) {
            samples[k * stride + 1] = x;
            samples[k * stride + 2] = y;
            samples[k * stride + 3] = z;
        }
    }

    /** Rebuild an Orekit TLE from the body's frozen line strings (Decision 19). */
    private TLE rebuildTle(ScenarioBody.Role role) {
        ScenarioBody.InitialState state = role.initialState();
        if (state == null || state.tle() == null
                || state.tle().line1() == null || state.tle().line2() == null) {
            throw new ScenarioStreamUnprocessableException(
                    "Role " + role.role() + " (" + role.noradId() + ") has no TLE initial state");
        }
        try {
            return new TLE(state.tle().line1(), state.tle().line2(), utc);
        } catch (RuntimeException e) {
            throw new ScenarioStreamUnprocessableException(
                    "Role " + role.role() + " (" + role.noradId() + ") TLE failed to parse: " + e.getMessage());
        }
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new ScenarioStreamUnprocessableException("scenario time range is incomplete");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException e1) {
            try {
                return Instant.parse(value);
            } catch (DateTimeException e2) {
                throw new ScenarioStreamUnprocessableException("scenario time range is not ISO-8601: " + value);
            }
        }
    }
}
