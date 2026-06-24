package space.orbit.backend.analysis;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import space.orbit.backend.prop.QuaternionSamples;
import space.orbit.backend.scenario.ScenarioBody;

/**
 * Constraint-violation detection (Phase 8, US-EVT-03 / SRS §3.12.3 / UC-4). Runs on
 * the already-sampled LVLH trajectory + attitude + the sampled Sun direction (no
 * re-propagation, deterministic — R11), emitting a violation-start / violation-end
 * pair per crossing (bisection-refined), mirroring {@link SensorEventComputer}.
 *
 * <p>v1 supports two kinds:
 * <ul>
 *   <li><b>sun-keep-out</b> — the angle between a host sensor's boresight (rotated into
 *       the LVLH scene by the host attitude) and the Sun direction drops below
 *       {@code limitDeg}. Completes UC-4 step 7 (and on a measured chief it evaluates
 *       against the craft's real telemetry attitude).</li>
 *   <li><b>approach-corridor</b> — the target craft is within {@code rangeM} of the host
 *       yet its bearing from the host's corridor axis (the host body {@code +Y} / ram,
 *       rotated into the scene) exceeds {@code limitDeg} (the host's
 *       {@link SensorEventComputer} bearing test, inverted: violation when
 *       <em>outside</em> the cone).</li>
 * </ul>
 */
public class ConstraintChecker {

    private static final int BISECT_ITERS = 24;
    /** Corridor axis in the host body frame (v1): +Y = ram / velocity (the classic V-bar corridor). */
    private static final double[] CORRIDOR_AXIS_BODY = {0, 1, 0};

    /**
     * @param crafts      every craft (chief {@code pos == null} → LVLH origin), carrying attitude + sensors
     * @param sunVector   Sun unit-direction samples in the LVLH scene, {@code [t,x,y,z, ...]} (stride 4)
     * @param constraints the scenario's constraints (gathered across all roles)
     */
    public List<ConstraintViolationEvent> compute(List<SampledCraft> crafts, double[] sunVector,
                                                  List<ScenarioBody.Constraint> constraints,
                                                  double firstT, int step, int steps,
                                                  Instant epoch, long durationSec) {
        List<ConstraintViolationEvent> out = new ArrayList<>();
        if (constraints == null || constraints.isEmpty()) {
            return out;
        }
        for (ScenarioBody.Constraint c : constraints) {
            Check check = resolve(c, crafts, sunVector);
            if (check != null) {
                detect(check, firstT, step, steps, epoch, durationSec, out);
            }
        }
        return out;
    }

    /** Per-constraint evaluation context (resolved once; null when the host/sensor/target is missing). */
    private record Check(ScenarioBody.Constraint c, SampledCraft host, double[] boresightBody,
                         SampledCraft target, double[] sunVector, boolean corridor) {
    }

    private Check resolve(ScenarioBody.Constraint c, List<SampledCraft> crafts, double[] sunVector) {
        SampledCraft host = find(crafts, c.hostNoradId());
        if (host == null) {
            return null;
        }
        if ("approach-corridor".equalsIgnoreCase(c.kind())) {
            SampledCraft target = find(crafts, c.targetNoradId());
            if (target == null || target == host) {
                return null;
            }
            return new Check(c, host, CORRIDOR_AXIS_BODY, target, sunVector, true);
        }
        // sun-keep-out: the boresight comes from the named sensor on the host.
        double[] boresight = boresightOf(host, c.sensorId());
        if (boresight == null || sunVector == null) {
            return null;
        }
        return new Check(c, host, boresight, null, sunVector, false);
    }

    private void detect(Check k, double firstT, int step, int steps, Instant epoch, long durationSec,
                        List<ConstraintViolationEvent> out) {
        boolean prev = false;
        boolean havePrev = false;
        double prevT = firstT;
        for (int i = 0; i <= steps; i++) {
            double t = firstT + (double) i * step;
            boolean viol = violated(k, t);
            if (havePrev && viol != prev) {
                double cross = refine(k, prevT, t);
                if (cross >= 0.0 && cross <= durationSec) {
                    out.add(event(k, viol, cross, epoch));
                }
            }
            prev = viol;
            prevT = t;
            havePrev = true;
        }
    }

    private double refine(Check k, double lo, double hi) {
        boolean loViol = violated(k, lo);
        for (int it = 0; it < BISECT_ITERS; it++) {
            double mid = 0.5 * (lo + hi);
            if (violated(k, mid) == loViol) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    private ConstraintViolationEvent event(Check k, boolean entering, double t, Instant epoch) {
        return new ConstraintViolationEvent(
                entering ? ConstraintViolationEvent.START : ConstraintViolationEvent.END,
                k.c().id(), k.c().kind(), k.host().noradId(),
                k.corridor() ? null : k.c().sensorId(),
                k.corridor() ? k.c().targetNoradId() : 0,
                epoch.plusMillis(Math.round(t * 1000.0)),
                angleDeg(k, t), k.c().limitDeg());
    }

    /** True when constraint {@code k} is violated at time {@code t}. */
    private boolean violated(Check k, double t) {
        double[] axis = axisLvlh(k, t);
        double an = norm(axis);
        if (an <= 0) {
            return false;
        }
        if (k.corridor()) {
            double[] dir = hostToTarget(k, t);
            double range = norm(dir);
            if (range <= 0 || range > k.c().rangeM()) {
                return false; // corridor only applies within rangeM of the host
            }
            double cos = dot(dir, axis) / (range * an);
            // Violation = outside the corridor cone (bearing exceeds the half-angle).
            return cos < Math.cos(Math.toRadians(k.c().limitDeg()));
        }
        // sun-keep-out: Sun direction vs the boresight.
        double[] sun = sunDirAt(k.sunVector(), t);
        double sn = norm(sun);
        if (sn <= 0) {
            return false;
        }
        double cos = dot(sun, axis) / (sn * an);
        return cos > Math.cos(Math.toRadians(k.c().limitDeg())); // angle below the keep-out limit
    }

    /** The measured angle (deg) at {@code t} (Sun↔boresight, or target-bearing↔corridor axis). */
    private double angleDeg(Check k, double t) {
        double[] axis = axisLvlh(k, t);
        double an = norm(axis);
        double[] other = k.corridor() ? hostToTarget(k, t) : sunDirAt(k.sunVector(), t);
        double on = norm(other);
        if (an <= 0 || on <= 0) {
            return Double.NaN;
        }
        double cos = Math.max(-1.0, Math.min(1.0, dot(other, axis) / (an * on)));
        return Math.toDegrees(Math.acos(cos));
    }

    /** The host's corridor/boresight axis rotated into the LVLH scene at {@code t}. */
    private static double[] axisLvlh(Check k, double t) {
        double[] q = new double[4];
        QuaternionSamples.sampleAt(k.host().att(), t, q);
        return QuaternionSamples.rotate(q, k.boresightBody());
    }

    /** host→target vector in the LVLH scene at {@code t}. */
    private static double[] hostToTarget(Check k, double t) {
        double[] h = new double[3];
        double[] tg = new double[3];
        posAt(k.host().pos(), k.host().posStride(), t, h);
        posAt(k.target().pos(), k.target().posStride(), t, tg);
        return new double[] {tg[0] - h[0], tg[1] - h[1], tg[2] - h[2]};
    }

    private static SampledCraft find(List<SampledCraft> crafts, int noradId) {
        for (SampledCraft c : crafts) {
            if (c.noradId() == noradId) {
                return c;
            }
        }
        return null;
    }

    /** Normalized body boresight of the named sensor on {@code host}, or null if absent. */
    private static double[] boresightOf(SampledCraft host, String sensorId) {
        if (host.sensors() == null || sensorId == null) {
            return null;
        }
        for (ScenarioBody.Sensor s : host.sensors()) {
            if (sensorId.equals(s.id())) {
                double[] b = s.mount() != null && s.mount().boresightBody() != null
                        && s.mount().boresightBody().length == 3
                        ? s.mount().boresightBody()
                        : new double[] {1, 0, 0};
                double n = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
                return n > 0 ? new double[] {b[0] / n, b[1] / n, b[2] / n} : new double[] {1, 0, 0};
            }
        }
        return null;
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** Linear interpolation of a stride-4 {@code [t,x,y,z]} Sun-direction array at {@code t}. */
    private static double[] sunDirAt(double[] sun, double t) {
        double[] out = new double[3];
        posAt(sun, 4, t, out);
        return out;
    }

    /** Linear interpolation of {@code [R,I,C]} at {@code t}; null/short → origin. */
    private static void posAt(double[] s, int stride, double t, double[] out3) {
        if (s == null || stride < 4 || s.length < stride) {
            out3[0] = out3[1] = out3[2] = 0.0;
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
            int base = (n - 1) * stride;
            out3[0] = s[base + 1];
            out3[1] = s[base + 2];
            out3[2] = s[base + 3];
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
}
