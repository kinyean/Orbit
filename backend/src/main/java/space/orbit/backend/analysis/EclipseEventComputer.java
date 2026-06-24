package space.orbit.backend.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.orekit.utils.Constants;

/**
 * Per-spacecraft Earth-shadow (eclipse) detection (Phase 8, US-ENV-02 / SRS §3.7.2 /
 * UC-5). Like {@link SensorEventComputer}, it works entirely on the
 * <strong>already-sampled</strong> geocentric ECI trajectory + the sampled Sun
 * position — it does <em>not</em> re-propagate, so eclipse bands are consistent by
 * construction with the rendered scene and it stays deterministic (R11).
 *
 * <p>Conical umbra + penumbra (the standard dual-cone model). For a craft at
 * geocentric ECI position {@code P} with the Sun at geocentric ECI {@code S}:
 * the shadow axis is the anti-sun direction {@code u = (−S).normalize()}; the
 * craft is in front of the Earth (sunlit) when {@code P·u ≤ 0}. Otherwise let
 * {@code s = P·u} (distance along the axis behind the Earth) and {@code d} the
 * perpendicular miss distance from the axis; with
 * {@code αU = asin((R_sun − R_e)/|S|)} (umbra cone, converging) and
 * {@code αP = asin((R_sun + R_e)/|S|)} (penumbra cone, diverging):
 * <pre>
 *   rU = R_e − s·tan(αU)      rP = R_e + s·tan(αP)
 *   d &lt; rU → umbra;  d &lt; rP → penumbra;  else → lit
 * </pre>
 * Detection uses two independent boolean predicates — {@code inPenumbra} (d &lt; rP)
 * and {@code inUmbra} (d &lt; rU) — each transition bisection-refined on the
 * interpolated samples (fixed iterations), mirroring the sensor-event detector.
 * Earth-shadow only: a satellite's lunar eclipse is negligible.
 */
public class EclipseEventComputer {

    private static final int BISECT_ITERS = 24;
    private static final double R_EARTH = Constants.WGS84_EARTH_EQUATORIAL_RADIUS;
    private static final double R_SUN = Constants.SUN_RADIUS;

    /**
     * Detect eclipse boundary crossings over {@code [0, durationSec]} (seconds
     * relative to {@code epoch}). {@code firstT}/{@code step}/{@code steps} describe
     * the (margined) sample grid shared by every craft and {@code sunEci}; only
     * crossings inside the window are emitted.
     *
     * @param crafts each craft's geocentric ECI position samples (stride 4)
     * @param sunEci the Sun's geocentric ECI position on the same grid,
     *               {@code [t, x, y, z, ...]} (stride 4, un-normalised metres)
     */
    public List<EclipseEvent> compute(List<SampledGeocentricCraft> crafts, double[] sunEci,
                                      double firstT, int step, int steps,
                                      Instant epoch, long durationSec) {
        List<EclipseEvent> events = new ArrayList<>();
        if (sunEci == null || sunEci.length < 4) {
            return events;
        }
        for (SampledGeocentricCraft craft : crafts) {
            detect(craft, sunEci, firstT, step, steps, epoch, durationSec, events);
        }
        return events;
    }

    private void detect(SampledGeocentricCraft craft, double[] sunEci, double firstT, int step, int steps,
                        Instant epoch, long durationSec, List<EclipseEvent> events) {
        boolean prevPen = false;
        boolean prevUmb = false;
        boolean havePrev = false;
        double prevT = firstT;
        double[] p = new double[3];
        double[] s = new double[3];
        for (int k = 0; k <= steps; k++) {
            double t = firstT + (double) k * step;
            sampleAt(craft.posEci(), craft.posStride(), t, p);
            sampleAt(sunEci, 4, t, s);
            boolean pen = inShadow(p, s, false);
            boolean umb = inShadow(p, s, true);
            if (havePrev) {
                // Refine penumbra (outer) and umbra (inner) boundary crossings independently;
                // on entry the penumbra crossing precedes the umbra crossing, on exit the
                // reverse — so emitting penumbra-then-umbra keeps the stream time-ordered.
                if (pen != prevPen) {
                    emit(craft, sunEci, prevT, t, false, pen, epoch, durationSec, events);
                }
                if (umb != prevUmb) {
                    emit(craft, sunEci, prevT, t, true, umb, epoch, durationSec, events);
                }
            }
            prevPen = pen;
            prevUmb = umb;
            prevT = t;
            havePrev = true;
        }
    }

    private void emit(SampledGeocentricCraft craft, double[] sunEci, double lo, double hi,
                      boolean umbra, boolean entering, Instant epoch, long durationSec,
                      List<EclipseEvent> events) {
        double cross = refine(craft, sunEci, lo, hi, umbra);
        if (cross < 0.0 || cross > durationSec) {
            return;
        }
        String type = umbra
                ? (entering ? EclipseEvent.UMBRA_INGRESS : EclipseEvent.UMBRA_EGRESS)
                : (entering ? EclipseEvent.PENUMBRA_INGRESS : EclipseEvent.PENUMBRA_EGRESS);
        events.add(new EclipseEvent(type, craft.noradId(), epoch.plusMillis(Math.round(cross * 1000.0))));
    }

    /** Bisection on a shadow boolean ({@code umbra} = inner cone) between {@code lo} and {@code hi}. */
    private double refine(SampledGeocentricCraft craft, double[] sunEci, double lo, double hi, boolean umbra) {
        double[] p = new double[3];
        double[] s = new double[3];
        sampleAt(craft.posEci(), craft.posStride(), lo, p);
        sampleAt(sunEci, 4, lo, s);
        boolean loVal = inShadow(p, s, umbra);
        for (int it = 0; it < BISECT_ITERS; it++) {
            double mid = 0.5 * (lo + hi);
            sampleAt(craft.posEci(), craft.posStride(), mid, p);
            sampleAt(sunEci, 4, mid, s);
            if (inShadow(p, s, umbra) == loVal) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /**
     * True when craft position {@code p} is inside the Earth's shadow cone given the
     * Sun position {@code sun} (both geocentric ECI metres). {@code umbra} selects the
     * inner (umbra) cone; otherwise the outer (penumbra) cone.
     */
    private static boolean inShadow(double[] p, double[] sun, boolean umbra) {
        double sNorm = Math.sqrt(sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2]);
        if (sNorm <= 0) {
            return false;
        }
        // Anti-sun axis u = −S / |S| (points from Earth into its shadow).
        double ux = -sun[0] / sNorm;
        double uy = -sun[1] / sNorm;
        double uz = -sun[2] / sNorm;
        double along = p[0] * ux + p[1] * uy + p[2] * uz; // s = P·u
        if (along <= 0) {
            return false; // craft is on the sunlit side of the Earth
        }
        // Perpendicular miss distance from the shadow axis.
        double cx = p[0] - along * ux;
        double cy = p[1] - along * uy;
        double cz = p[2] - along * uz;
        double miss = Math.sqrt(cx * cx + cy * cy + cz * cz);
        double radius;
        if (umbra) {
            double alphaU = Math.asin(Math.min(1.0, (R_SUN - R_EARTH) / sNorm));
            radius = R_EARTH - along * Math.tan(alphaU); // umbra cone converges
        } else {
            double alphaP = Math.asin(Math.min(1.0, (R_SUN + R_EARTH) / sNorm));
            radius = R_EARTH + along * Math.tan(alphaP); // penumbra cone diverges
        }
        return miss < radius;
    }

    /** Linear interpolation of {@code [x,y,z]} at {@code t} into {@code out3}; HOLD-clamp at the ends. */
    private static void sampleAt(double[] a, int stride, double t, double[] out3) {
        if (a == null || stride < 4 || a.length < stride) {
            out3[0] = out3[1] = out3[2] = 0.0;
            return;
        }
        int n = a.length / stride;
        double tFirst = a[0];
        double tLast = a[(n - 1) * stride];
        if (t <= tFirst || n == 1) {
            out3[0] = a[1];
            out3[1] = a[2];
            out3[2] = a[3];
            return;
        }
        if (t >= tLast) {
            int b = (n - 1) * stride;
            out3[0] = a[b + 1];
            out3[1] = a[b + 2];
            out3[2] = a[b + 3];
            return;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid * stride] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        int ba = lo * stride;
        int bb = hi * stride;
        double ta = a[ba];
        double tb = a[bb];
        double f = tb > ta ? (t - ta) / (tb - ta) : 0.0;
        out3[0] = a[ba + 1] + (a[bb + 1] - a[ba + 1]) * f;
        out3[1] = a[ba + 2] + (a[bb + 2] - a[ba + 2]) * f;
        out3[2] = a[ba + 3] + (a[bb + 3] - a[ba + 3]) * f;
    }
}
