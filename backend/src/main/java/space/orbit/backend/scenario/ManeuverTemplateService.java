package space.orbit.backend.scenario;

import jakarta.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.estimation.iod.IodLambert;
import org.orekit.frames.Frame;
import org.orekit.orbits.Orbit;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.KeplerianPropagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.AbsolutePVCoordinates;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;
import org.orekit.utils.PVCoordinatesProvider;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.prop.CwTargeting;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.PropagationService;

/**
 * Impulsive maneuver templates (Phase 5C): Hohmann transfer (US-MAN-02) and a
 * two-impulse Lambert rendezvous (US-MAN-03). Both <em>compute</em> ΔV from the
 * scenario's frozen TLEs and then insert the resulting impulses through the audited
 * {@link ScenarioService#addManeuvers} path — so a template is one versioned,
 * audited edit that re-propagates exactly like a hand-entered maneuver (Phase 5B).
 *
 * <p>Determinism (R11): the solvers run once here, at insert time, and store concrete
 * frozen ΔV components — a saved scenario reruns byte-identically regardless of solver
 * internals. ΔV is stored in the deputy's own RIC (the frame {@code ImpulseManeuver}
 * re-applies it in), prograde = +in-track.
 */
@Service
@DependsOn("orekitConfig")
public class ManeuverTemplateService {

    private final ScenarioService scenarioService;
    private final PropagationService propagationService;
    private final FrameService frames;
    private final RendezvousCorrector corrector;
    private final ChiefStateResolver chiefResolver;
    private final CollisionAvoidancePlanner camPlanner;

    /** Below this the orbit is deep in the atmosphere and re-enters within hours — a
     *  Hohmann target there just deorbits the deputy. Guards the common "I meant to
     *  nudge it, not crash it" mistake (the field is an absolute altitude, not a delta). */
    private static final double MIN_TARGET_ALTITUDE_KM = 150.0;

    private TimeScale utc;

    public ManeuverTemplateService(ScenarioService scenarioService,
                                   PropagationService propagationService,
                                   FrameService frames,
                                   RendezvousCorrector corrector,
                                   ChiefStateResolver chiefResolver,
                                   CollisionAvoidancePlanner camPlanner) {
        this.scenarioService = scenarioService;
        this.propagationService = propagationService;
        this.frames = frames;
        this.corrector = corrector;
        this.chiefResolver = chiefResolver;
        this.camPlanner = camPlanner;
    }

    @PostConstruct
    void init() {
        utc = TimeScalesFactory.getUTC();
    }

    /**
     * Hohmann transfer to a target circular altitude (US-MAN-02): two prograde
     * impulses (burn at the scenario start, the second a half-transfer later),
     * computed from the deputy's current radius via vis-viva.
     */
    public ScenarioResponse hohmann(UUID id, int deputyNoradId, double targetAltitudeKm) {
        // The target is an ABSOLUTE circular altitude (km above the surface), not a
        // change — reject sub-atmospheric targets that would just re-enter.
        if (targetAltitudeKm < MIN_TARGET_ALTITUDE_KM) {
            throw new ScenarioValidationException(String.format(
                    "target altitude must be at least %.0f km (it is the absolute altitude above the"
                            + " surface, not a change) — lower would re-enter the atmosphere",
                    MIN_TARGET_ALTITUDE_KM));
        }
        ScenarioBody body = scenarioService.get(id).body();
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);

        Frame eci = frames.eci();
        Instant startInstant = parseInstant(body.timeRange().start());
        AbsoluteDate t1 = new AbsoluteDate(startInstant, utc);
        Propagator prop = propagationService.propagatorFor(tleOf(deputy), Fidelity.SGP4);

        double r1 = prop.getPVCoordinates(t1, eci).getPosition().getNorm();
        double r2 = Constants.WGS84_EARTH_EQUATORIAL_RADIUS + targetAltitudeKm * 1000.0;
        double mu = Constants.WGS84_EARTH_MU;
        double at = 0.5 * (r1 + r2); // transfer-ellipse semi-major axis

        double dv1 = Math.sqrt(mu * (2.0 / r1 - 1.0 / at)) - Math.sqrt(mu / r1);
        double dv2 = Math.sqrt(mu / r2) - Math.sqrt(mu * (2.0 / r2 - 1.0 / at));
        double tofSec = Math.PI * Math.sqrt(at * at * at / mu);
        Instant arrival = startInstant.plusMillis(Math.round(tofSec * 1000.0));

        // Prograde = +in-track in the deputy's own RIC (near-circular assumption).
        List<ManeuverDraft> drafts = List.of(
                new ManeuverDraft(deputyNoradId, startInstant.toString(), "ric", 0.0, dv1, 0.0),
                new ManeuverDraft(deputyNoradId, arrival.toString(), "ric", 0.0, dv2, 0.0));
        String summary = String.format("Hohmann → %.0f km (Δv %.2f + %.2f m/s)", targetAltitudeKm, dv1, dv2);
        return scenarioService.addManeuvers(id, drafts, summary);
    }

    /**
     * Two-impulse rendezvous (US-MAN-03; Phase 9A flight-ready, closes R16): depart the
     * deputy at the scenario start, arrive at the chief's position at {@code arrivalEpoch}.
     * A two-body Lambert transfer ({@link IodLambert}) is the <em>seed</em>; when
     * {@code corrected} it is then closed-looped against the real propagators by
     * {@link RendezvousCorrector} so the deputy actually arrives (the open-loop seed
     * misses by the SGP4-vs-numerical model difference — tens of km). The two ΔV are
     * expressed in the burn state's own RIC.
     *
     * @param corrected run the differential corrector against the real propagators
     *                  (recommended default); on non-convergence it falls back to the
     *                  open-loop seed and labels the audit summary.
     * @param nRev      solve Lambert for exactly this revolution count (from the
     *                  arrival×rev search); {@code null} searches all feasible counts
     *                  and keeps the cheapest (the historical behavior).
     */
    public ScenarioResponse rendezvous(UUID id, int deputyNoradId, String arrivalEpoch,
                                       boolean corrected, Integer nRev) {
        ScenarioBody body = scenarioService.get(id).body();
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);
        if (body.chief() == null) {
            throw new ScenarioValidationException("rendezvous requires a chief");
        }

        Frame eci = frames.eci();
        Instant startInstant = parseInstant(body.timeRange().start());
        Instant arrInstant = parseInstant(arrivalEpoch);
        AbsoluteDate t1 = new AbsoluteDate(startInstant, utc);
        AbsoluteDate t2 = new AbsoluteDate(arrInstant, utc);
        if (t2.durationFrom(t1) <= 0) {
            throw new ScenarioValidationException("arrival epoch must be after the scenario start");
        }

        TLE depTle = tleOf(deputy);
        // The chief may be TLE-backed OR a measured ephemeris (e.g. an imported TELEOS-2);
        // the resolver hands back a state provider either way (the chief is only sampled, never
        // maneuvered). SGP4 for the cheap Lambert seed.
        ChiefStateResolver.ChiefState chiefSeed = chiefResolver.resolve(body.chief(), Fidelity.SGP4);
        Propagator depProp = propagationService.propagatorFor(depTle, Fidelity.SGP4);
        PVCoordinates dep1 = depProp.getPVCoordinates(t1, eci);
        PVCoordinates chief2 = chiefSeed.provider().getPVCoordinates(t2, eci);

        // Solve Lambert for the requested revolution count, or across every feasible
        // count keeping the cheapest. A fixed nRev=0 is degenerate once the arrival is
        // ≥1 orbit out: the target has wrapped back near its start, so a zero-rev path
        // between two near-coincident points must nearly cancel the ~7.5 km/s orbital
        // velocity (tens of km/s of Δv). The natural transfer is the matching multi-rev
        // solution, which a fixed nRev=0 never tries.
        double period = 2.0 * Math.PI / depTle.getMeanMotion();
        int maxRev = (int) Math.floor(t2.durationFrom(t1) / period);
        int loRev = nRev != null ? Math.max(0, nRev) : 0;
        int hiRev = nRev != null ? Math.max(0, nRev) : maxRev;
        IodLambert lambert = new IodLambert(Constants.WGS84_EARTH_MU);
        Vector3D vDepart = null;
        Vector3D vArrive = null;
        double bestTotal = Double.POSITIVE_INFINITY;
        for (int rev = loRev; rev <= hiRev; rev++) {
            try {
                Orbit transfer = lambert.estimate(eci, true, rev, dep1.getPosition(), t1, chief2.getPosition(), t2);
                if (transfer == null) {
                    continue;
                }
                Vector3D vd = transfer.getPVCoordinates().getVelocity();
                Vector3D va = new KeplerianPropagator(transfer).getPVCoordinates(t2, eci).getVelocity();
                double total = vd.subtract(dep1.getVelocity()).getNorm()
                        + chief2.getVelocity().subtract(va).getNorm();
                if (total < bestTotal) {
                    bestTotal = total;
                    vDepart = vd;
                    vArrive = va;
                }
            } catch (RuntimeException infeasible) {
                // no solution for this revolution count / geometry — skip it
            }
        }
        if (vDepart == null) {
            throw new ScenarioValidationException(
                    "no transfer orbit connects the deputy to the chief at that arrival time");
        }

        // Δv1 boosts the deputy onto the transfer; Δv2 matches the chief at arrival.
        Vector3D dv1Eci = vDepart.subtract(dep1.getVelocity());
        Vector3D dv2Eci = chief2.getVelocity().subtract(vArrive);
        double[] ric1 = toRic(dv1Eci, dep1.getPosition(), dep1.getVelocity(), t1);
        double[] ric2 = toRic(dv2Eci, chief2.getPosition(), vArrive, t2);
        Impulse depart = new Impulse(t1, ric1[0], ric1[1], ric1[2]);
        Impulse arrive = new Impulse(t2, ric2[0], ric2[1], ric2[2]);

        String summary;
        if (corrected) {
            // Close the loop against the SAME propagators the scenario flies (the
            // deputy is always numerical once it has a burn; the chief on the scenario
            // fidelity, CW→SGP4). R16: the open-loop seed misses; this hits.
            // The corrector flies the chief at the scenario fidelity (a measured chief reuses
            // the ephemeris — fidelity is meaningless there, so don't decode the dataset twice).
            Propagator chiefForCorrector = chiefResolver.isMeasured(body.chief())
                    ? chiefSeed.provider()
                    : chiefResolver.resolve(body.chief(), chiefStreamFidelity(body.fidelity())).provider();
            RendezvousCorrector.Correction c = corrector.correct(
                    depTle, chiefForCorrector, t1, t2, depart, arrive);
            depart = c.depart();
            arrive = c.arrive();
            double total = norm(depart) + norm(arrive);
            summary = c.converged()
                    ? String.format("Corrected rendezvous (Δv total %.2f m/s, arrival miss %.2f m)",
                            total, c.missM())
                    : String.format("Rendezvous (Δv total %.2f m/s) — %s", total, c.note());
        } else {
            summary = String.format("Lambert rendezvous (Δv total %.2f m/s)",
                    dv1Eci.getNorm() + dv2Eci.getNorm());
        }

        List<ManeuverDraft> drafts = List.of(
                new ManeuverDraft(deputyNoradId, startInstant.toString(), "ric", depart.r(), depart.i(), depart.c()),
                new ManeuverDraft(deputyNoradId, arrInstant.toString(), "ric", arrive.r(), arrive.i(), arrive.c()));
        return scenarioService.addManeuvers(id, drafts, summary);
    }

    /**
     * Phasing-orbit rendezvous (US-MAN-06, the realistic co-elliptic walk-down): close
     * the along-track phase gap to the chief over {@code phasingRevs} revolutions by
     * dropping the deputy onto a phasing orbit (slightly different period), then return
     * it to the chief's orbit. Two equal-and-opposite in-track burns.
     *
     * <p>This is an open-loop <em>two-body sketch</em> (like the original Lambert
     * template before 9A's corrector): the phasing geometry is computed in two-body and
     * frozen. A fully closed-loop multi-burn phasing solve is a follow-up; the cheap,
     * honest value here is the realistic geometry + ΔV magnitude.
     */
    public ScenarioResponse phasing(UUID id, int deputyNoradId, int phasingRevs) {
        if (phasingRevs < 1) {
            throw new ScenarioValidationException("phasing requires at least 1 revolution");
        }
        ScenarioBody body = scenarioService.get(id).body();
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);
        if (body.chief() == null) {
            throw new ScenarioValidationException("phasing requires a chief");
        }

        Frame eci = frames.eci();
        Instant startInstant = parseInstant(body.timeRange().start());
        AbsoluteDate t1 = new AbsoluteDate(startInstant, utc);

        TLE depTle = tleOf(deputy);
        ChiefStateResolver.ChiefState chief = chiefResolver.resolve(body.chief(), Fidelity.SGP4);
        PVCoordinates dep1 = propagationService.propagatorFor(depTle, Fidelity.SGP4).getPVCoordinates(t1, eci);
        PVCoordinates chief1 = chief.provider().getPVCoordinates(t1, eci);

        double mu = Constants.WGS84_EARTH_MU;
        // Signed along-track phase from the deputy to the chief about the orbit normal
        // (chief-ahead positive ⇒ deputy must catch up ⇒ shorter phasing period).
        Vector3D rd = dep1.getPosition();
        Vector3D h = rd.crossProduct(dep1.getVelocity());
        double phi = Math.atan2(
                rd.crossProduct(chief1.getPosition()).dotProduct(h.normalize()),
                rd.dotProduct(chief1.getPosition()));

        double tChief = 2.0 * Math.PI / chief.meanMotionRadPerSec();
        double tPhase = tChief * (1.0 - phi / (2.0 * Math.PI * phasingRevs));
        if (!(tPhase > 0)) {
            throw new ScenarioValidationException("phasing geometry is degenerate — try more revolutions");
        }
        double aPhase = Math.cbrt(mu * Math.pow(tPhase / (2.0 * Math.PI), 2.0));
        double r1 = rd.getNorm();
        double otherApsis = 2.0 * aPhase - r1; // the burn point r1 is one apsis of the phasing orbit
        if (Math.min(r1, otherApsis) < Constants.WGS84_EARTH_EQUATORIAL_RADIUS + MIN_TARGET_ALTITUDE_KM * 1000.0) {
            throw new ScenarioValidationException(String.format(
                    "phasing orbit would drop below %.0f km — use more revolutions", MIN_TARGET_ALTITUDE_KM));
        }
        double v1 = dep1.getVelocity().getNorm();
        double vPhase = Math.sqrt(mu * (2.0 / r1 - 1.0 / aPhase));
        double dv = vPhase - v1; // tangential ≈ in-track for a near-circular orbit

        Instant returnInstant = startInstant.plusMillis(Math.round(phasingRevs * tPhase * 1000.0));
        // The return burn lands N phasing-orbits later; that must fit the scenario window
        // (the audited path rejects out-of-window epochs — give a clearer reason here).
        Instant end = parseInstant(body.timeRange().end());
        if (returnInstant.isAfter(end)) {
            throw new ScenarioValidationException(String.format(
                    "phasing over %d revolutions returns at %s, past the scenario window — extend the"
                            + " window or use fewer revolutions", phasingRevs, returnInstant));
        }
        List<ManeuverDraft> drafts = List.of(
                new ManeuverDraft(deputyNoradId, startInstant.toString(), "ric", 0.0, dv, 0.0),
                new ManeuverDraft(deputyNoradId, returnInstant.toString(), "ric", 0.0, -dv, 0.0));
        String summary = String.format(
                "Phasing rendezvous (%d rev%s, Δv 2×%.2f m/s, return %s)",
                phasingRevs, phasingRevs == 1 ? "" : "s", Math.abs(dv), returnInstant);
        return scenarioService.addManeuvers(id, drafts, summary);
    }

    /**
     * Natural-Motion-Circumnavigation insertion (US-MAN-09): a single in-track burn that
     * cancels the deputy's along-track drift (sets {@code vy = −2 n x}, the CW bounded-orbit
     * condition), putting it on a closed relative ellipse that circles the chief with no
     * further thrust. Computed from the deputy's current relative state in the chief LVLH.
     */
    public ScenarioResponse nmc(UUID id, int deputyNoradId) {
        ScenarioBody body = scenarioService.get(id).body();
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);
        if (body.chief() == null) {
            throw new ScenarioValidationException("NMC requires a chief");
        }
        Instant startInstant = parseInstant(body.timeRange().start());
        AbsoluteDate t1 = new AbsoluteDate(startInstant, utc);
        ChiefStateResolver.ChiefState chief = chiefResolver.resolve(body.chief(), Fidelity.SGP4);
        Propagator chiefProp = chief.provider();
        Propagator depProp = propagationService.propagatorFor(tleOf(deputy), Fidelity.SGP4);
        double n = chief.meanMotionRadPerSec();
        double[] s = relativeStateLvlh(chiefProp, depProp, t1);
        double dvy = (-2.0 * n * s[0]) - s[4]; // bring vy to the no-drift value
        List<ManeuverDraft> drafts = List.of(
                new ManeuverDraft(deputyNoradId, startInstant.toString(), "ric", 0.0, dvy, 0.0));
        return scenarioService.addManeuvers(id, drafts, String.format(
                "NMC insertion (Δv %.2f m/s in-track → bounded relative orbit)", Math.abs(dvy)));
    }

    /**
     * V-bar / R-bar hold (US-MAN-07/08; point station-keeping): a CW two-impulse transfer
     * that brings the deputy to rest at a hold point on the chief's in-track (V-bar) or
     * radial (R-bar) axis, {@code distanceM} from the chief, arriving at {@code arrivalEpoch}.
     * The departure burn puts it on the transfer; the arrival burn nulls the relative
     * velocity so it parks there (US-MAN-10).
     *
     * @param axis {@code "vbar"} (in-track) or {@code "rbar"} (radial); a signed
     *             {@code distanceM} places the point ahead/behind or above/below.
     */
    public ScenarioResponse hold(UUID id, int deputyNoradId, String axis, double distanceM, String arrivalEpoch) {
        ScenarioBody body = scenarioService.get(id).body();
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);
        if (body.chief() == null) {
            throw new ScenarioValidationException("a hold requires a chief");
        }
        Instant startInstant = parseInstant(body.timeRange().start());
        Instant arrInstant = parseInstant(arrivalEpoch);
        AbsoluteDate t1 = new AbsoluteDate(startInstant, utc);
        AbsoluteDate t2 = new AbsoluteDate(arrInstant, utc);
        double dt = t2.durationFrom(t1);
        if (dt <= 0) {
            throw new ScenarioValidationException("arrival epoch must be after the scenario start");
        }
        String axisNorm = axis == null ? "vbar" : axis.trim().toLowerCase();
        double[] rT = switch (axisNorm) {
            case "rbar" -> new double[] {distanceM, 0.0, 0.0}; // radial
            case "vbar" -> new double[] {0.0, distanceM, 0.0}; // in-track
            default -> throw new ScenarioValidationException("hold axis must be 'vbar' or 'rbar'");
        };
        ChiefStateResolver.ChiefState chief = chiefResolver.resolve(body.chief(), Fidelity.SGP4);
        Propagator chiefProp = chief.provider();
        Propagator depProp = propagationService.propagatorFor(tleOf(deputy), Fidelity.SGP4);
        double n = chief.meanMotionRadPerSec();
        double[] s = relativeStateLvlh(chiefProp, depProp, t1);
        double[] dv = CwTargeting.twoImpulse(
                new double[] {s[0], s[1], s[2]}, new double[] {s[3], s[4], s[5]},
                rT, new double[] {0.0, 0.0, 0.0}, n, dt);
        if (dv == null) {
            throw new ScenarioValidationException(
                    "the transfer time is near an integer number of orbits (CW-singular) — pick a different arrival");
        }
        List<ManeuverDraft> drafts = List.of(
                new ManeuverDraft(deputyNoradId, startInstant.toString(), "ric", dv[0], dv[1], dv[2]),
                new ManeuverDraft(deputyNoradId, arrInstant.toString(), "ric", dv[3], dv[4], dv[5]));
        double total = Math.sqrt(dv[0] * dv[0] + dv[1] * dv[1] + dv[2] * dv[2])
                + Math.sqrt(dv[3] * dv[3] + dv[4] * dv[4] + dv[5] * dv[5]);
        return scenarioService.addManeuvers(id, drafts, String.format(
                "%s hold @ %.0f m (CW two-impulse, Δv total %.2f m/s)",
                axisNorm.toUpperCase(), distanceM, total));
    }

    /**
     * Glideslope approach (US-MAN-09): a constant-closing-rate straight-line approach along
     * the chief's in-track (V-bar) or radial (R-bar) axis, from {@code startRangeM} to
     * {@code endRangeM}, parking at rest at the end point. The line is discretized into
     * {@code segments} CW two-impulse legs (plus an acquisition leg from the deputy's current
     * state to the start point); a single corrective burn at each waypoint both kills the
     * previous leg's arrival velocity and sets up the next, and a final burn nulls the velocity
     * at the end point. Each leg is flown at the constant {@code closingRateMps}, so leg
     * duration scales with leg length.
     *
     * <p>Like the other close-range templates this is computed in CW from the deputy's current
     * relative state but executed in the real model (a documented approximation); more segments
     * tighten the straight-line tracking. {@code startRangeM}/{@code endRangeM} are signed (same
     * sign = same side; {@code |end| < |start|} = closing in).
     *
     * @param axis {@code "vbar"} (in-track) or {@code "rbar"} (radial)
     */
    public ScenarioResponse glideslope(UUID id, int deputyNoradId, String axis,
                                       double startRangeM, double endRangeM,
                                       double closingRateMps, int segments) {
        ScenarioBody body = scenarioService.get(id).body();
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);
        if (body.chief() == null) {
            throw new ScenarioValidationException("a glideslope requires a chief");
        }
        if (!(closingRateMps > 0.0) || !Double.isFinite(closingRateMps)) {
            throw new ScenarioValidationException("closing rate must be a positive speed (m/s)");
        }
        if (segments < 1 || segments > 30) {
            throw new ScenarioValidationException("segments must be between 1 and 30");
        }
        if (!Double.isFinite(startRangeM) || !Double.isFinite(endRangeM)
                || startRangeM == 0.0 || endRangeM == 0.0
                || Math.signum(startRangeM) != Math.signum(endRangeM)
                || Math.abs(endRangeM) >= Math.abs(startRangeM)) {
            throw new ScenarioValidationException(
                    "glideslope ranges must be non-zero, same side, and closing in (|end| < |start|)");
        }
        String axisNorm = axis == null ? "vbar" : axis.trim().toLowerCase();
        int ax = switch (axisNorm) {
            case "rbar" -> 0; // radial
            case "vbar" -> 1; // in-track
            default -> throw new ScenarioValidationException("glideslope axis must be 'vbar' or 'rbar'");
        };

        Instant startInstant = parseInstant(body.timeRange().start());
        Instant endInstant = parseInstant(body.timeRange().end());
        AbsoluteDate t1 = new AbsoluteDate(startInstant, utc);
        ChiefStateResolver.ChiefState chief = chiefResolver.resolve(body.chief(), Fidelity.SGP4);
        Propagator chiefProp = chief.provider();
        Propagator depProp = propagationService.propagatorFor(tleOf(deputy), Fidelity.SGP4);
        double n = chief.meanMotionRadPerSec();
        double[] s = relativeStateLvlh(chiefProp, depProp, t1);

        double[] rCur = {s[0], s[1], s[2]};
        double[] vCur = {s[3], s[4], s[5]};
        List<ManeuverDraft> drafts = new ArrayList<>();
        double cumT = 0.0;
        // Waypoints p[k] (k = 0..segments) along the axis from start to end; the deputy
        // acquires p[0] from its current state, then walks down to p[segments].
        for (int k = 0; k <= segments; k++) {
            double range = startRangeM + (endRangeM - startRangeM) * ((double) k / segments);
            double[] rT = {0.0, 0.0, 0.0};
            rT[ax] = range;
            double legDist = Math.sqrt(
                    (rT[0] - rCur[0]) * (rT[0] - rCur[0])
                    + (rT[1] - rCur[1]) * (rT[1] - rCur[1])
                    + (rT[2] - rCur[2]) * (rT[2] - rCur[2]));
            double dt = legDist / closingRateMps;
            if (dt <= 0.0) {
                continue; // already at this waypoint (degenerate leg)
            }
            double[] res = CwTargeting.twoImpulse(rCur, vCur, rT, new double[] {0, 0, 0}, n, dt);
            if (res == null) {
                throw new ScenarioValidationException(
                        "a glideslope leg is near an integer number of orbits (CW-singular) — "
                        + "raise the closing rate or segment count");
            }
            Instant epoch = startInstant.plusNanos(Math.round(cumT * 1.0e9));
            if (epoch.isAfter(endInstant)) {
                throw new ScenarioValidationException(
                        "the glideslope runs past the scenario end — shorten the range, raise the "
                        + "closing rate, or extend the time range");
            }
            drafts.add(new ManeuverDraft(deputyNoradId, epoch.toString(), "ric", res[0], res[1], res[2]));
            // Arrival velocity at rT (before any correction): res[3..5] = vT − vArr = −vArr.
            vCur = new double[] {-res[3], -res[4], -res[5]};
            rCur = rT;
            cumT += dt;
        }
        // Final park burn: null the residual velocity at the end point (arrive at rest).
        Instant parkEpoch = startInstant.plusNanos(Math.round(cumT * 1.0e9));
        if (parkEpoch.isAfter(endInstant)) {
            throw new ScenarioValidationException(
                    "the glideslope runs past the scenario end — shorten the range, raise the "
                    + "closing rate, or extend the time range");
        }
        drafts.add(new ManeuverDraft(deputyNoradId, parkEpoch.toString(), "ric", -vCur[0], -vCur[1], -vCur[2]));

        double total = 0.0;
        for (ManeuverDraft d : drafts) {
            total += Math.sqrt(d.r() * d.r() + d.i() * d.i() + d.c() * d.c());
        }
        return scenarioService.addManeuvers(id, drafts, String.format(
                "%s glideslope %.0f→%.0f m @ %.2f m/s (%d-segment CW, Δv total %.2f m/s)",
                axisNorm.toUpperCase(), startRangeM, endRangeM, closingRateMps, segments, total));
    }

    /**
     * Closed-loop station-keeping (US-MAN-10): hold the deputy at a V-bar/R-bar point against
     * the real perturbative drift, with a corrective burn every {@code intervalSec} for
     * {@code corrections} corrections (bounded by the window). Unlike the other templates this
     * is genuinely <em>closed-loop</em>: at each correction it rebuilds the deputy's real
     * (numerical, corrections-so-far) propagator, reads back its actual relative state in the
     * chief LVLH, and solves the CW departure burn that re-aims it at the hold point one
     * interval later. Each correction therefore sees the drift the previous ones left — the
     * burns counteract whatever the real model (and a forced hold point) actually do.
     *
     * <p>The corrections are computed in CW but applied to (and fed back from) the real
     * propagators (chief at the stream fidelity, deputy numerical), so the burn sizes reflect
     * the true cost of the hold. Rebuilding the numerical propagator each step is the dominant
     * cost; {@code corrections} is capped accordingly.
     *
     * @param axis {@code "vbar"} (in-track) or {@code "rbar"} (radial)
     */
    public ScenarioResponse stationKeep(UUID id, int deputyNoradId, String axis,
                                        double distanceM, double intervalSec, int corrections) {
        ScenarioBody body = scenarioService.get(id).body();
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);
        if (body.chief() == null) {
            throw new ScenarioValidationException("station-keeping requires a chief");
        }
        if (!(intervalSec > 0.0) || !Double.isFinite(intervalSec)) {
            throw new ScenarioValidationException("the correction interval must be a positive number of seconds");
        }
        if (corrections < 1 || corrections > 24) {
            throw new ScenarioValidationException("corrections must be between 1 and 24");
        }
        if (!Double.isFinite(distanceM) || distanceM == 0.0) {
            throw new ScenarioValidationException("the hold distance must be a non-zero number of metres");
        }
        String axisNorm = axis == null ? "vbar" : axis.trim().toLowerCase();
        double[] rT = switch (axisNorm) {
            case "rbar" -> new double[] {distanceM, 0.0, 0.0};
            case "vbar" -> new double[] {0.0, distanceM, 0.0};
            default -> throw new ScenarioValidationException("station-keeping axis must be 'vbar' or 'rbar'");
        };

        Instant startInstant = parseInstant(body.timeRange().start());
        Instant endInstant = parseInstant(body.timeRange().end());
        TLE depTle = tleOf(deputy);
        ChiefStateResolver.ChiefState chief =
                chiefResolver.resolve(body.chief(), chiefStreamFidelity(body.fidelity()));
        Propagator chiefProp = chief.provider();
        double n = chief.meanMotionRadPerSec();

        List<Impulse> impulses = new ArrayList<>();
        List<ManeuverDraft> drafts = new ArrayList<>();
        for (int k = 0; k < corrections; k++) {
            Instant epoch = startInstant.plusNanos(Math.round(k * intervalSec * 1.0e9));
            // Need a full interval before the window ends to target the next station point.
            if (epoch.plusNanos(Math.round(intervalSec * 1.0e9)).isAfter(endInstant)) {
                break;
            }
            AbsoluteDate t = new AbsoluteDate(epoch, utc);
            // Rebuild the deputy with the corrections applied so far → its REAL drifted state.
            Propagator depProp = propagationService.propagatorFor(depTle, Fidelity.NUMERICAL, impulses);
            double[] s = relativeStateLvlh(chiefProp, depProp, t);
            double[] res = CwTargeting.twoImpulse(
                    new double[] {s[0], s[1], s[2]}, new double[] {s[3], s[4], s[5]},
                    rT, new double[] {0, 0, 0}, n, intervalSec);
            if (res == null) {
                throw new ScenarioValidationException(
                        "the correction interval is near an integer number of orbits (CW-singular) — "
                        + "pick a different interval");
            }
            impulses.add(new Impulse(t, res[0], res[1], res[2]));
            drafts.add(new ManeuverDraft(deputyNoradId, epoch.toString(), "ric", res[0], res[1], res[2]));
        }
        if (drafts.isEmpty()) {
            throw new ScenarioValidationException(
                    "no corrections fit before the scenario end — shorten the interval or extend the window");
        }
        double total = 0.0;
        for (ManeuverDraft d : drafts) {
            total += Math.sqrt(d.r() * d.r() + d.i() * d.i() + d.c() * d.c());
        }
        return scenarioService.addManeuvers(id, drafts, String.format(
                "%s station-keep @ %.0f m (%d corrections every %.0f s, Δv total %.2f m/s)",
                axisNorm.toUpperCase(), distanceM, drafts.size(), intervalSec, total));
    }

    /**
     * Collision-avoidance maneuver (US-MAN-12): the inverse of {@link #rendezvous}. Preview only —
     * compute the single ΔV that raises the miss distance from the maneuvering {@code deputyNoradId}
     * to the {@code threatNoradId} (the chief or another deputy) at the predicted conjunction
     * {@code tcaEpoch}, up to {@code targetMissM}, along a chosen {@code axis}
     * ({@code crosstrack}/{@code radial}/{@code intrack}), WITHOUT inserting it. See
     * {@link CollisionAvoidancePlanner}.
     *
     * @param burnEpochOrNull when to burn (ISO-8601 UTC); {@code null}/blank → a per-axis default
     *                        (earliest for in-track; ~quarter-orbit before TCA for cross-track).
     */
    public CamPlanResult collisionAvoidancePreview(
            UUID id, int deputyNoradId, int threatNoradId, String tcaEpoch,
            String axis, double targetMissM, String burnEpochOrNull) {
        CamResult r = computeCam(scenarioService.get(id).body(),
                deputyNoradId, threatNoradId, tcaEpoch, axis, targetMissM, burnEpochOrNull);
        CollisionAvoidancePlanner.CamPlan p = r.plan();
        Impulse b = p.burn();
        return new CamPlanResult(deputyNoradId, threatNoradId, p.axis().name().toLowerCase(),
                r.burnInstant().toString(), iso(p.tcaEpoch()), iso(p.achievedTcaEpoch()),
                b.r(), b.i(), b.c(), p.dvMagnitudeMps(), p.baselineMissM(), p.achievedMissM(),
                p.converged(), p.note());
    }

    private String iso(AbsoluteDate d) {
        return d.toDate(utc).toInstant().toString();
    }

    /**
     * Collision-avoidance maneuver (US-MAN-12): compute the avoidance ΔV (as {@link #collisionAvoidancePreview})
     * and insert it as one audited RIC impulse through {@link ScenarioService#addManeuvers}. A
     * partial (converged=false) plan still inserts its best-effort burn with the reason in the audit
     * summary; a burn that is not needed / cannot be produced is a 422.
     */
    public ScenarioResponse collisionAvoidance(
            UUID id, int deputyNoradId, int threatNoradId, String tcaEpoch,
            String axis, double targetMissM, String burnEpochOrNull) {
        CamResult r = computeCam(scenarioService.get(id).body(),
                deputyNoradId, threatNoradId, tcaEpoch, axis, targetMissM, burnEpochOrNull);
        CollisionAvoidancePlanner.CamPlan plan = r.plan();
        if (plan.dvMagnitudeMps() <= 0.0) {
            throw new ScenarioValidationException(plan.note() != null ? plan.note()
                    : "no avoidance burn was produced — the deputy already clears the target miss");
        }
        Impulse b = plan.burn();
        List<ManeuverDraft> drafts = List.of(
                new ManeuverDraft(deputyNoradId, r.burnInstant().toString(), "ric", b.r(), b.i(), b.c()));
        String summary = String.format(
                "Collision avoidance vs %d — %s Δv %.2f m/s, miss %.0f→%.0f m%s",
                threatNoradId, plan.axis().name().toLowerCase(), plan.dvMagnitudeMps(),
                plan.baselineMissM(), plan.achievedMissM(),
                plan.note() == null ? "" : " (" + plan.note() + ")");
        return scenarioService.addManeuvers(id, drafts, summary);
    }

    /** A computed CAM plan plus the burn epoch (kept as an {@link Instant} so insertion uses the
     *  exact epoch rather than round-tripping the plan's {@link AbsoluteDate}). */
    private record CamResult(CollisionAvoidancePlanner.CamPlan plan, Instant burnInstant) {
    }

    private CamResult computeCam(ScenarioBody body, int deputyNoradId, int threatNoradId,
                                 String tcaEpoch, String axisName, double targetMissM,
                                 String burnEpochOrNull) {
        if (!(targetMissM > 0.0) || !Double.isFinite(targetMissM)) {
            throw new ScenarioValidationException("target miss distance must be a positive number of metres");
        }
        // The maneuvering craft must be a deputy (the chief is the immovable LVLH reference).
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);
        if (threatNoradId == deputyNoradId) {
            throw new ScenarioValidationException("the threat must be a different craft than the maneuvering deputy");
        }
        CollisionAvoidancePlanner.Axis axis = CollisionAvoidancePlanner.Axis.parse(axisName);

        Instant startInstant = parseInstant(body.timeRange().start());
        Instant endInstant = parseInstant(body.timeRange().end());
        Instant tcaInstant = parseInstant(tcaEpoch);
        if (!tcaInstant.isAfter(startInstant) || tcaInstant.isAfter(endInstant)) {
            throw new ScenarioValidationException("the conjunction TCA must fall inside the scenario window");
        }

        TLE depTle = tleOf(deputy);
        double nDeputy = depTle.getMeanMotion(); // rad/s
        double periodSec = 2.0 * Math.PI / nDeputy;
        PVCoordinatesProvider threatProvider = resolveThreatProvider(body, threatNoradId);

        Instant burnInstant = (burnEpochOrNull != null && !burnEpochOrNull.isBlank())
                ? parseInstant(burnEpochOrNull)
                : defaultBurnEpoch(axis, tcaInstant, startInstant, periodSec);
        if (burnInstant.isBefore(startInstant)) {
            burnInstant = startInstant;
        }
        if (!burnInstant.isBefore(tcaInstant)) {
            throw new ScenarioValidationException("the burn epoch must be before the conjunction TCA");
        }

        CollisionAvoidancePlanner.CamPlan plan = camPlanner.plan(
                depTle, toImpulses(deputy.maneuvers()), threatProvider, nDeputy,
                new AbsoluteDate(tcaInstant, utc), new AbsoluteDate(burnInstant, utc),
                new AbsoluteDate(endInstant, utc), axis, targetMissM);
        return new CamResult(plan, burnInstant);
    }

    /** Per-axis default burn epoch: earliest for in-track (secular drift ∝ lead time); ~quarter-orbit
     *  before TCA for cross-track (its displacement is a bounded sinusoid peaking there); ~half-orbit
     *  for radial. Clamped into the window by the caller. */
    private static Instant defaultBurnEpoch(CollisionAvoidancePlanner.Axis axis, Instant tca,
                                            Instant start, double periodSec) {
        return switch (axis) {
            case INTRACK -> start;
            case CROSSTRACK -> maxInstant(start, tca.minusMillis(Math.round(periodSec / 4.0 * 1000.0)));
            case RADIAL -> maxInstant(start, tca.minusMillis(Math.round(periodSec / 2.0 * 1000.0)));
        };
    }

    private static Instant maxInstant(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    /** Resolve the threat's state provider at the fidelity the stream flies (so the previewed
     *  achieved miss matches the post-insert conjunction): the chief (TLE or measured ephemeris),
     *  or another deputy (with its own maneuvers). */
    private PVCoordinatesProvider resolveThreatProvider(ScenarioBody body, int threatNoradId) {
        Fidelity streamFidelity = chiefStreamFidelity(body.fidelity());
        if (body.chief() != null && body.chief().noradId() == threatNoradId) {
            Fidelity f = chiefResolver.isMeasured(body.chief()) ? Fidelity.SGP4 : streamFidelity;
            return chiefResolver.resolve(body.chief(), f).provider();
        }
        ScenarioBody.Role threat = body.deputies().stream()
                .filter(d -> d.noradId() == threatNoradId)
                .findFirst()
                .orElseThrow(() -> new ScenarioValidationException(
                        "the threat " + threatNoradId + " is not a craft in this scenario"));
        return propagationService.propagatorFor(
                tleOf(threat), streamFidelity, toImpulses(threat.maneuvers()));
    }

    /** Convert a role's persisted maneuvers to prop-layer impulses (mirrors the stream/screening/MC helper). */
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

    /** The fidelity the scenario flies the chief with (CW models the deputy relatively,
     *  so its chief is on SGP4); used so the corrector matches what the stream executes. */
    private static Fidelity chiefStreamFidelity(String fidelity) {
        Fidelity f = Fidelity.fromString(fidelity);
        return f == Fidelity.CW ? Fidelity.SGP4 : f;
    }

    private static double norm(Impulse imp) {
        return Math.sqrt(imp.r() * imp.r() + imp.i() * imp.i() + imp.c() * imp.c());
    }

    // --- helpers --------------------------------------------------------------

    /**
     * The deputy's relative state in the chief's <em>rotating</em> LVLH frame at {@code t}:
     * {@code [x,y,z,vx,vy,vz]} (radial/in-track/cross, m and m/s). Routes through the
     * canonical {@link FrameService#lvlh} transform so the relative velocity carries the
     * frame rotation (R15 — never the single-epoch helper). These axes match
     * {@link CwTargeting} (radial/in-track/cross).
     */
    private double[] relativeStateLvlh(Propagator chiefProp, Propagator depProp, AbsoluteDate t) {
        Frame eci = frames.eci();
        Frame lvlh = frames.lvlh(chiefProp);
        PVCoordinates rel = eci.getTransformTo(lvlh, t)
                .transformPVCoordinates(depProp.getPVCoordinates(t, eci));
        return new double[] {
            rel.getPosition().getX(), rel.getPosition().getY(), rel.getPosition().getZ(),
            rel.getVelocity().getX(), rel.getVelocity().getY(), rel.getVelocity().getZ(),
        };
    }

    /**
     * Project an ECI ΔV onto the RIC axes of a burn state (r=radial, i=in-track,
     * c=cross), via the canonical {@link FrameService} rather than a hand-rolled
     * basis (Decision 12 / R15). The burn state is wrapped as a fixed
     * {@link AbsolutePVCoordinates} provider so {@code FrameService.ric} builds the
     * same QSW triad it uses everywhere; a ΔV is a free vector, so we rotate it into
     * the RIC axes ({@code transformVector} applies rotation only).
     */
    private double[] toRic(Vector3D dvEci, Vector3D r, Vector3D v, AbsoluteDate date) {
        Frame eci = frames.eci();
        Frame ric = frames.ric(new AbsolutePVCoordinates(eci, date, new PVCoordinates(r, v)));
        Vector3D out = eci.getTransformTo(ric, date).transformVector(dvEci);
        return new double[] {out.getX(), out.getY(), out.getZ()};
    }

    private static ScenarioBody.Role deputyRole(ScenarioBody body, int noradId) {
        if (body.chief() != null && body.chief().noradId() == noradId) {
            throw new ScenarioValidationException("Templates target a deputy, not the chief");
        }
        return body.deputies().stream()
                .filter(d -> d.noradId() == noradId)
                .findFirst()
                .orElseThrow(() -> new ScenarioValidationException(
                        "Deputy " + noradId + " is not in this scenario"));
    }

    private TLE tleOf(ScenarioBody.Role role) {
        ScenarioBody.InitialState state = role.initialState();
        if (state == null || state.tle() == null
                || state.tle().line1() == null || state.tle().line2() == null) {
            throw new ScenarioValidationException(
                    "Role " + role.role() + " (" + role.noradId() + ") has no TLE initial state");
        }
        try {
            return new TLE(state.tle().line1(), state.tle().line2(), utc);
        } catch (RuntimeException e) {
            throw new ScenarioValidationException(
                    "Role " + role.role() + " (" + role.noradId() + ") TLE failed to parse: " + e.getMessage());
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new ScenarioValidationException("epoch is required");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException e1) {
            try {
                return Instant.parse(value);
            } catch (DateTimeException e2) {
                throw new ScenarioValidationException("epoch must be ISO-8601: " + value);
            }
        }
    }
}
