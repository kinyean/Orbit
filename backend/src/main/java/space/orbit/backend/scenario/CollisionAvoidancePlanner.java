package space.orbit.backend.scenario;

import java.util.ArrayList;
import java.util.List;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.errors.OrekitException;
import org.orekit.frames.Frame;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinatesProvider;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.PropagationService;

/**
 * Collision-avoidance maneuver planner (the inverse of {@link RendezvousCorrector}). Given a
 * predicted conjunction — a deputy passing close to a threat (the chief or another deputy) at a
 * time of closest approach (TCA) — it finds the smallest single ΔV, along a chosen deputy-RIC
 * axis, that raises the miss distance to a target while keeping the deputy near its orbit.
 *
 * <p>Where the rendezvous corrector solves a 3-component ΔV to drive the arrival miss to
 * <em>zero</em>, this solves a <b>1-D</b> problem — the ΔV <em>magnitude</em> along a fixed axis
 * (the direction encodes the user's "not too high or low": {@link Axis#CROSSTRACK} tilts the plane
 * and leaves the semi-major axis / altitude unchanged) — to drive the miss <em>up to</em> a target
 * and keep it there. The sign is chosen automatically to push away from the threat ("180° from the
 * rendezvous").
 *
 * <p>Method (mirrors the corrector's honesty posture — measure against the REAL propagators, never
 * a two-body formula):
 * <ol>
 *   <li>Baseline: fly the deputy (its existing burns only) + the threat, take the miss vector
 *       {@code d0} at TCA.</li>
 *   <li>Sensitivity {@code S = ∂p_deputy(TCA)/∂(ΔV)} by one finite-difference probe of
 *       {@link #FD_PROBE_MS} along the axis (a smaller probe is swamped by the adaptive
 *       integrator's step noise — the same reason {@code RendezvousCorrector.FD_STEP_MS} is 1 m/s).</li>
 *   <li>Quadratic seed: solve {@code |d0 + m·sign·S| = target} for the magnitude {@code m}.</li>
 *   <li>Verify + refine against the <b>windowed minimum</b> separation (re-search the closest
 *       approach over the remaining window, not the fixed original TCA — the burn shifts the TCA,
 *       and a displacement component along the relative velocity only re-times the encounter
 *       without increasing the true miss; the windowed min discounts it and also catches a
 *       <em>new</em> conjunction a drifting deputy might create). A short secant loop lands the
 *       first {@code m} whose windowed-min ≥ target.</li>
 * </ol>
 *
 * <p>Determinism (R11): no RNG, no wall-clock in the result; fixed {@link #FD_PROBE_MS}, fixed
 * {@link #GOLDEN_ITERS}/{@link #MAX_REFINE_ITERS}, a deterministic sign tie-break. The
 * {@link #RUNTIME_CAP_MS} fuse only returns the best-so-far with a note (never a silently different
 * answer). The ΔV is frozen into the scenario as one RIC impulse, so a saved scenario reruns
 * byte-identically regardless of the solver internals.
 */
@Service
@DependsOn("orekitConfig")
public class CollisionAvoidancePlanner {

    /** Avoidance burn axis in the deputy's own RIC (the frame {@code ImpulseManeuver} applies it in). */
    public enum Axis {
        /** Out-of-plane (cross-track): tilts the plane, semi-major axis / altitude unchanged. Default. */
        CROSSTRACK,
        /** Radial: oscillating up/down, no mean altitude change. */
        RADIAL,
        /** In-track: cheapest ΔV (secular drift ∝ lead time) but CHANGES the period/altitude. */
        INTRACK;

        public static Axis parse(String s) {
            String v = s == null ? "crosstrack" : s.trim().toLowerCase();
            return switch (v) {
                case "crosstrack", "cross", "c" -> CROSSTRACK;
                case "radial", "rbar", "r" -> RADIAL;
                case "intrack", "in-track", "vbar", "i" -> INTRACK;
                default -> throw new ScenarioValidationException(
                        "avoidance axis must be 'crosstrack', 'radial' or 'intrack'");
            };
        }

        /** Unit vector in the deputy's RIC (r, i, c). */
        double[] unit() {
            return switch (this) {
                case RADIAL -> new double[] {1.0, 0.0, 0.0};
                case INTRACK -> new double[] {0.0, 1.0, 0.0};
                case CROSSTRACK -> new double[] {0.0, 0.0, 1.0};
            };
        }
    }

    /** Finite-difference probe (m/s) — matches {@code RendezvousCorrector.FD_STEP_MS}: large enough
     *  to clear the adaptive integrator's step-pattern noise, small enough to stay locally linear. */
    static final double FD_PROBE_MS = 1.0;
    /** Golden-section iterations for the windowed-minimum refine (matches the stream's TCA refine). */
    static final int GOLDEN_ITERS = 60;
    /** Coarse-scan sample cap for the windowed minimum (mirrors {@code ScreeningService}). */
    static final int MAX_SAMPLES = 400;
    /** Secant iterations on the ΔV magnitude — bounded so a long-window plan stays responsive. */
    static final int MAX_REFINE_ITERS = 5;
    /** Accept a windowed-min within this fraction of the target (2%). */
    static final double MISS_TOL_FRAC = 0.02;
    /** ΔV sanity cap (m/s): a real proximity CAM is cm–m/s to a few m/s; far past this means the
     *  geometry/phase is hopeless for this axis → fall back with a note rather than store a huge burn. */
    static final double MAX_DV_MS = 500.0;
    /** Wall-clock fuse (ms) for pathological long windows — returns best-so-far + a note (not a
     *  determinism knob; a normal solve finishes in a handful of propagations well under it). */
    static final long RUNTIME_CAP_MS = 8000;
    /** Stabilize the maneuvered deputy from this many seconds BEFORE the burn, so the burn's
     *  {@code ImpulseManeuver} detector fires strictly inside the captured ephemeris (a burn exactly
     *  on the lower bound of {@code propagate(from,to)} may not fire — the deputy would fly un-burned). */
    static final double STABILIZE_PRE_MARGIN_S = 60.0;

    private final PropagationService propagationService;
    private final FrameService frames;

    public CollisionAvoidancePlanner(PropagationService propagationService, FrameService frames) {
        this.propagationService = propagationService;
        this.frames = frames;
    }

    /**
     * The plan: the single avoidance {@link Impulse} (deputy RIC, at {@code burnEpoch}) plus
     * diagnostics. {@code converged} is false (with a {@code note}) when the target miss could not
     * be reached within the ΔV cap / time budget, or the geometry is degenerate — the caller still
     * gets the best-effort burn (a partial improvement is useful and honest).
     */
    public record CamPlan(Impulse burn, double dvMagnitudeMps, Axis axis,
                          AbsoluteDate burnEpoch, AbsoluteDate tcaEpoch, AbsoluteDate achievedTcaEpoch,
                          double baselineMissM, double achievedMissM,
                          boolean converged, String note) {
    }

    /** Windowed-minimum separation over the remaining window: the closest approach and its epoch. */
    private record WindowedMin(AbsoluteDate epoch, double separationM) {
    }

    /**
     * @param deputyTle        the maneuvering deputy (flown numerically once it has a burn)
     * @param existingImpulses the deputy's already-planned maneuvers (the CAM burn is added to these)
     * @param threatProvider   the threat's state provider (chief or another deputy), at the fidelity
     *                         the stream flies — so {@code achievedMissM} matches the post-insert conjunction
     * @param nRadPerSec       the deputy's mean motion (rad/s) — sets the poor-phase sensitivity floor
     * @param tca              the predicted time of closest approach
     * @param burnEpoch        when to burn (per-axis default chosen by the caller; earlier = cheaper for in-track)
     * @param windowEnd        the scenario window end (the min is re-searched over [burnEpoch, windowEnd])
     * @param axis             the RIC avoidance axis
     * @param targetMissM      the miss distance to reach (m)
     */
    public CamPlan plan(TLE deputyTle, List<Impulse> existingImpulses,
                        PVCoordinatesProvider threatProvider, double nRadPerSec,
                        AbsoluteDate tca, AbsoluteDate burnEpoch, AbsoluteDate windowEnd,
                        Axis axis, double targetMissM) {
        long startNanos = System.nanoTime();
        Frame eci = frames.eci();
        double[] hat = axis.unit();
        List<Impulse> existing = existingImpulses == null ? List.of() : existingImpulses;

        // The threat doesn't change with the burn — stabilize it once for order-independent sampling.
        PVCoordinatesProvider threat =
                propagationService.stabilizeForRepeatedSampling(threatProvider, burnEpoch, windowEnd);

        // Baseline geometry at the original TCA (deputy with only its existing burns).
        PVCoordinatesProvider dep0 = stabilizedDeputy(deputyTle, existing, burnEpoch, windowEnd);
        if (dep0 == null) {
            return fail(axis, burnEpoch, tca, Double.NaN, targetMissM,
                    "the deputy leaves the propagator's valid domain over the window — cannot plan");
        }
        Vector3D p0 = safePos(dep0, tca, eci);
        Vector3D pThreat = safePos(threat, tca, eci);
        if (p0 == null || pThreat == null) {
            return fail(axis, burnEpoch, tca, Double.NaN, targetMissM,
                    "the deputy or threat leaves the propagator's valid domain at the TCA — cannot plan");
        }
        Vector3D d0 = p0.subtract(pThreat);
        double miss0 = d0.getNorm();
        if (miss0 >= targetMissM) {
            return new CamPlan(new Impulse(burnEpoch, 0.0, 0.0, 0.0), 0.0, axis, burnEpoch, tca, tca,
                    miss0, miss0, true, "already clears the target miss — no avoidance burn needed");
        }

        // Sensitivity S = Δp(TCA) per m/s of ΔV along the axis (ECI, via the real propagator).
        Vector3D pProbe = deputyPositionAt(deputyTle, existing, burnEpoch, windowEnd,
                scaled(hat, FD_PROBE_MS), tca, eci);
        if (pProbe == null) {
            return fail(axis, burnEpoch, tca, miss0, targetMissM,
                    "the probe burn leaves the propagator's valid domain — try a different axis/epoch");
        }
        Vector3D s = pProbe.subtract(p0).scalarMultiply(1.0 / FD_PROBE_MS);
        double sMag = s.getNorm();
        // Poor-phase guard: a near-zero sensitivity means this axis barely moves the craft at TCA
        // (e.g. a cross-track burn an integer half-orbit before TCA). Refuse rather than blow up ΔV.
        double minSensitivity = 0.05 / Math.max(nRadPerSec, 1.0e-6);
        if (sMag < minSensitivity) {
            return fail(axis, burnEpoch, tca, miss0, targetMissM, String.format(
                    "poor conjunction phase for a %s burn at this epoch (little effect at TCA) — "
                            + "try in-track, or a burn ~a quarter-orbit before the approach",
                    axis.name().toLowerCase()));
        }

        // Push along +d0 (away from the threat, "180° from rendezvous"); deterministic + tie-break.
        double dDotS = d0.dotProduct(s);
        double sign = dDotS >= 0.0 ? 1.0 : -1.0;
        // Quadratic |d0 + m·sign·S| = target → smallest positive m under the linear model.
        double a = sMag * sMag;
        double b = 2.0 * sign * dDotS;                 // ≥ 0 by the sign choice
        double c = miss0 * miss0 - targetMissM * targetMissM; // < 0
        double disc = b * b - 4.0 * a * c;
        double mSeed = disc > 0.0 ? (-b + Math.sqrt(disc)) / (2.0 * a) : MAX_DV_MS;

        // Verify + refine against the windowed minimum. Secant toward the target, tracking the BEST
        // (largest) windowed-min across every evaluation — the m=0 baseline is the floor a burn must
        // beat. Tracking the best (not the last iterate) matters because g(m) is NOT monotonic for a
        // recurring approach (a closed relative orbit / constant-separation pair): a local secant step
        // can land on a magnitude that makes the miss WORSE, and we must never return that.
        double mLo = 0.0;
        double gLo = miss0;
        double bestSep = miss0;      // m = 0 (no burn) — the floor
        double bestM = 0.0;
        AbsoluteDate bestTca = tca;
        boolean anyEval = false;
        double m = Math.min(Math.max(mSeed, 0.0), MAX_DV_MS);
        for (int it = 0; it < MAX_REFINE_ITERS; it++) {
            if (overBudget(startNanos)) {
                break;
            }
            WindowedMin g = windowedMin(deputyTle, existing, burnEpoch, windowEnd, eci, threat,
                    scaled(hat, m * sign));
            if (g == null) { // this magnitude leaves the valid domain — back off toward mLo
                double next = 0.5 * (mLo + m);
                if (next <= mLo + 1.0e-6) {
                    break;
                }
                m = next;
                continue;
            }
            anyEval = true;
            if (g.separationM() > bestSep) {
                bestSep = g.separationM();
                bestM = m;
                bestTca = g.epoch();
            }
            if (g.separationM() >= targetMissM * (1.0 - MISS_TOL_FRAC)) {
                break; // target reached — bestM is (near) the minimal ΔV
            }
            // Under target → need a larger m. Secant from (mLo,gLo)→(m,g); guard flat/degenerate slopes.
            double slope = (g.separationM() - gLo) / Math.max(m - mLo, 1.0e-9);
            double mNext = slope > 1.0e-9 ? m + (targetMissM - g.separationM()) / slope : m * 2.0;
            mLo = m;
            gLo = g.separationM();
            m = Math.min(Math.max(mNext, m * 1.1), MAX_DV_MS);
            if (m <= mLo) {
                break;
            }
        }
        // If the target wasn't reached, probe the ΔV cap to report the best this axis can achieve —
        // g(m) is non-monotonic for a recurring approach, so a local secant can miss the better
        // large-ΔV region entirely.
        if (bestSep < targetMissM * (1.0 - MISS_TOL_FRAC) && !overBudget(startNanos)) {
            WindowedMin gCap = windowedMin(deputyTle, existing, burnEpoch, windowEnd, eci, threat,
                    scaled(hat, MAX_DV_MS * sign));
            if (gCap != null) {
                anyEval = true;
                if (gCap.separationM() > bestSep) {
                    bestSep = gCap.separationM();
                    bestM = MAX_DV_MS;
                    bestTca = gCap.epoch();
                }
            }
        }

        if (!anyEval) {
            return fail(axis, burnEpoch, tca, miss0, targetMissM,
                    "could not evaluate the avoidance burn (domain exit) — try a different axis/epoch");
        }
        // Never return a burn that fails to increase the miss over the window (a recurring approach,
        // or the wrong axis for the geometry). A burn that made it worse would be actively harmful.
        if (bestM <= 0.0 || bestSep <= miss0 * (1.0 + 1.0e-6)) {
            return fail(axis, burnEpoch, tca, miss0, targetMissM, String.format(
                    "a %s burn cannot increase the miss over the window (the approach may recur — e.g. a "
                            + "closed relative orbit) — try a different axis, an earlier burn, or a "
                            + "single-pass conjunction", axis.name().toLowerCase()));
        }
        boolean reached = bestSep >= targetMissM * (1.0 - MISS_TOL_FRAC);
        double dv = bestM;
        Impulse burn = new Impulse(burnEpoch, sign * hat[0] * dv, sign * hat[1] * dv, sign * hat[2] * dv);
        String note = altitudeNote(axis);
        if (!reached) {
            String reason = String.format(
                    "target miss %.0f m not reached (best %.0f m at %.1f m/s%s) — try in-track or a longer lead time",
                    targetMissM, bestSep, dv, dv >= MAX_DV_MS ? ", the ΔV cap" : "");
            note = note == null ? reason : reason + "; " + note;
        }
        return new CamPlan(burn, dv, axis, burnEpoch, tca, bestTca, miss0, bestSep, reached, note);
    }

    // --- internals ------------------------------------------------------------

    private static String altitudeNote(Axis axis) {
        return switch (axis) {
            case INTRACK -> "in-track burn changes the period/altitude (cheapest ΔV, but not altitude-neutral)";
            case CROSSTRACK -> null; // out-of-plane: altitude unchanged (the requested behaviour)
            case RADIAL -> null;     // no mean altitude change
        };
    }

    /** Deputy provider (numerical, existing + extra RIC burn) stabilized to a bounded ephemeris over
     *  the window, or {@code null} if it leaves the valid domain (decay / sub-surface). */
    private PVCoordinatesProvider stabilizedDeputy(TLE tle, List<Impulse> existing,
                                                   AbsoluteDate from, AbsoluteDate to) {
        try {
            Propagator dep = propagationService.propagatorFor(tle, Fidelity.NUMERICAL, existing);
            return propagationService.stabilizeForRepeatedSampling(
                    dep, from.shiftedBy(-STABILIZE_PRE_MARGIN_S), to);
        } catch (OrekitException leftDomain) {
            return null;
        }
    }

    /** The deputy's ECI position at {@code at} with an extra RIC burn added, or {@code null} on domain exit. */
    private Vector3D deputyPositionAt(TLE tle, List<Impulse> existing, AbsoluteDate burnEpoch,
                                      AbsoluteDate windowEnd, double[] ric, AbsoluteDate at, Frame eci) {
        List<Impulse> imps = withBurn(existing, burnEpoch, ric);
        PVCoordinatesProvider dep = stabilizedDeputy2(tle, imps, burnEpoch, windowEnd);
        if (dep == null) {
            return null;
        }
        return safePos(dep, at, eci);
    }

    /** Sample a provider's ECI position, or {@code null} if it leaves the valid domain (decay /
     *  sub-surface) — {@link PropagationService#stabilizeForRepeatedSampling} hands back the raw
     *  (unstabilized) propagator when capture fails, so sampling it can still throw here. */
    private static Vector3D safePos(PVCoordinatesProvider provider, AbsoluteDate at, Frame eci) {
        try {
            return provider.getPVCoordinates(at, eci).getPosition();
        } catch (OrekitException leftDomain) {
            return null;
        }
    }

    /** Same as {@link #stabilizedDeputy} but with a caller-supplied impulse list (includes the CAM burn). */
    private PVCoordinatesProvider stabilizedDeputy2(TLE tle, List<Impulse> imps,
                                                    AbsoluteDate from, AbsoluteDate to) {
        try {
            Propagator dep = propagationService.propagatorFor(tle, Fidelity.NUMERICAL, imps);
            return propagationService.stabilizeForRepeatedSampling(
                    dep, from.shiftedBy(-STABILIZE_PRE_MARGIN_S), to);
        } catch (OrekitException leftDomain) {
            return null;
        }
    }

    /** Windowed-minimum separation between the maneuvered deputy and the threat over [burnEpoch, windowEnd]. */
    private WindowedMin windowedMin(TLE tle, List<Impulse> existing, AbsoluteDate burnEpoch,
                                    AbsoluteDate windowEnd, Frame eci, PVCoordinatesProvider threat,
                                    double[] ricBurn) {
        PVCoordinatesProvider dep = stabilizedDeputy2(tle, withBurn(existing, burnEpoch, ricBurn),
                burnEpoch, windowEnd);
        if (dep == null) {
            return null;
        }
        try {
            double dur = windowEnd.durationFrom(burnEpoch);
            if (!(dur > 0.0)) {
                return new WindowedMin(burnEpoch, separation(dep, threat, burnEpoch, eci));
            }
            double step = Math.max(1.0, dur / (MAX_SAMPLES - 1));
            double coarseMin = Double.POSITIVE_INFINITY;
            double coarseT = 0.0;
            for (double t = 0.0; t <= dur; t += step) {
                double d = separation(dep, threat, burnEpoch.shiftedBy(t), eci);
                if (d < coarseMin) {
                    coarseMin = d;
                    coarseT = t;
                }
            }
            double[] refined = refine(dep, threat, burnEpoch, eci,
                    Math.max(0.0, coarseT - step), Math.min(dur, coarseT + step), coarseT, coarseMin);
            return new WindowedMin(burnEpoch.shiftedBy(refined[0]), refined[1]);
        } catch (OrekitException leftDomain) {
            // A large trial burn (e.g. the ΔV-cap probe) can drop the deputy below the surface;
            // stabilize hands back the raw propagator, which throws here. Treat as "no valid min".
            return null;
        }
    }

    /** Golden-section refine of the closest approach in [lo, hi] seconds past {@code base}. */
    private double[] refine(PVCoordinatesProvider dep, PVCoordinatesProvider threat, AbsoluteDate base,
                            Frame eci, double lo, double hi, double coarseT, double coarseDist) {
        if (!(hi > lo)) {
            return new double[] {coarseT, coarseDist};
        }
        final double gr = (Math.sqrt(5.0) - 1.0) / 2.0;
        double aL = lo;
        double bH = hi;
        double c = bH - gr * (bH - aL);
        double d = aL + gr * (bH - aL);
        double fc = separation(dep, threat, base.shiftedBy(c), eci);
        double fd = separation(dep, threat, base.shiftedBy(d), eci);
        for (int it = 0; it < GOLDEN_ITERS; it++) {
            if (fc < fd) {
                bH = d; d = c; fd = fc; c = bH - gr * (bH - aL); fc = separation(dep, threat, base.shiftedBy(c), eci);
            } else {
                aL = c; c = d; fc = fd; d = aL + gr * (bH - aL); fd = separation(dep, threat, base.shiftedBy(d), eci);
            }
        }
        double tBest = 0.5 * (aL + bH);
        double fBest = separation(dep, threat, base.shiftedBy(tBest), eci);
        return fBest <= coarseDist ? new double[] {tBest, fBest} : new double[] {coarseT, coarseDist};
    }

    private static double separation(PVCoordinatesProvider a, PVCoordinatesProvider b,
                                     AbsoluteDate t, Frame eci) {
        Vector3D pa = a.getPVCoordinates(t, eci).getPosition();
        Vector3D pb = b.getPVCoordinates(t, eci).getPosition();
        return pa.subtract(pb).getNorm();
    }

    private static List<Impulse> withBurn(List<Impulse> existing, AbsoluteDate burnEpoch, double[] ric) {
        List<Impulse> imps = new ArrayList<>(existing);
        imps.add(new Impulse(burnEpoch, ric[0], ric[1], ric[2]));
        return imps;
    }

    private static double[] scaled(double[] hat, double m) {
        return new double[] {hat[0] * m, hat[1] * m, hat[2] * m};
    }

    private static boolean overBudget(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L > RUNTIME_CAP_MS;
    }

    private static CamPlan fail(Axis axis, AbsoluteDate burnEpoch, AbsoluteDate tca,
                                double miss0, double target, String note) {
        return new CamPlan(new Impulse(burnEpoch, 0.0, 0.0, 0.0), 0.0, axis, burnEpoch, tca, tca,
                miss0, miss0, false, note);
    }
}
