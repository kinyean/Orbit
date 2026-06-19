package space.orbit.backend.prop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.hipparchus.geometry.euclidean.threed.Rotation;
import org.hipparchus.geometry.euclidean.threed.RotationConvention;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.util.FastMath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.frames.Frame;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.orbits.PositionAngleType;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.KeplerianPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;

/**
 * LVLH/RIC + body frame tests (US-FRAME-02, Decision 12). Pure JUnit.
 *
 * <p>The orientation tests are the R15 pin: a closed loop alone is rotation-
 * invariant (it stays closed under <em>any</em> frame convention), so it cannot
 * tell our R/I/C (Orekit {@code LOFType.LVLH}/{@code QSW}) apart from the CCSDS
 * nadir-down convention. We therefore assert that a known displacement lands on
 * the expected <em>signed</em> axis — radial-out&nbsp;&rarr;&nbsp;+R/+X,
 * in-track&nbsp;&rarr;&nbsp;+I/+Y, cross-track&nbsp;&rarr;&nbsp;+C/+Z — which is
 * what actually locks the convention to the glossary.
 */
class FrameRelativeTests {

    private static final double MU = Constants.WGS84_EARTH_MU;

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    private static FrameService frameService() {
        FrameService fs = new FrameService();
        fs.init();
        return fs;
    }

    /**
     * Chief on a prograde equatorial circular orbit in the XY plane, so its
     * LVLH axes coincide with ECI at this instant (radial=+X, in-track=+Y,
     * cross-track=+Z) — making the expected relative components trivial to read.
     */
    private static StateVector equatorialChief(FrameService fs, AbsoluteDate date) {
        double r = 7.0e6;
        double v = Math.sqrt(MU / r);
        return new StateVector(new Vector3D(r, 0, 0), new Vector3D(0, v, 0), date, fs.eci());
    }

    @Test
    void radialDisplacementLandsOnPlusRadialAxis() {
        FrameService fs = frameService();
        AbsoluteDate date = new AbsoluteDate(2024, 6, 1, 0, 0, 0.0, TimeScalesFactory.getUTC());
        StateVector chief = equatorialChief(fs, date);
        // 1 km radially outward (along +X = radial-out here), same velocity.
        StateVector deputy = new StateVector(
                chief.position().add(new Vector3D(1000.0, 0, 0)), chief.velocity(), date, fs.eci());

        StateVector rel = fs.toRelativeState(deputy, chief);
        assertThat(rel.position().getX()).as("radial (R)").isCloseTo(1000.0, within(1e-3));
        assertThat(rel.position().getY()).as("in-track (I)").isCloseTo(0.0, within(1e-3));
        assertThat(rel.position().getZ()).as("cross-track (C)").isCloseTo(0.0, within(1e-3));
    }

    @Test
    void inTrackDisplacementLandsOnPlusInTrackAxis() {
        FrameService fs = frameService();
        AbsoluteDate date = new AbsoluteDate(2024, 6, 1, 0, 0, 0.0, TimeScalesFactory.getUTC());
        StateVector chief = equatorialChief(fs, date);
        // 1 km ahead along the velocity direction (+Y). In QSW/LVLH this is +I;
        // in the CCSDS convention it would NOT be +Y — this assertion rejects it.
        StateVector deputy = new StateVector(
                chief.position().add(new Vector3D(0, 1000.0, 0)), chief.velocity(), date, fs.eci());

        StateVector rel = fs.toRelativeState(deputy, chief);
        assertThat(rel.position().getY()).as("in-track (I)").isCloseTo(1000.0, within(1e-3));
        assertThat(rel.position().getX()).as("radial (R)").isCloseTo(0.0, within(1e-3));
        assertThat(rel.position().getZ()).as("cross-track (C)").isCloseTo(0.0, within(1e-3));
    }

    @Test
    void crossTrackDisplacementLandsOnPlusCrossTrackAxis() {
        FrameService fs = frameService();
        AbsoluteDate date = new AbsoluteDate(2024, 6, 1, 0, 0, 0.0, TimeScalesFactory.getUTC());
        StateVector chief = equatorialChief(fs, date);
        // 1 km along the orbit normal (+Z = momentum direction here).
        StateVector deputy = new StateVector(
                chief.position().add(new Vector3D(0, 0, 1000.0)), chief.velocity(), date, fs.eci());

        StateVector rel = fs.toRelativeState(deputy, chief);
        assertThat(rel.position().getZ()).as("cross-track (C)").isCloseTo(1000.0, within(1e-3));
        assertThat(rel.position().getX()).as("radial (R)").isCloseTo(0.0, within(1e-3));
        assertThat(rel.position().getY()).as("in-track (I)").isCloseTo(0.0, within(1e-3));
    }

    @Test
    void boundedRelativeOrbitTracesAClosedLoopInLvlh() {
        // US-FRAME-02: a deputy on a co-period (same-SMA) orbit has bounded,
        // periodic relative motion that closes after one orbital period. Two-body
        // (Keplerian) propagation is exactly periodic, so the loop closes to
        // numerical precision.
        FrameService fs = frameService();
        Frame eci = fs.eci();
        AbsoluteDate t0 = new AbsoluteDate(2024, 6, 1, 0, 0, 0.0, TimeScalesFactory.getUTC());
        double a = 7.0e6;
        double i = FastMath.toRadians(51.6);
        KeplerianOrbit chiefOrbit = new KeplerianOrbit(
                a, 1.0e-4, i, FastMath.toRadians(10), FastMath.toRadians(20),
                FastMath.toRadians(0), PositionAngleType.TRUE, eci, t0, MU);
        KeplerianOrbit deputyOrbit = new KeplerianOrbit(
                a, 1.5e-4, i, FastMath.toRadians(10), FastMath.toRadians(20),
                FastMath.toRadians(0.05), PositionAngleType.TRUE, eci, t0, MU);
        Propagator chief = new KeplerianPropagator(chiefOrbit);
        Propagator deputy = new KeplerianPropagator(deputyOrbit);
        double period = chiefOrbit.getKeplerianPeriod();

        Vector3D start = relPosition(fs, deputy, chief, eci, t0);
        double maxSep = 0.0;
        for (int k = 0; k <= 12; k++) {
            Vector3D rel = relPosition(fs, deputy, chief, eci, t0.shiftedBy(period * k / 12.0));
            maxSep = Math.max(maxSep, rel.getNorm());
        }
        Vector3D end = relPosition(fs, deputy, chief, eci, t0.shiftedBy(period));

        assertThat(end.distance(start)).as("loop closes after one period").isLessThan(1.0);
        assertThat(maxSep).as("relative orbit is non-degenerate but bounded (m)").isBetween(100.0, 50_000.0);
    }

    @Test
    void bodyFrameRoundTripsAndActuallyRotates() {
        FrameService fs = frameService();
        AbsoluteDate date = new AbsoluteDate(2024, 6, 1, 0, 0, 0.0, TimeScalesFactory.getUTC());
        Rotation eciToBody = new Rotation(Vector3D.PLUS_K, FastMath.toRadians(30), RotationConvention.VECTOR_OPERATOR);
        Frame body = fs.body(eciToBody, "test-body");

        Vector3D v = new Vector3D(1, 2, 3);
        Vector3D inBody = fs.eci().getStaticTransformTo(body, date).transformVector(v);
        Vector3D back = body.getStaticTransformTo(fs.eci(), date).transformVector(inBody);

        assertThat(back.distance(v)).as("ECI->body->ECI round trip").isLessThan(1e-9);
        assertThat(inBody.distance(v)).as("rotation is non-trivial").isGreaterThan(0.1);
    }

    // --- modeled attitude (Phase 7, US-PROX-01, Decision 24) -----------------
    //
    // R15 pin for the *orientation* convention: assert the streamed quaternion maps
    // the model's body axes (+Y = nose/ram, +Z = top/radial) onto the expected scene
    // axes (R→+X, I→+Y, C→+Z). A round-trip alone wouldn't catch a flipped convention.

    @Test
    void basisQuaternionMatchesThreeJsConvention() {
        // nose = scene +Y (in-track), top = scene +X (radial) — a chief-like LVLH pose.
        double[] q = FrameService.basisQuaternion(new Vector3D(0, 1, 0), new Vector3D(1, 0, 0));
        assertThat(applyQuat(q, new Vector3D(0, 1, 0)).distance(new Vector3D(0, 1, 0)))
                .as("body +Y (nose) -> scene +Y (in-track)").isLessThan(1e-9);
        assertThat(applyQuat(q, new Vector3D(0, 0, 1)).distance(new Vector3D(1, 0, 0)))
                .as("body +Z (top) -> scene +X (radial)").isLessThan(1e-9);
    }

    @Test
    void lvlhAttitudeOfCircularChiefAlignsNoseToInTrackTopToRadial() {
        FrameService fs = frameService();
        AbsoluteDate date = new AbsoluteDate(2024, 6, 1, 0, 0, 0.0, TimeScalesFactory.getUTC());
        // Equatorial circular chief: its LVLH frame coincides with ECI here.
        KeplerianOrbit chief = new KeplerianOrbit(7.0e6, 1.0e-4, 0.0,
                0.0, 0.0, 0.0, PositionAngleType.TRUE, fs.eci(), date, MU);
        Propagator prop = new KeplerianPropagator(chief);
        Frame lvlh = fs.lvlh(prop);

        double[] q = fs.bodyQuaternionInLvlh(prop, lvlh, date, "lvlh", null);
        assertThat(applyQuat(q, new Vector3D(0, 1, 0)).getY()).as("nose -> +I").isCloseTo(1.0, within(1e-2));
        assertThat(applyQuat(q, new Vector3D(0, 0, 1)).getX()).as("top -> +R").isCloseTo(1.0, within(1e-2));
    }

    @Test
    void fixedAttitudeDiffersFromLvlhAndIsNormalized() {
        FrameService fs = frameService();
        AbsoluteDate date = new AbsoluteDate(2024, 6, 1, 0, 0, 0.0, TimeScalesFactory.getUTC());
        KeplerianOrbit chief = new KeplerianOrbit(7.0e6, 1.0e-4, 0.0,
                0.0, 0.0, 0.0, PositionAngleType.TRUE, fs.eci(), date, MU);
        Propagator prop = new KeplerianPropagator(chief);
        Frame lvlh = fs.lvlh(prop);
        // 90° about +Z (x,y,z,w) = (0,0,sin45,cos45).
        double s = Math.sqrt(0.5);
        double[] q = fs.bodyQuaternionInLvlh(prop, lvlh, date, "fixed", new double[] {0, 0, s, s});
        double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        assertThat(norm).as("unit quaternion").isCloseTo(1.0, within(1e-6));
        double[] lvlhQ = fs.bodyQuaternionInLvlh(prop, lvlh, date, "lvlh", null);
        double diff = Math.abs(q[0] - lvlhQ[0]) + Math.abs(q[1] - lvlhQ[1])
                + Math.abs(q[2] - lvlhQ[2]) + Math.abs(q[3] - lvlhQ[3]);
        assertThat(diff).as("fixed attitude differs from the LVLH model").isGreaterThan(0.1);
    }

    /** Rotate {@code v} by a {@code (x,y,z,w)} quaternion (Hamilton / three.js convention). */
    private static Vector3D applyQuat(double[] q, Vector3D v) {
        double x = q[0], y = q[1], z = q[2], w = q[3];
        double tx = 2.0 * (y * v.getZ() - z * v.getY());
        double ty = 2.0 * (z * v.getX() - x * v.getZ());
        double tz = 2.0 * (x * v.getY() - y * v.getX());
        return new Vector3D(
                v.getX() + w * tx + (y * tz - z * ty),
                v.getY() + w * ty + (z * tx - x * tz),
                v.getZ() + w * tz + (x * ty - y * tx));
    }

    @Test
    void missingChiefIsAClearError() {
        FrameService fs = frameService();
        AbsoluteDate date = new AbsoluteDate(2024, 6, 1, 0, 0, 0.0, TimeScalesFactory.getUTC());
        StateVector deputy = equatorialChief(fs, date);

        assertThatThrownBy(() -> fs.lvlh(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fs.ric(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fs.toRelativeState(deputy, null)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Vector3D relPosition(FrameService fs, Propagator deputy, Propagator chief,
                                        Frame eci, AbsoluteDate t) {
        StateVector c = stateAt(chief, t, eci);
        StateVector d = stateAt(deputy, t, eci);
        return fs.toRelativeState(d, c).position();
    }

    private static StateVector stateAt(Propagator p, AbsoluteDate t, Frame eci) {
        PVCoordinates pv = p.getPVCoordinates(t, eci);
        return new StateVector(pv.getPosition(), pv.getVelocity(), t, eci);
    }
}
