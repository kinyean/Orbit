package space.orbit.backend.prop;

/**
 * Converts a WOD measured-attitude quaternion ({@code SW_TM_ADCS_EST_ATTD_Q1..Q4})
 * to the project's streaming convention: a <b>body→ECI</b> quaternion in three.js
 * {@code (x,y,z,w)} order — the exact thing {@code FrameService.bodyQuaternionInLvlh}'s
 * {@code "fixed"} branch consumes as {@code fixedQuatXyzw} (it rotates the body axes
 * into ECI, then through the per-step ECI→LVLH transform into the scene basis).
 *
 * <p><b>Resolved convention (measured-data slice 2, R20).</b> The convention was
 * established empirically from the TELEOS-2 telemetry (no vendor spec was available):
 * <ul>
 *   <li>The quaternion is a <b>unit</b> quaternion and is <b>ECI-referenced</b> — the
 *       recurring "home" value is held at many different orbit positions, i.e. a fixed
 *       <em>inertial</em> attitude (not LVLH-relative).
 *   <li>{@code EST_ATTD} equals the star-tracker {@code STS_BF} channel exactly
 *       (same quaternion, one convention).
 *   <li><b>Scalar-last</b> ({@code Q4} is the real/scalar part) and the raw quaternion
 *       is <b>body→ECI</b>: this parsing was favored by both the pointing-geometry
 *       analysis and the only positive correlation in the gyro-rate
 *       ({@code FOG_RATE_BF}) angular-velocity cross-check.
 * </ul>
 * Because three.js {@code (x,y,z,w)} is also scalar-last and the raw quaternion is
 * already body→ECI, the mapping is the identity reorder
 * {@code (x,y,z,w) = (Q1,Q2,Q3,Q4)}. EME2000 ≈ the ADCS inertial frame (J2000/GCRF
 * differ by ~mas — negligible at visualization fidelity).
 *
 * <p><b>If the dev-stack visual shows the craft mirrored or inverted</b>, flip exactly
 * one knob below: {@link #SCALAR_LAST} (scalar position) or {@link #CONJUGATE}
 * (rotation direction). The signed-axis pin test guards the math, not the physical
 * convention — that is confirmed visually (the data could not disambiguate it fully).
 */
public final class MeasuredAttitude {

    /** {@code true}: {@code Q4} is the scalar/real part (scalar-last). */
    private static final boolean SCALAR_LAST = true;
    /** {@code true}: the raw quaternion is ECI→body and must be conjugated to body→ECI. */
    private static final boolean CONJUGATE = false;

    private MeasuredAttitude() {}

    /**
     * Map raw {@code (q1,q2,q3,q4)} to a body→ECI three.js {@code (x,y,z,w)} quaternion,
     * normalized. See the class doc for the resolved convention.
     */
    public static double[] wodEstAttdToBodyEciXyzw(double q1, double q2, double q3, double q4) {
        double x;
        double y;
        double z;
        double w;
        if (SCALAR_LAST) {
            x = q1;
            y = q2;
            z = q3;
            w = q4;
        } else { // scalar-first: Q1 is the real part
            w = q1;
            x = q2;
            y = q3;
            z = q4;
        }
        if (CONJUGATE) { // invert the rotation direction (ECI→body → body→ECI)
            x = -x;
            y = -y;
            z = -z;
        }
        double n = Math.sqrt(x * x + y * y + z * z + w * w);
        if (n <= 0) {
            return new double[] {0, 0, 0, 1};
        }
        return new double[] {x / n, y / n, z / n, w / n};
    }
}
