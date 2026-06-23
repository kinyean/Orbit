package space.orbit.backend.prop;

import static org.assertj.core.api.Assertions.assertThat;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.Test;

/**
 * Pins the WOD {@code EST_ATTD} → body→ECI three.js {@code (x,y,z,w)} conversion
 * (measured-data slice 2, R20). This guards the <em>math</em> of the resolved
 * convention (scalar-last, body→ECI ⇒ identity reorder) by a signed-axis check —
 * the same discipline as {@code FrameRelativeTests.basisQuaternionMatchesThreeJsConvention}.
 * The physical convention itself was resolved empirically + confirmed visually
 * (see {@link MeasuredAttitude}); this test fails loudly if the reorder/sign drifts.
 */
class MeasuredAttitudeTest {

    /** Rotate v by a (x,y,z,w) quaternion — Hamilton / three.js convention. */
    private static Vector3D applyQuat(double[] q, Vector3D v) {
        double x = q[0];
        double y = q[1];
        double z = q[2];
        double w = q[3];
        double tx = 2.0 * (y * v.getZ() - z * v.getY());
        double ty = 2.0 * (z * v.getX() - x * v.getZ());
        double tz = 2.0 * (x * v.getY() - y * v.getX());
        return new Vector3D(
                v.getX() + w * tx + (y * tz - z * ty),
                v.getY() + w * ty + (z * tx - x * tz),
                v.getZ() + w * tz + (x * ty - y * tx));
    }

    @Test
    void identityQuaternionLeavesBodyAxesUnrotated() {
        // Q4 is the scalar part: (0,0,0,1) is the identity rotation.
        double[] q = MeasuredAttitude.wodEstAttdToBodyEciXyzw(0, 0, 0, 1);
        assertThat(applyQuat(q, Vector3D.PLUS_I).distance(Vector3D.PLUS_I)).isLessThan(1e-9);
        assertThat(applyQuat(q, Vector3D.PLUS_J).distance(Vector3D.PLUS_J)).isLessThan(1e-9);
        assertThat(applyQuat(q, Vector3D.PLUS_K).distance(Vector3D.PLUS_K)).isLessThan(1e-9);
    }

    @Test
    void quaternionRotatesBodyAxesByTheModeledRotation() {
        // A +90° rotation about Z as a body→ECI quaternion is (x,y,z,w)=(0,0,sin45,cos45);
        // in WOD scalar-last order that's Q1..Q4 = (0,0,0.70710678,0.70710678).
        double s = Math.sqrt(0.5);
        double[] q = MeasuredAttitude.wodEstAttdToBodyEciXyzw(0, 0, s, s);
        // +90° about +Z maps body +X → ECI +Y and body +Y → ECI −X.
        assertThat(applyQuat(q, Vector3D.PLUS_I).distance(Vector3D.PLUS_J)).isLessThan(1e-9);
        assertThat(applyQuat(q, Vector3D.PLUS_J).distance(Vector3D.MINUS_I)).isLessThan(1e-9);
        assertThat(applyQuat(q, Vector3D.PLUS_K).distance(Vector3D.PLUS_K)).isLessThan(1e-9);
    }

    @Test
    void normalizesNonUnitInput() {
        double[] q = MeasuredAttitude.wodEstAttdToBodyEciXyzw(0, 0, 0, 2); // 2x identity
        double n = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        assertThat(n).isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-12));
    }
}
