package space.orbit.backend.stream;

import jakarta.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.Frame;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.PVCoordinates;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.PropagationService;
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

    private TimeScale utc;

    public ScenarioStreamService(ScenarioService scenarioService,
                                 PropagationService propagationService,
                                 FrameService frames,
                                 CzmlEncoder encoder,
                                 RelativeStateEncoder relativeEncoder,
                                 ScenarioStreamProperties props) {
        this.scenarioService = scenarioService;
        this.propagationService = propagationService;
        this.frames = frames;
        this.encoder = encoder;
        this.relativeEncoder = relativeEncoder;
        this.props = props;
    }

    @PostConstruct
    void init() {
        utc = TimeScalesFactory.getUTC(); // safe: @DependsOn orekitConfig
    }

    /**
     * Load the scenario (owned by {@code callerEmail}), propagate every role over
     * its time range at the effective step, and encode the global-view CZML.
     *
     * @throws space.orbit.backend.scenario.ScenarioNotFoundException missing / soft-deleted / not owned
     * @throws ScenarioStreamUnprocessableException CW fidelity, non-TLE state, or a TLE that won't parse
     */
    public EncodedScenario loadAndEncode(UUID id, String callerEmail) {
        ScenarioBody body = scenarioService.bodyForStream(id, callerEmail);

        Fidelity fidelity = Fidelity.fromString(body.fidelity());
        if (fidelity == Fidelity.CW) {
            throw new ScenarioStreamUnprocessableException(
                    "CW fidelity is not streamable until Phase 5");
        }

        Instant start = parseInstant(body.timeRange().start());
        Instant end = parseInstant(body.timeRange().end());
        long durationSec = Math.max(1, end.getEpochSecond() - start.getEpochSecond());

        // Prepare each role up front so we know the periods before sampling.
        List<PreparedRole> roles = new ArrayList<>();
        roles.add(prepareRole(body.chief(), fidelity));
        for (ScenarioBody.Role deputy : body.deputies()) {
            roles.add(prepareRole(deputy, fidelity));
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
        AbsoluteDate startDate = new AbsoluteDate(start, utc);

        List<ScenarioSatelliteSamples> samples = new ArrayList<>();
        for (PreparedRole role : roles) {
            samples.add(sampleRole(role, startDate, firstT, effectiveStep, steps));
        }
        String czml = encoder.encodeScenario(start, effectiveStep, samples);

        // Relative-state (proximity view, 4B): chief-LVLH R/I/C per deputy, on the
        // SAME time grid so both views interpolate to the same instants.
        String relative = encodeRelative(roles, startDate, firstT, effectiveStep, steps, start);

        return new EncodedScenario(czml, relative, effectiveStep);
    }

    /**
     * Encode each deputy's position (and optionally velocity) in the chief's LVLH
     * frame. R15: the frame is built ONCE from the <em>live</em> chief propagator
     * and applied per step — never via {@code FrameService.toRelativeState}, whose
     * single-epoch constant provider drops the frame rotation rate and would give
     * a wrong relative <em>velocity</em>.
     */
    private String encodeRelative(List<PreparedRole> roles, AbsoluteDate startDate,
                                  double firstT, int step, int steps, Instant epoch) {
        PreparedRole chief = roles.get(0);
        Frame eci = frames.eci();
        Frame lvlh = frames.lvlh(chief.propagator()); // rotating LVLH over the live chief orbit
        boolean withVel = props.includeRelativeVelocity();
        int stride = withVel ? 7 : 4;
        int degree = Math.max(1, Math.min(5, steps));

        List<RelativeSamples> deputies = new ArrayList<>();
        for (int i = 1; i < roles.size(); i++) { // skip chief (the LVLH origin)
            Propagator depProp = roles.get(i).propagator();
            double[] s = new double[(steps + 1) * stride];
            for (int k = 0; k <= steps; k++) {
                double t = firstT + (double) k * step;
                AbsoluteDate date = startDate.shiftedBy(t);
                PVCoordinates depEci = depProp.getPVCoordinates(date, eci);
                PVCoordinates rel = eci.getTransformTo(lvlh, date).transformPVCoordinates(depEci);
                Vector3D p = rel.getPosition();
                int base = k * stride;
                s[base] = t;
                s[base + 1] = p.getX(); // radial
                s[base + 2] = p.getY(); // in-track
                s[base + 3] = p.getZ(); // cross-track
                if (withVel) {
                    Vector3D v = rel.getVelocity(); // carries the LVLH rotation rate (R15)
                    s[base + 4] = v.getX();
                    s[base + 5] = v.getY();
                    s[base + 6] = v.getZ();
                }
            }
            ScenarioBody.Role role = roles.get(i).role();
            deputies.add(new RelativeSamples(role.noradId(), role.name(), degree, s));
        }
        return relativeEncoder.encodeRelative(epoch, step, chief.role().noradId(), deputies, withVel);
    }

    /** Total orbit-path length, in orbital periods (must match the encoder's lead+trail). */
    private static final double PATH_PERIODS = 1.5;

    /** A role with its built propagator + period, prepared before sampling. */
    private record PreparedRole(ScenarioBody.Role role, Propagator propagator, double periodSeconds) {}

    private PreparedRole prepareRole(ScenarioBody.Role role, Fidelity fidelity) {
        TLE tle = rebuildTle(role);
        Propagator propagator = propagationService.propagatorFor(tle, fidelity);
        // Orbital period from the TLE mean motion (rad/s); ≥60 s guards odd elements.
        double periodSeconds = Math.max(60.0, 2.0 * Math.PI / tle.getMeanMotion());
        return new PreparedRole(role, propagator, periodSeconds);
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
        Propagator propagator = role.propagator();
        double[] cartesian = new double[(steps + 1) * 4];
        for (int k = 0; k <= steps; k++) {
            double t = firstT + (double) k * step; // relative to startDate; negative before start
            AbsoluteDate date = startDate.shiftedBy(t);
            Vector3D ecef = propagator.getPVCoordinates(date, frames.ecef()).getPosition();
            int base = k * 4;
            cartesian[base] = t;
            cartesian[base + 1] = ecef.getX();
            cartesian[base + 2] = ecef.getY();
            cartesian[base + 3] = ecef.getZ();
        }
        return new ScenarioSatelliteSamples(
                role.role().role(), role.role().noradId(), role.role().name(), role.periodSeconds(), cartesian);
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
