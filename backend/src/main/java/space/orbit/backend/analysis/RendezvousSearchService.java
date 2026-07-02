package space.orbit.backend.analysis;

import jakarta.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
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
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.scenario.ChiefStateResolver;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioService;
import space.orbit.backend.scenario.ScenarioValidationException;

/**
 * Rendezvous arrival-time × revolution ΔV search (Phase 9A, US-MAN-03). A request→
 * response analysis (like {@link ScreeningService}, not the stream): sweep a grid of
 * arrival epochs across the scenario window × revolution counts, cost each cell with
 * the cheap two-body Lambert, and return a sorted ΔV map. The UI shows a heatmap/table
 * so the user can pick a feasible/cheap transfer; the chosen cell then feeds the
 * differential corrector (which is expensive — we never correct the whole grid).
 *
 * <p>Thread-safety + determinism (R11): the chief's PV is sampled on the grid
 * <em>serially</em> up front (Orekit propagators aren't safe for concurrent calls on
 * one instance), so each parallel cell is a <em>pure</em> Lambert solve over immutable
 * positions; the result is then sorted, so stream order never shows.
 */
@Service
@DependsOn("orekitConfig")
public class RendezvousSearchService {

    /** Cap on arrival-epoch grid samples (keeps the heatmap readable + the sweep fast). */
    static final int MAX_ARRIVAL_SAMPLES = 60;
    /** Revolution-count cap per arrival cell. */
    static final int MAX_REVS = 6;

    private final ScenarioService scenarioService;
    private final PropagationService propagationService;
    private final FrameService frames;
    private final ChiefStateResolver chiefResolver;

    private TimeScale utc;

    public RendezvousSearchService(ScenarioService scenarioService,
                                   PropagationService propagationService, FrameService frames,
                                   ChiefStateResolver chiefResolver) {
        this.scenarioService = scenarioService;
        this.propagationService = propagationService;
        this.frames = frames;
        this.chiefResolver = chiefResolver;
    }

    @PostConstruct
    void init() {
        utc = TimeScalesFactory.getUTC();
    }

    /** Sweep the arrival × revolution ΔV grid for a deputy rendezvous (owner-gated via
     *  {@link ScenarioService#get} on the request thread, before any parallelism). */
    public RendezvousSearchResult search(UUID id, int deputyNoradId) {
        ScenarioBody body = scenarioService.get(id).body();
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);
        if (body.chief() == null) {
            throw new ScenarioValidationException("rendezvous requires a chief");
        }

        Frame eci = frames.eci();
        Instant start = parseInstant(body.timeRange().start());
        Instant end = parseInstant(body.timeRange().end());
        long windowSec = end.getEpochSecond() - start.getEpochSecond();
        if (windowSec <= 0) {
            throw new ScenarioValidationException("scenario time range is empty");
        }
        AbsoluteDate t1 = new AbsoluteDate(start, utc);

        TLE depTle = rebuildTle(deputy);
        // The chief may be a measured ephemeris (e.g. TELEOS-2) — resolve its state provider
        // either way; the deputy must be TLE-backed (it's the one we maneuver).
        Propagator depProp = propagationService.propagatorFor(depTle, Fidelity.SGP4);
        Propagator chiefProp = chiefResolver.resolve(body.chief(), Fidelity.SGP4).provider();
        PVCoordinates dep1 = depProp.getPVCoordinates(t1, eci);
        Vector3D depPos = dep1.getPosition();
        Vector3D depVel = dep1.getVelocity();

        double period = 2.0 * Math.PI / depTle.getMeanMotion();
        // Arrivals span (t1 + minLead, end]; need a minimum transfer time so the grid
        // doesn't start at a degenerate zero-time Lambert.
        double minLead = Math.max(60.0, 0.05 * period);
        if (minLead >= windowSec) {
            throw new ScenarioValidationException(
                    "scenario window is too short for a rendezvous search");
        }
        int arrivalCount = (int) Math.min(MAX_ARRIVAL_SAMPLES, Math.max(2, (windowSec - (long) minLead) / 60 + 1));
        double span = windowSec - minLead;
        double step = span / (arrivalCount - 1);

        // Serial chief-grid sample (thread-safe; one propagator instance).
        AbsoluteDate[] arrDates = new AbsoluteDate[arrivalCount];
        Vector3D[] chiefPos = new Vector3D[arrivalCount];
        Vector3D[] chiefVel = new Vector3D[arrivalCount];
        for (int a = 0; a < arrivalCount; a++) {
            double dt = minLead + a * step;
            AbsoluteDate t2 = t1.shiftedBy(dt);
            arrDates[a] = t2;
            PVCoordinates pv = chiefProp.getPVCoordinates(t2, eci);
            chiefPos[a] = pv.getPosition();
            chiefVel[a] = pv.getVelocity();
        }

        // Pure parallel Lambert solves over the immutable grid.
        List<DvCell> cells = IntStream.range(0, arrivalCount).parallel()
                .mapToObj(a -> solveCell(eci, t1, arrDates[a], depPos, depVel,
                        chiefPos[a], chiefVel[a], period))
                .flatMap(List::stream)
                .sorted(Comparator.comparingDouble(DvCell::totalDvMs))
                .toList();

        DvCell cheapest = cells.isEmpty() ? null : cells.get(0);
        return new RendezvousSearchResult(deputyNoradId, body.timeRange().start(), body.timeRange().end(),
                arrivalCount, MAX_REVS + 1, cells, cheapest);
    }

    /** Every feasible revolution-count cell for one arrival epoch (a pure computation). */
    private List<DvCell> solveCell(Frame eci, AbsoluteDate t1, AbsoluteDate t2,
                                   Vector3D depPos, Vector3D depVel,
                                   Vector3D chiefPos, Vector3D chiefVel, double period) {
        int maxRev = Math.min(MAX_REVS, (int) Math.floor(t2.durationFrom(t1) / period));
        IodLambert lambert = new IodLambert(Constants.WGS84_EARTH_MU);
        List<DvCell> out = new ArrayList<>();
        for (int nRev = 0; nRev <= maxRev; nRev++) {
            try {
                Orbit transfer = lambert.estimate(eci, true, nRev, depPos, t1, chiefPos, t2);
                if (transfer == null) {
                    continue;
                }
                Vector3D vd = transfer.getPVCoordinates().getVelocity();
                Vector3D va = new KeplerianPropagator(transfer).getPVCoordinates(t2, eci).getVelocity();
                double dv1 = vd.subtract(depVel).getNorm();
                double dv2 = chiefVel.subtract(va).getNorm();
                out.add(new DvCell(t2.toString(), nRev, dv1, dv2, dv1 + dv2));
            } catch (RuntimeException infeasible) {
                // no solution for this revolution count / geometry — skip it
            }
        }
        return out;
    }

    private static ScenarioBody.Role deputyRole(ScenarioBody body, int noradId) {
        if (body.chief() != null && body.chief().noradId() == noradId) {
            throw new ScenarioValidationException("rendezvous targets a deputy, not the chief");
        }
        return body.deputies().stream()
                .filter(d -> d.noradId() == noradId)
                .findFirst()
                .orElseThrow(() -> new ScenarioValidationException(
                        "Deputy " + noradId + " is not in this scenario"));
    }

    private TLE rebuildTle(ScenarioBody.Role role) {
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

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            throw new ScenarioValidationException("scenario time range is incomplete");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeException e1) {
            try {
                return Instant.parse(value);
            } catch (DateTimeException e2) {
                throw new ScenarioValidationException("scenario time is not ISO-8601: " + value);
            }
        }
    }
}
