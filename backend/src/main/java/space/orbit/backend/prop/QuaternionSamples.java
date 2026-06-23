package space.orbit.backend.prop;

/**
 * Interpolation over a stride-5 quaternion sample array {@code [t,qx,qy,qz,qw, ...]}
 * (three.js {@code (x,y,z,w)} convention). Shared by the sensor-event detector
 * (over the streamed per-craft attitude) and the measured-attitude stream path
 * (over a dataset's measured quaternions). SLERP matches the frontend
 * ({@code relativeBuffer.ts}) exactly so backend events and the rendered FOV agree.
 *
 * <p>The {@code t} key's meaning is the caller's (seconds-since-epoch for the
 * streamed attitude; absolute epoch seconds for a measured series) — this class
 * only requires it ascending. Out-of-range queries HOLD-clamp to the end sample.
 * Deterministic (R11): fixed math, no RNG/wall-clock.
 */
public final class QuaternionSamples {

    /** Stride of a quaternion sample: {@code [t, qx, qy, qz, qw]}. */
    public static final int STRIDE = 5;

    private QuaternionSamples() {}

    /**
     * SLERP of the stride-5 samples {@code a} at key {@code t} into
     * {@code out4 = (x,y,z,w)}. HOLD-clamps before the first / after the last
     * sample; identity for an empty/too-short array.
     */
    public static void sampleAt(double[] a, double t, double[] out4) {
        if (a == null || a.length < STRIDE) {
            out4[0] = out4[1] = out4[2] = 0.0;
            out4[3] = 1.0;
            return;
        }
        int n = a.length / STRIDE;
        if (t <= a[0] || n == 1) {
            copyQuat(a, 0, out4);
            return;
        }
        if (t >= a[(n - 1) * STRIDE]) {
            copyQuat(a, (n - 1) * STRIDE, out4);
            return;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo + 1 < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid * STRIDE] <= t) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        int ba = lo * STRIDE;
        int bb = hi * STRIDE;
        double ta = a[ba];
        double tb = a[bb];
        double f = tb > ta ? (t - ta) / (tb - ta) : 0.0;
        slerp(a, ba, a, bb, f, out4);
    }

    private static void copyQuat(double[] a, int base, double[] out4) {
        out4[0] = a[base + 1];
        out4[1] = a[base + 2];
        out4[2] = a[base + 3];
        out4[3] = a[base + 4];
    }

    /** Spherical-linear interpolation between two stride-5 quaternion samples (matches the client). */
    private static void slerp(double[] a, int ba, double[] b, int bb, double t, double[] out4) {
        double ax = a[ba + 1];
        double ay = a[ba + 2];
        double az = a[ba + 3];
        double aw = a[ba + 4];
        double bx = b[bb + 1];
        double by = b[bb + 2];
        double bz = b[bb + 3];
        double bw = b[bb + 4];
        double cos = ax * bx + ay * by + az * bz + aw * bw;
        if (cos < 0) { // shorter arc (quaternion double-cover)
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
            cos = -cos;
        }
        double s0;
        double s1;
        if (cos > 0.9995) {
            s0 = 1 - t;
            s1 = t;
        } else {
            double theta = Math.acos(cos);
            double sin = Math.sin(theta);
            s0 = Math.sin((1 - t) * theta) / sin;
            s1 = Math.sin(t * theta) / sin;
        }
        double x = s0 * ax + s1 * bx;
        double y = s0 * ay + s1 * by;
        double z = s0 * az + s1 * bz;
        double w = s0 * aw + s1 * bw;
        double norm = Math.sqrt(x * x + y * y + z * z + w * w);
        if (norm <= 0) {
            norm = 1;
        }
        out4[0] = x / norm;
        out4[1] = y / norm;
        out4[2] = z / norm;
        out4[3] = w / norm;
    }
}
