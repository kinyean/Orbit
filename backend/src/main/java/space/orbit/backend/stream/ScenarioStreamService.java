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
import org.orekit.orbits.CartesianOrbit;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.Ephemeris;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;
import org.orekit.utils.PVCoordinatesProvider;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.analysis.SampledCraft;
import space.orbit.backend.analysis.SensorEvent;
import space.orbit.backend.analysis.SensorEventComputer;
import space.orbit.backend.io.MeasuredEphemeris;
import space.orbit.backend.prop.CwPropagation;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.scenario.MeasuredDataset;
import space.orbit.backend.scenario.MeasuredDatasetCodec;
import space.orbit.backend.scenario.MeasuredDatasetRepository;
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
                    durationSec, body.fidelity(), chief.eccentricity());

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
                                  long durationSec, String fidelity, double chiefEccentricity) {
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

        // Chief body attitude in its own LVLH frame (Phase 7) so the chief's FOV
        // volumes render. The chief is the LVLH origin (no relative position).
        double[] chiefAttitude = sampleAttitude(chief, eci, lvlh, startDate, firstT, step, steps);

        double maxSeparation = 0.0; // over all deputies, within the scenario window (CW hint)
        List<RelativeSamples> deputies = new ArrayList<>();
        for (int i = 1; i < roles.size(); i++) { // skip chief (the LVLH origin)
            PVCoordinatesProvider depProp = roles.get(i).provider();
            ScenarioBody.Role role = roles.get(i).role();
            String attMode = attitudeMode(role);
            double[] attFixedQuat = attitudeQuat(role);
            double[] s = new double[(steps + 1) * stride];
            double[] a = new double[(steps + 1) * ATT_STRIDE]; // [t,qx,qy,qz,qw, ...]
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
            for (int k = 0; k <= steps; k++) {
                double t = firstT + (double) k * step;
                int base = k * stride;
                s[base] = t;
                a[k * ATT_STRIDE] = t;
                if (!decayed) {
                    try {
                        AbsoluteDate date = startDate.shiftedBy(t);
                        Transform toLvlh = eci.getTransformTo(lvlh, date);
                        PVCoordinates depEci = depProp.getPVCoordinates(date, eci);
                        PVCoordinates rel = toLvlh.transformPVCoordinates(depEci);
                        Vector3D p = rel.getPosition();
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
                        // same state + transform (no extra propagation).
                        double[] q = frames.bodyQuaternionInLvlh(depEci, toLvlh, attMode, attFixedQuat);
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
            }
            if (firstValid < 0) {
                throw new ScenarioStreamUnprocessableException("deputy "
                        + roles.get(i).role().noradId() + " never reaches a valid relative state");
            }
            backfillLeading(s, firstValid, stride);
            backfillLeadingAttitude(a, firstValid);
            // Refine the closest approach on the live propagators around the coarse
            // bracket (US-REL-02). Distance is frame-invariant — compute it in ECI.
            double[] tca = refineTca(depProp, chief.provider(), eci, startDate,
                    Math.max(0.0, coarseMinT - step), Math.min((double) durationSec, coarseMinT + step),
                    coarseMinT, coarseMinDist);
            Instant tcaEpoch = epoch.plusMillis(Math.round(tca[0] * 1000.0));

            deputies.add(new RelativeSamples(role.noradId(), role.name(), degree, s, tcaEpoch, tca[1],
                    a, role.sensors()));
        }

        // Acquisition / loss-of-sight events (Phase 7, US-EVT-01) — computed from the SAME
        // sampled trajectory + attitude this method just built (NOT a second propagation), so
        // events are consistent with the drawn FOV + the closest approach (Decision 24). Only
        // when some craft carries a sensor; every craft is both a potential host and target.
        List<SensorEvent> events = sensorEvents(chief, chiefAttitude, deputies, stride, firstT, step, steps,
                epoch, durationSec, chiefRadiusM);

        return relativeEncoder.encodeRelative(epoch, step, chief.role().noradId(),
                chiefAttitude, chief.role().sensors(), deputies, withVel,
                fidelity, maxSeparation, chiefEccentricity, chiefRadiusM, events);
    }

    /**
     * Detect acquisition/loss events over the already-sampled trajectory (Phase 7). The chief is
     * the LVLH origin (position {@code null} → (0,0,0)); deputies carry their sampled position +
     * attitude. Reuses the rendered samples so events agree with the drawn FOV.
     */
    private List<SensorEvent> sensorEvents(PreparedRole chief, double[] chiefAttitude,
                                           List<RelativeSamples> deputies, int stride, double firstT,
                                           int step, int steps, Instant epoch, long durationSec,
                                           double chiefRadiusM) {
        boolean anySensors = chief.role().sensors() != null && !chief.role().sensors().isEmpty();
        for (RelativeSamples d : deputies) {
            anySensors |= d.sensors() != null && !d.sensors().isEmpty();
        }
        if (!anySensors) {
            return List.of();
        }
        List<SampledCraft> crafts = new ArrayList<>(deputies.size() + 1);
        crafts.add(new SampledCraft(chief.role().noradId(), null, stride, chiefAttitude, chief.role().sensors()));
        for (RelativeSamples d : deputies) {
            crafts.add(new SampledCraft(d.noradId(), d.samples(), stride, d.attitude(), d.sensors()));
        }
        return sensorEvents.compute(crafts, firstT, step, steps, epoch, durationSec, chiefRadiusM);
    }

    /** Attitude sample stride: {@code [t,qx,qy,qz,qw]} (matches the encoder). */
    private static final int ATT_STRIDE = 5;

    /**
     * Sample a role's body attitude (three.js-convention quaternion in the chief-LVLH
     * scene frame) on the position grid (Phase 7). HOLDs the last valid quaternion past
     * a domain exit, mirroring the position sampler. Used for the chief (the deputy loop
     * computes its own attitude inline to reuse the per-step transform).
     */
    private double[] sampleAttitude(PreparedRole role, Frame eci, Frame lvlh, AbsoluteDate startDate,
                                    double firstT, int step, int steps) {
        String mode = attitudeMode(role.role());
        double[] fixedQuat = attitudeQuat(role.role());
        double[] a = new double[(steps + 1) * ATT_STRIDE];
        int firstValid = -1;
        boolean decayed = false;
        double hqx = 0, hqy = 0, hqz = 0, hqw = 1;
        for (int k = 0; k <= steps; k++) {
            double t = firstT + (double) k * step;
            a[k * ATT_STRIDE] = t;
            if (!decayed) {
                try {
                    double[] q = frames.bodyQuaternionInLvlh(
                            role.provider(), lvlh, startDate.shiftedBy(t), mode, fixedQuat);
                    hqx = q[0];
                    hqy = q[1];
                    hqz = q[2];
                    hqw = q[3];
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
        }
        // If the chief never reached a valid state the whole stream already fails in
        // sampleRole; here we just backfill any leading held identity quaternions.
        backfillLeadingAttitude(a, Math.max(firstValid, 0));
        return a;
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
                                double periodSeconds, double eccentricity, double inclinationDeg) {}

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
     * Interpolation points for the tabulated measured ephemeris. Each sample carries
     * velocity, so TWO points give a cubic Hermite per segment — accurate for dense
     * (~5 min) LEO ephemeris and, crucially, STABLE. More points raise the polynomial
     * degree (4 pts ⇒ degree-7) which, over ~20° of arc per step, overshoots wildly
     * between nodes (Runge oscillation → positions blowing up to ~1e11 km even though
     * the nodes are exact). Keep this at 2.
     */
    private static final int EPHEMERIS_INTERP_POINTS = 2;

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
        List<MeasuredEphemeris.Sample> samples = MeasuredDatasetCodec.decode(ds.getSamples());
        if (samples.size() < 2) {
            throw new ScenarioStreamUnprocessableException(
                    "measured dataset " + datasetId + " has too few states");
        }
        Frame eci = frames.eci();
        double mu = Constants.WGS84_EARTH_MU;
        List<SpacecraftState> states = new ArrayList<>(samples.size());
        for (MeasuredEphemeris.Sample s : samples) {
            AbsoluteDate date = new AbsoluteDate(Instant.ofEpochMilli(s.epochMillis()), utc);
            PVCoordinates pv = new PVCoordinates(
                    new Vector3D(s.px(), s.py(), s.pz()), new Vector3D(s.vx(), s.vy(), s.vz()));
            states.add(new SpacecraftState(new CartesianOrbit(pv, eci, date, mu)));
        }
        Ephemeris ephemeris = new Ephemeris(states, Math.min(EPHEMERIS_INTERP_POINTS, states.size()));
        KeplerianOrbit k0 = new KeplerianOrbit(states.get(0).getOrbit());
        double periodSeconds = Math.max(60.0, k0.getKeplerianPeriod());
        return new PreparedRole(role, ephemeris, periodSeconds, k0.getE(), Math.toDegrees(k0.getI()));
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
            impulses.add(new Impulse(epoch, m.deltaV().r(), m.deltaV().i(), m.deltaV().c()));
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
