package space.orbit.backend.scenario;

import jakarta.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
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

    private TimeScale utc;

    public ManeuverTemplateService(ScenarioService scenarioService,
                                   PropagationService propagationService,
                                   FrameService frames) {
        this.scenarioService = scenarioService;
        this.propagationService = propagationService;
        this.frames = frames;
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
        ScenarioBody body = scenarioService.get(id).body();
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);

        Frame eci = frames.eci();
        Instant startInstant = parseInstant(body.timeRange().start());
        AbsoluteDate t1 = new AbsoluteDate(startInstant, utc);
        Propagator prop = propagationService.propagatorFor(tleOf(deputy), Fidelity.SGP4);

        double r1 = prop.getPVCoordinates(t1, eci).getPosition().getNorm();
        double r2 = Constants.WGS84_EARTH_EQUATORIAL_RADIUS + targetAltitudeKm * 1000.0;
        if (targetAltitudeKm <= 0 || r2 <= 0) {
            throw new ScenarioValidationException("target altitude must be positive");
        }
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
     * Two-impulse Lambert rendezvous (US-MAN-03): depart the deputy at the scenario
     * start, arrive at the chief's position at {@code arrivalEpoch}. The transfer is
     * solved with Orekit's {@link IodLambert}; the two ΔV are expressed in the burn
     * state's own RIC.
     */
    public ScenarioResponse rendezvous(UUID id, int deputyNoradId, String arrivalEpoch) {
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

        Propagator depProp = propagationService.propagatorFor(tleOf(deputy), Fidelity.SGP4);
        Propagator chiefProp = propagationService.propagatorFor(tleOf(body.chief()), Fidelity.SGP4);
        PVCoordinates dep1 = depProp.getPVCoordinates(t1, eci);
        PVCoordinates chief2 = chiefProp.getPVCoordinates(t2, eci);

        IodLambert lambert = new IodLambert(Constants.WGS84_EARTH_MU);
        Orbit transfer = lambert.estimate(eci, true, 0, dep1.getPosition(), t1, chief2.getPosition(), t2);
        Vector3D vTransferDepart = transfer.getPVCoordinates().getVelocity();
        Vector3D vTransferArrive = new KeplerianPropagator(transfer).getPVCoordinates(t2, eci).getVelocity();

        // Δv1 boosts the deputy onto the transfer; Δv2 matches the chief at arrival.
        Vector3D dv1Eci = vTransferDepart.subtract(dep1.getVelocity());
        Vector3D dv2Eci = chief2.getVelocity().subtract(vTransferArrive);
        double[] ric1 = toRic(dv1Eci, dep1.getPosition(), dep1.getVelocity(), t1);
        double[] ric2 = toRic(dv2Eci, chief2.getPosition(), vTransferArrive, t2);

        List<ManeuverDraft> drafts = List.of(
                new ManeuverDraft(deputyNoradId, startInstant.toString(), "ric", ric1[0], ric1[1], ric1[2]),
                new ManeuverDraft(deputyNoradId, arrInstant.toString(), "ric", ric2[0], ric2[1], ric2[2]));
        double total = dv1Eci.getNorm() + dv2Eci.getNorm();
        return scenarioService.addManeuvers(id, drafts,
                String.format("Lambert rendezvous (Δv total %.2f m/s)", total));
    }

    // --- helpers --------------------------------------------------------------

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
