package space.orbit.backend.analysis;

import jakarta.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.EigenDecompositionSymmetric;
import org.orekit.errors.OrekitException;
import org.orekit.frames.Frame;
import org.orekit.frames.Transform;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScale;
import org.orekit.time.TimeScalesFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.StateVector;
import space.orbit.backend.scenario.ChiefStateResolver;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioService;
import space.orbit.backend.scenario.ScenarioValidationException;

/**
 * Monte Carlo dispersion + covariance analysis (Phase 9C, UC-6, US-MC-01/02). Disperses a
 * deputy's initial state (Gaussian position/velocity σ) and maneuver execution (ΔV
 * magnitude % + pointing σ), propagates each sample against the real engine, expresses it
 * in the chief LVLH frame, and aggregates a trajectory cloud + per-epoch covariance
 * ellipsoids. A request→response analysis (like {@link ScreeningService}), not the stream.
 *
 * <p>The chief may be TLE-sourced or a measured ephemeris (resolved via
 * {@link ChiefStateResolver} — it is only the LVLH reference). Dispersion itself stays
 * deputy/TLE-only: perturbing measured truth is meaningless.
 *
 * <p><b>Reproducible (SRS §5.4.1, R11) — the crux.</b> This is the codebase's first RNG.
 * Each sample {@code i} derives its own {@link SplittableRandom} purely from {@code (seed,
 * i)} (a SplitMix64 mix), with a fixed intra-sample draw order; the parallel stream is
 * collected in index order, so execution order never affects the result. Same {@code
 * (scenario, seed, params)} → byte-identical output.
 */
@Service
@DependsOn("orekitConfig")
public class MonteCarloService {

    // Each sample is a full numerical propagation over the window (R18 cost), so the
    // default is modest for an interactive request; the cap lets a user opt into more.
    static final int DEFAULT_SAMPLES = 100;
    static final int MAX_SAMPLES = 500;
    static final int MAX_GRID = 120;       // grid-point cap over the window
    static final int MAX_TRACKS = 150;     // spaghetti cap (covariance still uses ALL samples)
    static final int MAX_ELLIPSOIDS = 16;  // ellipsoid-epoch cap
    static final int TRACK_POINTS = 60;    // positions per returned track
    /** Bounded sample concurrency — each sample is a heavy numerical propagator, so cap
     *  it well below core count to keep peak memory in check (result is pool-independent). */
    static final int MC_PARALLELISM = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, 6));

    private final ScenarioService scenarioService;
    private final PropagationService propagationService;
    private final FrameService frames;
    private final ChiefStateResolver chiefResolver;

    private TimeScale utc;

    public MonteCarloService(ScenarioService scenarioService, PropagationService propagationService,
                             FrameService frames, ChiefStateResolver chiefResolver) {
        this.scenarioService = scenarioService;
        this.propagationService = propagationService;
        this.frames = frames;
        this.chiefResolver = chiefResolver;
    }

    @PostConstruct
    void init() {
        utc = TimeScalesFactory.getUTC();
    }

    /** Dispersion inputs (1-σ initial-state uncertainty + maneuver execution error). */
    public record Params(int sampleCount, long seed, double posSigmaM, double velSigmaMs,
                         double dvMagFrac, double dvPointingDeg) {
    }

    /** Run the dispersion for one deputy (owner-gated up front, before any parallelism). */
    public MonteCarloResult analyze(UUID id, int deputyNoradId, Params p) {
        int samples = p.sampleCount() <= 0 ? DEFAULT_SAMPLES : Math.min(MAX_SAMPLES, p.sampleCount());
        ScenarioBody body = scenarioService.get(id).body();
        if (body.chief() == null) {
            throw new ScenarioValidationException("Monte Carlo requires a chief");
        }
        ScenarioBody.Role deputy = deputyRole(body, deputyNoradId);
        Fidelity fidelity = Fidelity.fromString(body.fidelity());
        Fidelity propFidelity = fidelity == Fidelity.CW ? Fidelity.SGP4 : fidelity;

        Instant start = parseInstant(body.timeRange().start());
        Instant end = parseInstant(body.timeRange().end());
        long durationSec = Math.max(1, end.getEpochSecond() - start.getEpochSecond());
        AbsoluteDate startDate = new AbsoluteDate(start, utc);
        int step = (int) Math.max(30, durationSec / (MAX_GRID - 1));
        int steps = (int) Math.min(MAX_GRID - 1, durationSec / step);

        // Chief LVLH transform per grid step — sampled SERIALLY (the shared chief propagator
        // is not safe for concurrent access); the immutable Transforms are then shared across
        // the parallel samples.
        Frame eci = frames.eci();
        Propagator chiefProp = chiefResolver.resolve(body.chief(), propFidelity).provider();
        Frame lvlh = frames.lvlh(chiefProp);
        Transform[] toLvlh = new Transform[steps + 1];
        try {
            for (int k = 0; k <= steps; k++) {
                toLvlh[k] = eci.getTransformTo(lvlh, startDate.shiftedBy((double) k * step));
            }
        } catch (OrekitException outsideSpan) {
            // A measured chief is a tabulated ephemeris that throws outside its data span —
            // and PUT can set any window. User-fixable input, not a server fault.
            throw new ScenarioValidationException(
                    "the chief's state source does not cover the scenario window ("
                            + body.timeRange().start() + " … " + body.timeRange().end() + "): "
                            + outsideSpan.getMessage());
        }

        // Nominal deputy state at the scenario start — the perturbation reference.
        StateVector nominalStart = propagationService.sample(
                propagationService.propagatorFor(rebuildTle(deputy), propFidelity), startDate);
        List<Impulse> nominalImpulses = toImpulses(deputy.maneuvers());

        final int fSteps = steps;
        final int fStep = step;
        // Parallel over samples in a BOUNDED pool: each sample is a heavy numerical
        // propagator (gravity field + atmosphere), so running one-per-core would spike
        // memory; cap concurrency. Per-sample seeded RNG + index-ordered collect keep the
        // result deterministic regardless of pool size (R11).
        List<double[]> tracks;
        try (ForkJoinPool pool = new ForkJoinPool(MC_PARALLELISM)) {
            tracks = pool.submit(() -> IntStream.range(0, samples).parallel()
                    .mapToObj(i -> sampleTrack(i, p, nominalStart, nominalImpulses, startDate, fStep, fSteps, toLvlh))
                    .collect(Collectors.toList())).get();
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Monte Carlo propagation failed", ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Monte Carlo interrupted", ex);
        }

        // Covariance ellipsoids from ALL samples (full statistic).
        List<EllipsoidSample> ellipsoids = new ArrayList<>();
        int ellipStride = Math.max(1, (steps + 1) / MAX_ELLIPSOIDS);
        for (int k = 0; k <= steps; k += ellipStride) {
            ellipsoids.add(ellipsoidAt(k, tracks, samples, (double) k * step));
        }

        // Thin the drawn spaghetti (covariance is unaffected). No silent truncation (R8).
        int trackStride = Math.max(1, samples / MAX_TRACKS);
        int cloudStride = Math.max(1, (steps + 1 + TRACK_POINTS - 1) / TRACK_POINTS);
        List<double[]> outTracks = new ArrayList<>();
        for (int i = 0; i < samples; i += trackStride) {
            double[] full = tracks.get(i);
            List<Double> pts = new ArrayList<>();
            for (int k = 0; k <= steps; k += cloudStride) {
                pts.add(full[k * 3]);
                pts.add(full[k * 3 + 1]);
                pts.add(full[k * 3 + 2]);
            }
            double[] t = new double[pts.size()];
            for (int j = 0; j < t.length; j++) {
                t[j] = pts.get(j);
            }
            outTracks.add(t);
        }

        return new MonteCarloResult(deputyNoradId, deputy.name(), p.seed(), samples, outTracks.size(),
                Instant.now().toString(), start.toEpochMilli(), step, cloudStride, outTracks, ellipsoids, 0);
    }

    /** One sample's relative-position track in the chief LVLH frame (flat [x,y,z]·(steps+1)). */
    private double[] sampleTrack(int i, Params p, StateVector nominalStart, List<Impulse> nominalImpulses,
                                 AbsoluteDate startDate, int step, int steps, Transform[] toLvlh) {
        SplittableRandom rng = new SplittableRandom(mix(p.seed(), i));
        // Fixed intra-sample draw order: position xyz, velocity xyz, then per maneuver.
        Vector3D dPos = new Vector3D(rng.nextGaussian() * p.posSigmaM(),
                rng.nextGaussian() * p.posSigmaM(), rng.nextGaussian() * p.posSigmaM());
        Vector3D dVel = new Vector3D(rng.nextGaussian() * p.velSigmaMs(),
                rng.nextGaussian() * p.velSigmaMs(), rng.nextGaussian() * p.velSigmaMs());
        StateVector seed = new StateVector(nominalStart.position().add(dPos),
                nominalStart.velocity().add(dVel), nominalStart.date(), frames.eci());
        List<Impulse> impulses = perturbImpulses(nominalImpulses, p, rng);

        Propagator prop = propagationService.propagatorFor(seed, impulses);
        double[] track = new double[(steps + 1) * 3];
        for (int k = 0; k <= steps; k++) {
            Vector3D rel;
            try {
                Vector3D depEci = propagationService.sample(prop, startDate.shiftedBy((double) k * step)).position();
                rel = toLvlh[k].transformPosition(depEci);
            } catch (OrekitException leftDomain) {
                // A dispersed sample may decay out of the domain — hold the last valid point
                // so the track stays finite (mirrors the stream's HOLD).
                if (k > 0) {
                    track[k * 3] = track[(k - 1) * 3];
                    track[k * 3 + 1] = track[(k - 1) * 3 + 1];
                    track[k * 3 + 2] = track[(k - 1) * 3 + 2];
                }
                continue;
            }
            track[k * 3] = rel.getX();
            track[k * 3 + 1] = rel.getY();
            track[k * 3 + 2] = rel.getZ();
        }
        return track;
    }

    /** Perturb each maneuver: ΔV magnitude (fractional) + pointing (tilt by a Gaussian angle). */
    private List<Impulse> perturbImpulses(List<Impulse> nominal, Params p, SplittableRandom rng) {
        if (nominal.isEmpty()) {
            return nominal;
        }
        double pointRad = Math.toRadians(p.dvPointingDeg());
        List<Impulse> out = new ArrayList<>(nominal.size());
        for (Impulse imp : nominal) {
            Vector3D v = new Vector3D(imp.r(), imp.i(), imp.c());
            double mag = v.getNorm();
            if (mag < 1.0e-9) {
                out.add(imp);
                continue;
            }
            double newMag = mag * (1.0 + rng.nextGaussian() * p.dvMagFrac());
            Vector3D u = v.scalarMultiply(1.0 / mag);
            double theta = rng.nextGaussian() * pointRad;
            double phi = rng.nextDouble() * 2.0 * Math.PI;
            Vector3D e1 = perpendicular(u);
            Vector3D e2 = Vector3D.crossProduct(u, e1);
            Vector3D tilt = e1.scalarMultiply(Math.cos(phi)).add(e2.scalarMultiply(Math.sin(phi)));
            Vector3D newU = u.scalarMultiply(Math.cos(theta)).add(tilt.scalarMultiply(Math.sin(theta)));
            Vector3D nv = newU.scalarMultiply(newMag);
            // Preserve finite-burn parameters: dispersion perturbs the achieved ΔV (so a
            // finite burn's duration recomputes from the perturbed magnitude), not the engine.
            out.add(new Impulse(imp.epoch(), nv.getX(), nv.getY(), nv.getZ(), imp.thrustN(), imp.ispSec()));
        }
        return out;
    }

    /** Covariance ellipsoid of the relative-position cloud at grid step {@code k}. */
    private EllipsoidSample ellipsoidAt(int k, List<double[]> tracks, int samples, double tSeconds) {
        double mx = 0;
        double my = 0;
        double mz = 0;
        for (int i = 0; i < samples; i++) {
            double[] t = tracks.get(i);
            mx += t[k * 3];
            my += t[k * 3 + 1];
            mz += t[k * 3 + 2];
        }
        mx /= samples;
        my /= samples;
        mz /= samples;
        double cxx = 0;
        double cyy = 0;
        double czz = 0;
        double cxy = 0;
        double cxz = 0;
        double cyz = 0;
        for (int i = 0; i < samples; i++) {
            double[] t = tracks.get(i);
            double dx = t[k * 3] - mx;
            double dy = t[k * 3 + 1] - my;
            double dz = t[k * 3 + 2] - mz;
            cxx += dx * dx;
            cyy += dy * dy;
            czz += dz * dz;
            cxy += dx * dy;
            cxz += dx * dz;
            cyz += dy * dz;
        }
        double denom = Math.max(1, samples - 1);
        double[][] cov = {
            {cxx / denom, cxy / denom, cxz / denom},
            {cxy / denom, cyy / denom, cyz / denom},
            {cxz / denom, cyz / denom, czz / denom},
        };
        EigenDecompositionSymmetric eig = new EigenDecompositionSymmetric(new Array2DRowRealMatrix(cov, false));
        double[] ev = eig.getEigenvalues();
        Integer[] order = {0, 1, 2};
        java.util.Arrays.sort(order, Comparator.comparingDouble((Integer j) -> ev[j]).reversed());
        Vector3D[] ax = new Vector3D[3];
        double[] sigma1 = new double[3];
        for (int j = 0; j < 3; j++) {
            int idx = order[j];
            ax[j] = canonicalSign(toVec(eig.getEigenvector(idx)));
            sigma1[j] = Math.sqrt(Math.max(0.0, ev[idx]));
        }
        // Force a right-handed basis so the matrix is a proper rotation (det = +1).
        if (Vector3D.dotProduct(Vector3D.crossProduct(ax[0], ax[1]), ax[2]) < 0) {
            ax[2] = ax[2].negate();
        }
        double[][] rot = {
            {ax[0].getX(), ax[1].getX(), ax[2].getX()},
            {ax[0].getY(), ax[1].getY(), ax[2].getY()},
            {ax[0].getZ(), ax[1].getZ(), ax[2].getZ()},
        };
        double[] quat = FrameService.matrixToQuaternionXyzw(rot);
        return new EllipsoidSample(tSeconds, new double[] {mx, my, mz},
                sigma1, new double[] {3.0 * sigma1[0], 3.0 * sigma1[1], 3.0 * sigma1[2]}, quat);
    }

    // --- helpers --------------------------------------------------------------

    /** SplitMix64 finalizer — an independent stream seed per sample (order-independent). */
    private static long mix(long seed, int i) {
        long z = seed + 0x9E3779B97F4A7C15L * (i + 1L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static Vector3D toVec(org.hipparchus.linear.RealVector v) {
        return new Vector3D(v.getEntry(0), v.getEntry(1), v.getEntry(2)).normalize();
    }

    /** Deterministic eigenvector sign: make the largest-magnitude component positive. */
    private static Vector3D canonicalSign(Vector3D v) {
        double ax = Math.abs(v.getX());
        double ay = Math.abs(v.getY());
        double az = Math.abs(v.getZ());
        double dominant = ax >= ay && ax >= az ? v.getX() : (ay >= az ? v.getY() : v.getZ());
        return dominant < 0 ? v.negate() : v;
    }

    /** A unit vector perpendicular to {@code u}. */
    private static Vector3D perpendicular(Vector3D u) {
        Vector3D ref = Math.abs(u.getX()) < 0.9 ? Vector3D.PLUS_I : Vector3D.PLUS_J;
        return Vector3D.crossProduct(u, ref).normalize();
    }

    private static ScenarioBody.Role deputyRole(ScenarioBody body, int noradId) {
        if (body.chief() != null && body.chief().noradId() == noradId) {
            throw new ScenarioValidationException("Monte Carlo disperses a deputy, not the chief");
        }
        return body.deputies().stream()
                .filter(d -> d.noradId() == noradId)
                .findFirst()
                .orElseThrow(() -> new ScenarioValidationException(
                        "Deputy " + noradId + " is not in this scenario"));
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
                    "Monte Carlo disperses TLE-backed deputies; role " + role.role() + " (" + role.noradId()
                            + ") is not TLE-backed");
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
