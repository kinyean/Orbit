package space.orbit.backend.prop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.bodies.GeodeticPoint;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;

/**
 * Round-trip + sanity tests for {@link FrameService}. Pure JUnit. Confirms the
 * geodetic conversion and the frame-tag plumbing behave (Decision 12, §5.2.4
 * "transforms precise to 1e-9" — checked here as a sub-millimetre ECEF
 * round-trip).
 */
class FrameServiceTests {

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    private static FrameService newFrameService() {
        FrameService fs = new FrameService();
        fs.init();
        return fs;
    }

    @Test
    void geodeticRoundTripIsExact() {
        FrameService fs = newFrameService();
        AbsoluteDate date = new AbsoluteDate(2024, 6, 1, 0, 0, 0.0, TimeScalesFactory.getUTC());

        // An ECEF point ~400 km up.
        Vector3D ecef = new Vector3D(6_778_137.0, 1_000_000.0, 2_000_000.0);
        GeodeticPoint gp = fs.toGeodetic(ecef, fs.ecef(), date);
        Vector3D back = fs.geodeticToEcef(gp);

        assertThat(back.distance(ecef))
                .as("ECEF -> geodetic -> ECEF round trip")
                .isLessThan(1e-3); // sub-millimetre
    }

    @Test
    void altitudeOfKnownPointIsReasonable() {
        FrameService fs = newFrameService();
        AbsoluteDate date = new AbsoluteDate(2024, 6, 1, 0, 0, 0.0, TimeScalesFactory.getUTC());

        // A point on the equatorial plane at radius = Re + 400 km.
        double r = 6_378_137.0 + 400_000.0;
        Vector3D ecef = new Vector3D(r, 0.0, 0.0);
        GeodeticPoint gp = fs.toGeodetic(ecef, fs.ecef(), date);

        assertThat(gp.getAltitude()).isCloseTo(400_000.0, within(5_000.0));
        assertThat(Math.toDegrees(gp.getLatitude())).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void eciToEcefPreservesMagnitude() {
        FrameService fs = newFrameService();
        AbsoluteDate date = new AbsoluteDate(2024, 6, 1, 6, 0, 0.0, TimeScalesFactory.getUTC());

        Vector3D eci = new Vector3D(7_000_000.0, 1_500_000.0, -2_000_000.0);
        Vector3D ecef = fs.toEcef(eci, fs.eci(), date);

        // A pure rotation between inertial and Earth-fixed preserves length.
        assertThat(ecef.getNorm()).isCloseTo(eci.getNorm(), within(1.0));
        // ...but the vectors differ (Earth has rotated under the inertial point).
        assertThat(ecef.distance(eci)).isGreaterThan(1000.0);
    }
}
