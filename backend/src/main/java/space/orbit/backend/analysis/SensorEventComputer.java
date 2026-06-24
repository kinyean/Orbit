package space.orbit.backend.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.orekit.utils.Constants;
import space.orbit.backend.prop.QuaternionSamples;
import space.orbit.backend.scenario.ScenarioBody;

/**
 * Sensor acquisition / loss-of-sight detection (Phase 7, US-EVT-01 / SRS §3.12.2).
 *
 * <p>Works entirely on the <strong>already-sampled trajectory</strong> the stream
 * computed for rendering (per-craft position + body-attitude on the same time grid,
 * in the chief-LVLH scene frame) — it does <em>not</em> re-propagate. That is the
 * fix for the maneuvered-deputy bug: a maneuvered deputy is a stateful Orekit
 * numerical+{@code ImpulseManeuver} propagator, and querying it out of order (a grid
 * scan + bisection) returned a trajectory inconsistent with the sampled one, so the
 * events disagreed with the drawn FOV. Reusing the samples makes events consistent by
 * construction with both the rendered cone and the closest-approach (Decision 24), and
 * it's cheaper. Accuracy is the sample-grid resolution — the same fidelity as
 * everything drawn; the headline closest-approach stays full-resolution elsewhere.
 *
 * <p>For each sensor on each host, against every other craft, a target is
 * <em>acquired</em> when ALL hold at an instant: inside the sensor's FOV angle,
 * within {@code [minRange, maxRange]}, and the line of sight is not blocked by the
 * Earth. Evaluated on the sample grid, with a fixed-iteration bisection refine of the
 * crossing — deterministic (R11). <b>v1 simplifications:</b> the FOV test treats both
 * shapes as a circular cone (a rectangle uses its larger half-angle as a bounding
 * cone); occlusion is Earth-only (inter-spacecraft is negligible for point targets);
 * the Sun is Phase 8.
 */
public class SensorEventComputer {

    private static final int BISECT_ITERS = 24;
    private static final double EARTH_RADIUS_M = Constants.WGS84_EARTH_EQUATORIAL_RADIUS;

    /**
     * Detect acquisition/loss events over {@code [0, durationSec]} (seconds relative to
     * the scenario epoch). {@code firstT}/{@code step}/{@code steps} describe the
     * (margined) sample grid; only crossings inside the window are emitted.
     *
     * @param chiefRadiusM the chief's geocentric radius (m) — places the Earth at
     *                     {@code (−chiefRadiusM, 0, 0)} in the LVLH scene for occlusion
     */
    public List<SensorEvent> compute(List<SampledCraft> crafts, double firstT, int step, int steps,
                                     Instant epoch, long durationSec, double chiefRadiusM) {
        List<SensorEvent> events = new ArrayList<>();
        for (SampledCraft host : crafts) {
            if (host.sensors() == null || host.sensors().isEmpty()) {
                continue;
            }
            for (ScenarioBody.Sensor sensor : host.sensors()) {
                double fovCos = boundingCos(sensor);
                double[] boresightBody = boresight(sensor);
                for (SampledCraft target : crafts) {
                    if (target.noradId() == host.noradId()) {
                        continue; // a sensor doesn't observe its own host
                    }
                    detect(host, sensor, fovCos, boresightBody, target,
                            firstT, step, steps, epoch, durationSec, chiefRadiusM, events);
                }
            }
        }
        return events;
    }

    private void detect(SampledCraft host, ScenarioBody.Sensor sensor, double fovCos, double[] boresightBody,
                        SampledCraft target, double firstT, int step, int steps,
                        Instant epoch, long durationSec, double chiefRadiusM, List<SensorEvent> events) {
        boolean prev = false;
        boolean havePrev = false;
        double prevT = firstT;
        for (int k = 0; k <= steps; k++) {
            double t = firstT + (double) k * step;
            boolean vis = visible(host, sensor, fovCos, boresightBody, target, t, chiefRadiusM);
            if (havePrev && vis != prev) {
                double cross = refine(host, sensor, fovCos, boresightBody, target, prevT, t, chiefRadiusM);
                if (cross >= 0.0 && cross <= durationSec) {
                    Instant when = epoch.plusMillis(Math.round(cross * 1000.0));
                    double range = rangeAt(host, target, cross);
                    events.add(new SensorEvent(vis ? "acquisition" : "los",
                            host.noradId(), sensor.id(), target.noradId(), when, range));
                }
            }
            prev = vis;
            prevT = t;
            havePrev = true;
        }
    }

    /** Bisection on the boolean visibility transition between {@code lo} and {@code hi}. */
    private double refine(SampledCraft host, ScenarioBody.Sensor sensor, double fovCos, double[] boresightBody,
                          SampledCraft target, double lo, double hi, double chiefRadiusM) {
        boolean loVis = visible(host, sensor, fovCos, boresightBody, target, lo, chiefRadiusM);
        for (int it = 0; it < BISECT_ITERS; it++) {
            double mid = 0.5 * (lo + hi);
            if (visible(host, sensor, fovCos, boresightBody, target, mid, chiefRadiusM) == loVis) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    /** True when {@code target} is in {@code sensor}'s FOV + range and not Earth-occluded at {@code t}. */
    private boolean visible(SampledCraft host, ScenarioBody.Sensor sensor, double fovCos, double[] boresightBody,
                            SampledCraft target, double t, double chiefRadiusM) {
        double[] h = new double[3];
        double[] tg = new double[3];
        posAt(host.pos(), host.posStride(), t, h);
        posAt(target.pos(), target.posStride(), t, tg);
        double lx = tg[0] - h[0];
        double ly = tg[1] - h[1];
        double lz = tg[2] - h[2];
        double range = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (range < sensor.minRangeM() || range > sensor.maxRangeM() || range <= 0) {
            return false;
        }
        // Boresight in the LVLH scene = host body-attitude (sampled) applied to the body axis.
        double[] q = new double[4];
        QuaternionSamples.sampleAt(host.att(), t, q);
        double[] b = QuaternionSamples.rotate(q, boresightBody);
        double bn = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        double cos = bn > 0 ? (lx * b[0] + ly * b[1] + lz * b[2]) / (range * bn) : -1;
        if (cos < fovCos) {
            return false; // outside the FOV cone
        }
        return !earthBlocks(h, tg, chiefRadiusM);
    }

    private double rangeAt(SampledCraft host, SampledCraft target, double t) {
        double[] h = new double[3];
        double[] tg = new double[3];
        posAt(host.pos(), host.posStride(), t, h);
        posAt(target.pos(), target.posStride(), t, tg);
        double dx = tg[0] - h[0];
        double dy = tg[1] - h[1];
        double dz = tg[2] - h[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** cos of the bounding half-angle: cone half-angle, or the larger half-angle of a rect. */
    private static double boundingCos(ScenarioBody.Sensor sensor) {
        ScenarioBody.Fov fov = sensor.fov();
        double halfDeg;
        if (fov != null && "rect".equalsIgnoreCase(fov.type())) {
            halfDeg = Math.max(fov.hDeg(), fov.vDeg()) / 2.0;
        } else {
            halfDeg = fov != null ? fov.halfAngleDeg() : 10.0;
        }
        return Math.cos(Math.toRadians(Math.max(0.01, Math.min(89.99, halfDeg))));
    }

    /** Normalized body-frame boresight axis (defaults to +X when absent/zero). */
    private static double[] boresight(ScenarioBody.Sensor sensor) {
        double[] b = sensor.mount() != null && sensor.mount().boresightBody() != null
                && sensor.mount().boresightBody().length == 3
                ? sensor.mount().boresightBody()
                : new double[] {1, 0, 0};
        double n = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        return n > 0 ? new double[] {b[0] / n, b[1] / n, b[2] / n} : new double[] {1, 0, 0};
    }

    /** Linear interpolation of {@code [R,I,C]} at {@code t} into {@code out3}; HOLD-clamp at the ends. */
    private static void posAt(double[] s, int stride, double t, double[] out3) {
        if (s == null || stride < 4 || s.length < stride) {
            out3[0] = out3[1] = out3[2] = 0.0; // chief origin (or absent)
            return;
        }
        int n = s.length / stride;
        double tFirst = s[0];
        double tLast = s[(n - 1) * stride];
        if (t <= tFirst || n == 1) {
            out3[0] = s[1];
            out3[1] = s[2];
            out3[2] = s[3];
            return;
        }
        if (t >= tLast) {
            int b = (n - 1) * stride;
            out3[0] = s[b + 1];
            out3[1] = s[b + 2];
            out3[2] = s[b + 3];
            return;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (s[mid * stride] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        int ba = lo * stride;
        int bb = hi * stride;
        double ta = s[ba];
        double tb = s[bb];
        double f = tb > ta ? (t - ta) / (tb - ta) : 0.0;
        out3[0] = s[ba + 1] + (s[bb + 1] - s[ba + 1]) * f;
        out3[1] = s[ba + 2] + (s[bb + 2] - s[ba + 2]) * f;
        out3[2] = s[ba + 3] + (s[bb + 3] - s[ba + 3]) * f;
    }

    /** True when the Earth blocks the line segment {@code host}→{@code target} in the LVLH scene. */
    private static boolean earthBlocks(double[] host, double[] target, double chiefRadiusM) {
        if (chiefRadiusM < EARTH_RADIUS_M) {
            return false; // degenerate / absent chief radius — can't place the Earth
        }
        // Earth center in the chief-LVLH scene is at (−chiefRadiusM, 0, 0) (chief is +R out).
        double ex = -chiefRadiusM;
        double dx = target[0] - host[0];
        double dy = target[1] - host[1];
        double dz = target[2] - host[2];
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 0) {
            return false;
        }
        double ux = dx / len;
        double uy = dy / len;
        double uz = dz / len;
        // Closest approach of the segment to the Earth center.
        double s = (ex - host[0]) * ux + (0 - host[1]) * uy + (0 - host[2]) * uz;
        if (s <= 0 || s >= len) {
            return false; // closest point is outside the segment
        }
        double cx = host[0] + ux * s - ex;
        double cy = host[1] + uy * s;
        double cz = host[2] + uz * s;
        double closest = Math.sqrt(cx * cx + cy * cy + cz * cz);
        return closest < EARTH_RADIUS_M;
    }
}
