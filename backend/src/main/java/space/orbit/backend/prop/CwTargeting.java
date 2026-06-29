package space.orbit.backend.prop;

import org.hipparchus.linear.Array2DRowRealMatrix;
import org.hipparchus.linear.LUDecomposition;
import org.hipparchus.linear.RealMatrix;

/**
 * Closed-form Clohessy–Wiltshire two-impulse targeting (Phase 9B), the shared workhorse
 * behind the close-range RPO templates (V-bar/R-bar hold, NMC, glideslope). It partitions
 * the CW state-transition matrix Φ = [[Φrr, Φrv],[Φvr, Φvv]] (the same dynamics
 * {@link CwPropagation#advance} integrates) and solves the two-point boundary value
 * problem: given the deputy's current relative state, find the departure ΔV that lands it
 * at a target relative position after a transfer time, and the arrival ΔV that matches a
 * desired arrival velocity.
 *
 * <p>Axes are the project RIC/LVLH (x = radial, y = in-track, z = cross-track), metres and
 * m/s. Deterministic (closed-form, fixed math). Φrv is singular at integer-revolution
 * transfer times (and the cross-track block at half-revolutions) — the solver reports that
 * rather than returning a garbage burn.
 */
public final class CwTargeting {

    private CwTargeting() {
    }

    /** The four 3×3 STM blocks at transfer time {@code dt} under mean motion {@code n}. */
    public record Stm(double[][] rr, double[][] rv, double[][] vr, double[][] vv) {
    }

    /** CW STM blocks (analytic — matches {@link CwPropagation#advance}). */
    public static Stm stm(double n, double dt) {
        double nt = n * dt;
        double s = Math.sin(nt);
        double c = Math.cos(nt);
        double[][] rr = {
            {4.0 - 3.0 * c, 0.0, 0.0},
            {6.0 * (s - nt), 1.0, 0.0},
            {0.0, 0.0, c},
        };
        double[][] rv = {
            {s / n, (2.0 / n) * (1.0 - c), 0.0},
            {(2.0 / n) * (c - 1.0), (1.0 / n) * (4.0 * s - 3.0 * nt), 0.0},
            {0.0, 0.0, s / n},
        };
        double[][] vr = {
            {3.0 * n * s, 0.0, 0.0},
            {6.0 * n * (c - 1.0), 0.0, 0.0},
            {0.0, 0.0, -n * s},
        };
        double[][] vv = {
            {c, 2.0 * s, 0.0},
            {-2.0 * s, 4.0 * c - 3.0, 0.0},
            {0.0, 0.0, c},
        };
        return new Stm(rr, rv, vr, vv);
    }

    /**
     * Solve the two-impulse transfer from relative state {@code (r0, v0)} to relative
     * position {@code rT} with arrival velocity {@code vT}, over {@code dt} seconds at mean
     * motion {@code n}.
     *
     * @return {@code [dv1x,dv1y,dv1z, dv2x,dv2y,dv2z]} (m/s, RIC) — the departure burn that
     *         puts the deputy on the transfer and the arrival burn that matches {@code vT};
     *         {@code null} if Φrv is singular at this transfer time (e.g. an integer-rev arrival).
     */
    public static double[] twoImpulse(double[] r0, double[] v0, double[] rT, double[] vT,
                                      double n, double dt) {
        Stm phi = stm(n, dt);
        // v0plus = Φrv⁻¹ (rT − Φrr·r0): the post-burn velocity that reaches rT at dt.
        double[] rhs = {
            rT[0] - dot(phi.rr()[0], r0),
            rT[1] - dot(phi.rr()[1], r0),
            rT[2] - dot(phi.rr()[2], r0),
        };
        double[] v0plus;
        try {
            RealMatrix rv = new Array2DRowRealMatrix(phi.rv(), true);
            v0plus = new LUDecomposition(rv, 1.0e-12).getSolver()
                    .solve(new org.hipparchus.linear.ArrayRealVector(rhs, false)).toArray();
        } catch (RuntimeException singular) {
            return null; // integer-rev (or half-rev cross-track) transfer — ill-posed
        }
        // Arrival velocity just before the second burn: vArr = Φvr·r0 + Φvv·v0plus.
        double[] vArr = {
            dot(phi.vr()[0], r0) + dot(phi.vv()[0], v0plus),
            dot(phi.vr()[1], r0) + dot(phi.vv()[1], v0plus),
            dot(phi.vr()[2], r0) + dot(phi.vv()[2], v0plus),
        };
        return new double[] {
            v0plus[0] - v0[0], v0plus[1] - v0[1], v0plus[2] - v0[2],
            vT[0] - vArr[0], vT[1] - vArr[1], vT[2] - vArr[2],
        };
    }

    private static double dot(double[] row, double[] v) {
        return row[0] * v[0] + row[1] * v[1] + row[2] * v[2];
    }
}
