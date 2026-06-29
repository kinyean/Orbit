package space.orbit.backend.analysis;

import jakarta.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.errors.OrekitException;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.orbits.KeplerianOrbit;
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
import space.orbit.backend.catalog.CatalogService;
import space.orbit.backend.catalog.TrackedSatellite;
import space.orbit.backend.io.MeasuredEphemeris;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.scenario.MeasuredDataset;
import space.orbit.backend.scenario.MeasuredDatasetCodec;
import space.orbit.backend.scenario.MeasuredDatasetRepository;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioService;
import space.orbit.backend.scenario.ScenarioValidationException;

/**
 * Catalog conjunction screening (Phase 8, US-EVT-02 / UC-7 / SRS §3.12.1). Propagates
 * the scenario craft over the scenario window and screens them against the full live
 * SGP4 catalog (Decision 13), returning a sorted list of close approaches below a
 * miss-distance threshold.
 *
 * <p>A request→response REST analysis — NOT the stream (it produces a one-shot list,
 * not playback). It is a <strong>snapshot</strong>: the catalog refreshes every ~6 h,
 * so a screening is not a reproducible scenario artifact; the result is tagged with the
 * run instant ({@link ScreeningResult#ranAt}).
 *
 * <p>Two-stage to keep ~14,500 satellites tractable: a cheap radial <b>shell prune</b>
 * (a pair can only approach within the threshold if their geocentric radius bands
 * overlap to within it — {@code |p1−p2| ≥ ||p1|−|p2||}), then fine sampled
 * closest-approach + a golden-section refine on the survivors (parallelised).
 */
@Service
@DependsOn("orekitConfig")
public class ScreeningService {

    /** Cap on grid samples over the window (coarse — the refine recovers precision). */
    private static final int MAX_SAMPLES = 200;
    /** Tabulated-ephemeris interpolation points (cubic Hermite — see ScenarioStreamService). */
    private static final int EPHEMERIS_INTERP_POINTS = 2;
    private static final int GOLDEN_ITERS = 40;

    private final ScenarioService scenarioService;
    private final PropagationService propagationService;
    private final FrameService frames;
    private final CatalogService catalog;
    private final MeasuredDatasetRepository measuredDatasets;

    private TimeScale utc;

    public ScreeningService(ScenarioService scenarioService, PropagationService propagationService,
                            FrameService frames, CatalogService catalog,
                            MeasuredDatasetRepository measuredDatasets) {
        this.scenarioService = scenarioService;
        this.propagationService = propagationService;
        this.frames = frames;
        this.catalog = catalog;
        this.measuredDatasets = measuredDatasets;
    }

    @PostConstruct
    void init() {
        utc = TimeScalesFactory.getUTC(); // safe: @DependsOn orekitConfig
    }

    /** A scenario craft prepared for screening: its provider, orbit shell, and ECI grid. */
    private record ScenarioCraft(int noradId, String name, PVCoordinatesProvider provider,
                                 double perigeeM, double apogeeM, double[] eciGrid) {
    }

    /**
     * Screen the scenario against the live catalog (owner-gated on the request thread
     * via {@link ScenarioService#get}). The body is fetched up front, before the
     * parallel propagation, so no security context crosses thread boundaries.
     *
     * @param thresholdKm report approaches closer than this (km)
     */
    public ScreeningResult screen(UUID id, double thresholdKm) {
        if (!(thresholdKm > 0)) {
            throw new ScenarioValidationException("screening threshold must be a positive number of km");
        }
        double thresholdM = thresholdKm * 1000.0;
        ScenarioBody body = scenarioService.get(id).body();
        Fidelity fidelity = Fidelity.fromString(body.fidelity());

        Instant start = parseInstant(body.timeRange().start());
        Instant end = parseInstant(body.timeRange().end());
        long durationSec = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
        AbsoluteDate startDate = new AbsoluteDate(start, utc);
        int step = (int) Math.max(30, durationSec / (MAX_SAMPLES - 1));
        int steps = (int) Math.min(MAX_SAMPLES - 1, durationSec / step);

        List<ScenarioBody.Role> roles = new ArrayList<>();
        roles.add(body.chief());
        roles.addAll(body.deputies());
        List<ScenarioCraft> crafts = new ArrayList<>();
        java.util.Set<Integer> scenarioIds = new java.util.HashSet<>();
        for (ScenarioBody.Role role : roles) {
            crafts.add(buildCraft(role, fidelity, startDate, step, steps));
            scenarioIds.add(role.noradId());
        }

        List<TrackedSatellite> tracked = catalog.tracked();
        java.util.concurrent.atomic.AtomicInteger candidates = new java.util.concurrent.atomic.AtomicInteger();
        List<ConjunctionResult> results = tracked.parallelStream()
                .filter(sat -> !scenarioIds.contains(sat.noradId()))
                .flatMap(sat -> screenOne(sat, crafts, startDate, step, steps, durationSec,
                        thresholdM, start, candidates).stream())
                .sorted(Comparator.comparingDouble(ConjunctionResult::missDistanceM))
                .toList();

        return new ScreeningResult(thresholdM, Instant.now().toString(),
                tracked.size(), candidates.get(), results);
    }

    /** Screen one catalog satellite against every scenario craft (after a shell prune). */
    private List<ConjunctionResult> screenOne(TrackedSatellite sat, List<ScenarioCraft> crafts,
                                              AbsoluteDate startDate, int step, int steps, long durationSec,
                                              double thresholdM, Instant epoch,
                                              java.util.concurrent.atomic.AtomicInteger candidates) {
        // Coarse shell prune: keep the craft only if its radial band can come within
        // the threshold of the catalog sat's band.
        List<ScenarioCraft> near = new ArrayList<>(crafts.size());
        for (ScenarioCraft c : crafts) {
            double gap = Math.max(0.0, Math.max(sat.perigeeRadiusM() - c.apogeeM(), c.perigeeM() - sat.apogeeRadiusM()));
            if (gap <= thresholdM) {
                near.add(c);
            }
        }
        if (near.isEmpty()) {
            return List.of();
        }
        candidates.incrementAndGet();

        List<ConjunctionResult> rows = new ArrayList<>();
        try {
            // Per-craft running minimum + bracket, found on the shared sampling grid.
            double[] minDist = new double[near.size()];
            double[] minT = new double[near.size()];
            java.util.Arrays.fill(minDist, Double.POSITIVE_INFINITY);
            for (int k = 0; k <= steps; k++) {
                double t = (double) k * step;
                Vector3D pSat = sat.propagator().getPVCoordinates(startDate.shiftedBy(t), frames.eci()).getPosition();
                for (int i = 0; i < near.size(); i++) {
                    double[] g = near.get(i).eciGrid();
                    double dx = pSat.getX() - g[k * 3];
                    double dy = pSat.getY() - g[k * 3 + 1];
                    double dz = pSat.getZ() - g[k * 3 + 2];
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d < minDist[i]) {
                        minDist[i] = d;
                        minT[i] = t;
                    }
                }
            }
            for (int i = 0; i < near.size(); i++) {
                if (minDist[i] >= thresholdM) {
                    continue;
                }
                ScenarioCraft c = near.get(i);
                double[] tca = refine(sat.propagator(), c.provider(), startDate,
                        Math.max(0.0, minT[i] - step), Math.min((double) durationSec, minT[i] + step),
                        minT[i], minDist[i]);
                if (tca[1] < thresholdM) {
                    rows.add(new ConjunctionResult(c.noradId(), c.name(), sat.noradId(), sat.name(),
                            epoch.plusMillis(Math.round(tca[0] * 1000.0)).toString(), tca[1]));
                }
            }
        } catch (OrekitException leftDomain) {
            return rows; // a catalog sat that won't propagate this window is simply skipped
        }
        return rows;
    }

    /** Golden-section refine of the ECI separation minimum over {@code [lo,hi]}. */
    private double[] refine(PVCoordinatesProvider a, PVCoordinatesProvider b, AbsoluteDate startDate,
                            double lo, double hi, double coarseT, double coarseDist) {
        if (!(hi > lo)) {
            return new double[] {coarseT, coarseDist};
        }
        final double gr = (Math.sqrt(5.0) - 1.0) / 2.0;
        double aL = lo;
        double bH = hi;
        double c = bH - gr * (bH - aL);
        double d = aL + gr * (bH - aL);
        double fc = dist(a, b, startDate, c);
        double fd = dist(a, b, startDate, d);
        for (int it = 0; it < GOLDEN_ITERS; it++) {
            if (fc < fd) {
                bH = d;
                d = c;
                fd = fc;
                c = bH - gr * (bH - aL);
                fc = dist(a, b, startDate, c);
            } else {
                aL = c;
                c = d;
                fc = fd;
                d = aL + gr * (bH - aL);
                fd = dist(a, b, startDate, d);
            }
        }
        double tBest = 0.5 * (aL + bH);
        double fBest = dist(a, b, startDate, tBest);
        return fBest <= coarseDist ? new double[] {tBest, fBest} : new double[] {coarseT, coarseDist};
    }

    private double dist(PVCoordinatesProvider a, PVCoordinatesProvider b, AbsoluteDate startDate, double t) {
        try {
            AbsoluteDate date = startDate.shiftedBy(t);
            Vector3D pa = a.getPVCoordinates(date, frames.eci()).getPosition();
            Vector3D pb = b.getPVCoordinates(date, frames.eci()).getPosition();
            return Vector3D.distance(pa, pb);
        } catch (OrekitException leftDomain) {
            return Double.POSITIVE_INFINITY;
        }
    }

    /** Build a scenario craft's provider + shell + precomputed ECI grid over the window. */
    private ScenarioCraft buildCraft(ScenarioBody.Role role, Fidelity fidelity,
                                     AbsoluteDate startDate, int step, int steps) {
        ScenarioBody.InitialState state = role.initialState();
        PVCoordinatesProvider provider;
        double perigeeM;
        double apogeeM;
        if (state != null && "ephemeris".equals(state.kind())) {
            Ephemeris eph = buildEphemeris(role, state.datasetId());
            provider = eph;
            KeplerianOrbit k0 = new KeplerianOrbit(eph.getInitialState().getOrbit());
            perigeeM = k0.getA() * (1.0 - k0.getE());
            apogeeM = k0.getA() * (1.0 + k0.getE());
        } else {
            TLE tle = rebuildTle(role);
            // CW is a relative model; for absolute screening, propagate the craft on SGP4.
            Fidelity f = fidelity == Fidelity.CW ? Fidelity.SGP4 : fidelity;
            provider = propagationService.propagatorFor(tle, f, toImpulses(role.maneuvers()));
            double a = Math.cbrt(Constants.WGS84_EARTH_MU / (tle.getMeanMotion() * tle.getMeanMotion()));
            perigeeM = a * (1.0 - tle.getE());
            apogeeM = a * (1.0 + tle.getE());
        }
        double[] grid = new double[(steps + 1) * 3];
        for (int k = 0; k <= steps; k++) {
            Vector3D p = provider.getPVCoordinates(startDate.shiftedBy((double) k * step), frames.eci()).getPosition();
            grid[k * 3] = p.getX();
            grid[k * 3 + 1] = p.getY();
            grid[k * 3 + 2] = p.getZ();
        }
        return new ScenarioCraft(role.noradId(), role.name(), provider, perigeeM, apogeeM, grid);
    }

    private Ephemeris buildEphemeris(ScenarioBody.Role role, String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new ScenarioValidationException("ephemeris role " + role.noradId() + " has no datasetId");
        }
        MeasuredDataset ds;
        try {
            ds = measuredDatasets.findById(UUID.fromString(datasetId))
                    .orElseThrow(() -> new ScenarioValidationException("measured dataset " + datasetId + " not found"));
        } catch (IllegalArgumentException badUuid) {
            throw new ScenarioValidationException("invalid datasetId \"" + datasetId + "\"");
        }
        List<MeasuredEphemeris.Sample> samples = MeasuredDatasetCodec.decode(ds.getSamples()).samples();
        if (samples.size() < 2) {
            throw new ScenarioValidationException("measured dataset " + datasetId + " has too few states");
        }
        double mu = Constants.WGS84_EARTH_MU;
        List<SpacecraftState> states = new ArrayList<>(samples.size());
        for (MeasuredEphemeris.Sample s : samples) {
            AbsoluteDate date = new AbsoluteDate(Instant.ofEpochMilli(s.epochMillis()), utc);
            PVCoordinates pv = new PVCoordinates(
                    new Vector3D(s.px(), s.py(), s.pz()), new Vector3D(s.vx(), s.vy(), s.vz()));
            states.add(new SpacecraftState(new CartesianOrbit(pv, frames.eci(), date, mu)));
        }
        return new Ephemeris(states, Math.min(EPHEMERIS_INTERP_POINTS, states.size()));
    }

    private List<Impulse> toImpulses(List<ScenarioBody.Maneuver> maneuvers) {
        if (maneuvers == null || maneuvers.isEmpty()) {
            return List.of();
        }
        List<Impulse> impulses = new ArrayList<>(maneuvers.size());
        for (ScenarioBody.Maneuver m : maneuvers) {
            if (m.deltaV() == null || m.epoch() == null) {
                continue;
            }
            impulses.add(new Impulse(new AbsoluteDate(parseInstant(m.epoch()), utc),
                    m.deltaV().r(), m.deltaV().i(), m.deltaV().c(), m.thrustN(), m.ispSec()));
        }
        return impulses;
    }

    private TLE rebuildTle(ScenarioBody.Role role) {
        ScenarioBody.InitialState state = role.initialState();
        if (state == null || state.tle() == null || state.tle().line1() == null || state.tle().line2() == null) {
            throw new ScenarioValidationException(
                    "Role " + role.role() + " (" + role.noradId() + ") has no TLE initial state to screen");
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
