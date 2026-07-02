package space.orbit.backend.scenario;

import java.util.List;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.ArrayRealVector;
import org.hipparchus.linear.LUDecomposition;
import org.hipparchus.linear.RealMatrix;
import org.hipparchus.linear.RealVector;
import org.orekit.errors.OrekitException;
import org.orekit.frames.Frame;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.AbsolutePVCoordinates;
import org.orekit.utils.PVCoordinates;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import space.orbit.backend.prop.Fidelity;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.Impulse;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.StateVector;

/**
 * Differential corrector for the two-impulse rendezvous (Phase 9A, US-MAN-03; closes
 * risk R16). The {@link ManeuverTemplateService} Lambert solution is an open-loop
 * <em>two-body</em> sketch, but the scenario <em>executes</em> the transfer with the
 * high-fidelity numerical propagator (the maneuvered deputy) against an SGP4/numerical
 * chief — so the deputy misses the chief by the model difference (tens of km even in
 * the ideal co-orbital case). This corrector closes the loop: it iterates the
 * <b>departure</b> ΔV against the <em>same</em> propagators the scenario flies until
 * the deputy actually arrives at the chief, then derives the matching <b>arrival</b>
 * ΔV from the residual relative velocity. The converged ΔV are frozen into the
 * scenario, so reruns stay byte-identical (R11) — the solver runs once, here.
 *
 * <p>Method: Newton on the arrival position miss {@code f(x) = p_deputy(arrive ; first
 * impulse = x) − p_chief(arrive)} in ECI, with the free variable {@code x} the
 * departure ΔV in the deputy's RIC (the frame {@code ImpulseManeuver} re-applies it in
 * — no round-trip, R15). The Jacobian {@code ∂f/∂x} is a 3×3 built by finite
 * differences (perturb each RIC axis), solved via Hipparchus {@link QRDecomposition}.
 * The arrival impulse is the residual velocity match — not a free variable — so the
 * solve stays 3×3, not 6×6.
 *
 * <p>Determinism (R11): fixed {@link #MAX_ITERS}, fixed {@link #FD_STEP_MS}, a
 * deterministic linear solve, and pinned propagation settings → the converged result
 * is reproducible. {@link #RUNTIME_CAP_MS} is a pathological-input safety fuse only
 * (it returns the seed + a warning, never a silently-different converged answer).
 */
@Service
@DependsOn("orekitConfig")
public class RendezvousCorrector {

    /** Fixed iteration cap — the determinism bound (R11) and a runtime guard. */
    static final int MAX_ITERS = 12;
    /** Arrival position miss tolerance: "the deputy reaches the chief" (metres). */
    static final double CONVERGE_MISS_M = 1.0;
    /** Finite-difference ΔV perturbation per RIC axis (m/s) — large enough that the
     *  arrival-position signal dominates the adaptive integrator's step-pattern noise
     *  (a tiny step gives a noise-swamped, non-descent Jacobian), small enough to stay
     *  locally linear over a transfer arc. */
    static final double FD_STEP_MS = 1.0;
    /** Backtracking-line-search halvings per Newton step (robustness near the
     *  integer-revolution Lambert singularity, where a full step overshoots). */
    static final int MAX_BACKTRACK = 6;
    /** Levenberg–Marquardt relative damping (fraction of the JᵀJ trace added to the
     *  diagonal) — keeps the normal matrix SPD for ill-conditioned transfers. */
    static final double LM_DAMPING = 1.0e-4;
    /** Reject a corrected total ΔV beyond this — far past a real burn (mirrors the
     *  panel's >5 km/s guard; orbital speed at LEO ≈ 7.5 km/s). */
    static final double MAX_TOTAL_DV_MS = 5000.0;
    /** Skip correction (return the open-loop seed immediately) when the seed ΔV is already
     *  this large. The corrector exists to close the <em>model-mismatch</em> gap (tens of km
     *  → &lt;1 m) on a <em>feasible</em> transfer; a seed this big means the geometry is
     *  ΔV-dominated (a cross-plane rendezvous / wrong regime), where correction can't
     *  converge anyway and only burns ~16 s of numerical propagation before falling back.
     *  Catching it here makes the request return in milliseconds with a clear note instead.
     *  Well below {@link #MAX_TOTAL_DV_MS} (the post-convergence cap) — a real proximity
     *  rendezvous is tens-to-hundreds of m/s, not kilometres. */
    static final double MAX_SEED_DV_MS = 2000.0;
    /** Wall-clock safety fuse for pathological inputs (long arcs). NOT a determinism
     *  knob — a normal co-orbital solve converges in a few iterations well under it.
     *  Checked at the top of each Newton iteration AND inside the backtracking line search,
     *  so a single iteration's ≤9 numerical propagations can't run the cap 2× over. */
    static final long RUNTIME_CAP_MS = 8000;

    private final PropagationService propagationService;
    private final FrameService frames;

    public RendezvousCorrector(PropagationService propagationService, FrameService frames) {
        this.propagationService = propagationService;
        this.frames = frames;
    }

    /** The outcome of a correction: the frozen impulses (corrected, or the seed on
     *  fall-back) plus diagnostics threaded into the audit summary. */
    public record Correction(Impulse depart, Impulse arrive, double missM,
                             int iterations, boolean converged, String note) {
    }

    /**
     * Correct a two-impulse transfer against the real propagators.
     *
     * @param deputyTle   the maneuvering deputy (always flown numerically once it has a burn)
     * @param chiefProp   the rendezvous target's state provider (the LVLH origin), built at
     *                    the scenario's chief fidelity — or a measured tabulated ephemeris
     * @param departEpoch the first burn epoch (scenario start)
     * @param arriveEpoch the rendezvous epoch
     * @param seedDepart  the two-body Lambert departure ΔV (deputy RIC) — the Newton seed
     * @param seedArrive  the two-body Lambert arrival ΔV — returned unchanged on fall-back
     */
    public Correction correct(TLE deputyTle, Propagator chiefProp,
                              AbsoluteDate departEpoch, AbsoluteDate arriveEpoch,
                              Impulse seedDepart, Impulse seedArrive) {
        // Fast refusal for a ΔV-dominated (e.g. cross-plane) geometry: the open-loop seed is
        // already huge, so correcting it can't converge and would just grind through the full
        // iteration budget before falling back. Return the seed now with a clear note (R16:
        // still honest — it's the open-loop transfer, flagged as not a feasible rendezvous).
        double seedTotalDv = norm(seedDepart.r(), seedDepart.i(), seedDepart.c())
                + norm(seedArrive.r(), seedArrive.i(), seedArrive.c());
        if (seedTotalDv > MAX_SEED_DV_MS) {
            return fallback(seedDepart, seedArrive, Double.POSITIVE_INFINITY, 0, String.format(
                    "rendezvous is ΔV-dominated (open-loop seed %.0f m/s — e.g. a cross-plane "
                            + "transfer); differential correction skipped, using the open-loop "
                            + "two-body transfer (not a feasible proximity rendezvous)", seedTotalDv));
        }

        StateVector chiefArr = propagationService.sample(chiefProp, arriveEpoch);
        Vector3D chiefPos = chiefArr.position();
        Vector3D chiefVel = chiefArr.velocity();

        double[] x = {seedDepart.r(), seedDepart.i(), seedDepart.c()};
        Vector3D miss = arrivalMiss(deputyTle, departEpoch, arriveEpoch, x, chiefPos);
        if (miss == null) {
            return fallback(seedDepart, seedArrive, Double.POSITIVE_INFINITY, 0,
                    "the open-loop transfer leaves the propagator's valid domain (e.g. below the "
                            + "surface) — using it unchanged (rendezvous is approximate)");
        }
        double seedMissNorm = miss.getNorm();

        long startNanos = System.nanoTime();
        int iter = 0;
        while (miss.getNorm() >= CONVERGE_MISS_M && iter < MAX_ITERS) {
            if (overBudget(startNanos)) {
                return fallback(seedDepart, seedArrive, seedMissNorm, iter,
                        "differential corrector exceeded its time budget — using the open-loop "
                                + "two-body transfer (rendezvous is approximate)");
            }
            RealMatrix j = jacobian(deputyTle, departEpoch, arriveEpoch, x, miss, chiefPos);
            if (j == null) {
                return fallback(seedDepart, seedArrive, seedMissNorm, iter,
                        "differential corrector left the propagator's valid domain — using the open-loop "
                                + "two-body transfer (rendezvous is approximate)");
            }
            // Damped Gauss-Newton (Levenberg–Marquardt) step: solve (JᵀJ + λI) dx = −Jᵀ·miss.
            // The Jacobian is ill-conditioned for radial-heavy / near-integer-rev transfers
            // (columns go near-parallel); the λ regularization keeps the normal matrix SPD so
            // the step is always well-defined, while backtracking guarantees descent. λ scales
            // with the matrix (relative damping) so it adapts to the transfer's sensitivity.
            RealMatrix jtj = j.transpose().multiply(j);
            double trace = jtj.getEntry(0, 0) + jtj.getEntry(1, 1) + jtj.getEntry(2, 2);
            double lambda = LM_DAMPING * Math.max(trace, 1.0);
            for (int d = 0; d < 3; d++) {
                jtj.addToEntry(d, d, lambda);
            }
            RealVector negJtMiss = j.transpose().operate(
                    new ArrayRealVector(new double[] {-miss.getX(), -miss.getY(), -miss.getZ()}, false));
            RealVector dx;
            try {
                dx = new LUDecomposition(jtj).getSolver().solve(negJtMiss);
            } catch (RuntimeException singular) {
                return fallback(seedDepart, seedArrive, seedMissNorm, iter,
                        "differential corrector hit a singular Jacobian — using the open-loop "
                                + "two-body transfer (rendezvous is approximate)");
            }
            // Backtracking line search: accept the largest halving step that reduces the
            // miss. The Jacobian is ill-conditioned near integer-revolution transfers, so a
            // full Newton step can overshoot; backtracking keeps it monotone and robust
            // (fixed iteration counts → still deterministic, R11).
            double stepScale = 1.0;
            double[] trialX = x;
            Vector3D trialMiss = miss;
            boolean improved = false;
            for (int bt = 0; bt < MAX_BACKTRACK; bt++) {
                // The fuse must cover the line search too: a single iteration runs up to
                // MAX_BACKTRACK numerical propagations, so checking only at the top of the
                // Newton loop let a run overrun the cap ~2× (≈16 s observed).
                if (overBudget(startNanos)) {
                    return fallback(seedDepart, seedArrive, seedMissNorm, iter,
                            "differential corrector exceeded its time budget — using the open-loop "
                                    + "two-body transfer (rendezvous is approximate)");
                }
                double[] candidate = {x[0] + stepScale * dx.getEntry(0),
                        x[1] + stepScale * dx.getEntry(1), x[2] + stepScale * dx.getEntry(2)};
                Vector3D candidateMiss = arrivalMiss(deputyTle, departEpoch, arriveEpoch, candidate, chiefPos);
                if (candidateMiss != null && candidateMiss.getNorm() < miss.getNorm()) {
                    trialX = candidate;
                    trialMiss = candidateMiss;
                    improved = true;
                    break;
                }
                stepScale *= 0.5;
            }
            if (!improved) {
                break; // stalled — the convergence check below decides success vs fall-back
            }
            x = trialX;
            miss = trialMiss;
            iter++;
        }

        if (miss.getNorm() >= CONVERGE_MISS_M) {
            return fallback(seedDepart, seedArrive, seedMissNorm, iter, String.format(
                    "differential corrector did not converge in %d iterations (best miss %.0f m)"
                            + " — using the open-loop two-body transfer (rendezvous is approximate)",
                    MAX_ITERS, miss.getNorm()));
        }

        // Converged: the arrival burn is the residual relative velocity at arrival,
        // sampled from the same (converged-departure) numerical transfer.
        Propagator depProp = propagationService.propagatorFor(deputyTle, Fidelity.NUMERICAL,
                List.of(new Impulse(departEpoch, x[0], x[1], x[2])));
        StateVector depArr = propagationService.sample(depProp, arriveEpoch);
        Vector3D dvArriveEci = chiefVel.subtract(depArr.velocity());
        double[] arriveRic = toRic(dvArriveEci, depArr.position(), depArr.velocity(), arriveEpoch);

        double total = norm(x[0], x[1], x[2]) + norm(arriveRic[0], arriveRic[1], arriveRic[2]);
        if (total > MAX_TOTAL_DV_MS) {
            return fallback(seedDepart, seedArrive, seedMissNorm, iter, String.format(
                    "corrected ΔV %.0f m/s exceeds the %.0f m/s sanity cap — using the open-loop "
                            + "two-body transfer (rendezvous is approximate)", total, MAX_TOTAL_DV_MS));
        }

        return new Correction(
                new Impulse(departEpoch, x[0], x[1], x[2]),
                new Impulse(arriveEpoch, arriveRic[0], arriveRic[1], arriveRic[2]),
                miss.getNorm(), iter, true, null);
    }

    // --- internals ------------------------------------------------------------

    /** Arrival position miss in ECI for a trial departure ΔV {@code x} (deputy RIC), or
     *  {@code null} if that ΔV drives the deputy out of the propagator's valid domain
     *  (e.g. a garbage seed sending it below the surface — Orekit throws). */
    private Vector3D arrivalMiss(TLE deputyTle, AbsoluteDate departEpoch, AbsoluteDate arriveEpoch,
                                 double[] x, Vector3D chiefPos) {
        try {
            Propagator dep = propagationService.propagatorFor(deputyTle, Fidelity.NUMERICAL,
                    List.of(new Impulse(departEpoch, x[0], x[1], x[2])));
            return propagationService.sample(dep, arriveEpoch).position().subtract(chiefPos);
        } catch (OrekitException leftDomain) {
            return null;
        }
    }

    /**
     * Finite-difference 3×3 Jacobian {@code ∂(arrival miss)/∂(departure ΔV)}. Each
     * column perturbs one RIC axis by {@link #FD_STEP_MS}; the shared {@code chiefPos}
     * cancels in the difference, so the column is purely the deputy's arrival-position
     * sensitivity to that ΔV component. Returns {@code null} if any perturbed trajectory
     * leaves the valid domain (the caller then falls back to the seed).
     */
    private RealMatrix jacobian(TLE deputyTle, AbsoluteDate departEpoch, AbsoluteDate arriveEpoch,
                                double[] x, Vector3D miss0, Vector3D chiefPos) {
        double[][] j = new double[3][3];
        for (int k = 0; k < 3; k++) {
            double[] xp = x.clone();
            xp[k] += FD_STEP_MS;
            Vector3D fk = arrivalMiss(deputyTle, departEpoch, arriveEpoch, xp, chiefPos);
            if (fk == null) {
                return null;
            }
            j[0][k] = (fk.getX() - miss0.getX()) / FD_STEP_MS;
            j[1][k] = (fk.getY() - miss0.getY()) / FD_STEP_MS;
            j[2][k] = (fk.getZ() - miss0.getZ()) / FD_STEP_MS;
        }
        return new Array2DRowRealMatrix(j, false);
    }

    /** Wall-clock fuse check (see {@link #RUNTIME_CAP_MS}) — evaluated around every batch
     *  of numerical propagations so the cap is an actual bound, not a per-iteration check. */
    private static boolean overBudget(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L > RUNTIME_CAP_MS;
    }

    private static Correction fallback(Impulse seedDepart, Impulse seedArrive,
                                       double seedMissNorm, int iter, String note) {
        return new Correction(seedDepart, seedArrive, seedMissNorm, iter, false, note);
    }

    private static double norm(double a, double b, double c) {
        return Math.sqrt(a * a + b * b + c * c);
    }

    /**
     * Project an ECI ΔV onto a burn state's RIC axes via the canonical
     * {@link FrameService#ric} (Decision 12 / R15) — rotation only ({@code transformVector}),
     * a ΔV is a free vector. Mirrors {@link ManeuverTemplateService}'s projection.
     */
    private double[] toRic(Vector3D dvEci, Vector3D r, Vector3D v, AbsoluteDate date) {
        Frame eci = frames.eci();
        Frame ric = frames.ric(new AbsolutePVCoordinates(eci, date, new PVCoordinates(r, v)));
        Vector3D out = eci.getTransformTo(ric, date).transformVector(dvEci);
        return new double[] {out.getX(), out.getY(), out.getZ()};
    }
}
