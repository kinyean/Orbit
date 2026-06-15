package space.orbit.backend.prop;

import java.util.List;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.orekit.frames.Frame;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;
import org.orekit.utils.PVCoordinatesProvider;
import org.orekit.utils.TimeStampedPVCoordinates;

/**
 * Closed-form Clohessy–Wiltshire (Hill) relative motion (SRS §3.1.7, US-REL-03,
 * Phase 5C). CW is a <em>relative</em> model — it advances a deputy's state in the
 * chief's rotating LVLH frame with the 6×6 state-transition matrix, valid for small
 * separations (≲10 km) about a near-circular chief orbit.
 *
 * <p>Rather than force CW through the ECI {@code Propagator} shape, this builds a
 * {@link PVCoordinatesProvider} for the deputy: given the chief's live LVLH frame,
 * the deputy's initial relative state (seeded R15-correctly from the deputy's own
 * propagator at {@code t0}), and the chief's mean motion {@code n}, it returns the
 * deputy's PV in any requested frame at any date — so the existing precompute-once
 * sampling loop (ECEF for CZML, LVLH for the relative stream) works unchanged.
 *
 * <p>Axis convention matches the project RIC/LVLH: x = radial, y = in-track,
 * z = cross-track. Impulsive ΔV maneuvers (RIC, Phase 5B) are applied piecewise —
 * the relative velocity gains {@code (r,i,c)} at each burn epoch (CW's small-
 * separation regime makes the deputy-own and chief RIC frames effectively the same).
 */
public final class CwPropagation {

    private CwPropagation() {
    }

    /**
     * Advance a CW relative state {@code [x,y,z,vx,vy,vz]} (LVLH metres, m/s) by
     * {@code dt} seconds under mean motion {@code n} (rad/s). Pure + deterministic.
     */
    public static double[] advance(double[] s, double n, double dt) {
        double nt = n * dt;
        double sin = Math.sin(nt);
        double cos = Math.cos(nt);
        double x0 = s[0];
        double y0 = s[1];
        double z0 = s[2];
        double vx0 = s[3];
        double vy0 = s[4];
        double vz0 = s[5];

        double x = (4.0 - 3.0 * cos) * x0 + (sin / n) * vx0 + (2.0 / n) * (1.0 - cos) * vy0;
        double y = 6.0 * (sin - nt) * x0 + y0 + (2.0 / n) * (cos - 1.0) * vx0
                + (1.0 / n) * (4.0 * sin - 3.0 * nt) * vy0;
        double z = cos * z0 + (sin / n) * vz0;

        double vx = 3.0 * n * sin * x0 + cos * vx0 + 2.0 * sin * vy0;
        double vy = 6.0 * n * (cos - 1.0) * x0 - 2.0 * sin * vx0 + (4.0 * cos - 3.0) * vy0;
        double vz = -n * sin * z0 + cos * vz0;

        return new double[] {x, y, z, vx, vy, vz};
    }

    /**
     * Build a deputy {@link PVCoordinatesProvider} from CW dynamics.
     *
     * @param lvlh     the chief's live LVLH frame ({@code FrameService.lvlh(chiefProp)})
     * @param t0       the epoch the relative state is seeded at (scenario start)
     * @param rel0     deputy state relative to the chief at {@code t0}, in {@code lvlh}
     * @param n        chief mean motion, rad/s
     * @param impulses RIC ΔV maneuvers (applied piecewise to the relative velocity)
     */
    public static PVCoordinatesProvider deputyProvider(Frame lvlh, AbsoluteDate t0,
                                                       PVCoordinates rel0, double n,
                                                       List<Impulse> impulses) {
        double[] seed = {
            rel0.getPosition().getX(), rel0.getPosition().getY(), rel0.getPosition().getZ(),
            rel0.getVelocity().getX(), rel0.getVelocity().getY(), rel0.getVelocity().getZ(),
        };
        // Sort impulses by epoch so piecewise advance is order-stable (R11).
        List<Impulse> burns = impulses == null ? List.of()
                : impulses.stream().sorted((a, b) -> a.epoch().compareTo(b.epoch())).toList();

        return (date, frame) -> {
            double tTarget = date.durationFrom(t0);
            double[] state = seed.clone();
            double segStart = 0.0;
            for (Impulse burn : burns) {
                double te = burn.epoch().durationFrom(t0);
                if (te > tTarget) {
                    break; // burn is in the future relative to the requested instant
                }
                state = advance(state, n, te - segStart);
                state[3] += burn.r();
                state[4] += burn.i();
                state[5] += burn.c();
                segStart = te;
            }
            state = advance(state, n, tTarget - segStart);

            TimeStampedPVCoordinates relPv = new TimeStampedPVCoordinates(date,
                    new Vector3D(state[0], state[1], state[2]),
                    new Vector3D(state[3], state[4], state[5]));
            return lvlh.getTransformTo(frame, date).transformPVCoordinates(relPv);
        };
    }
}
