package space.orbit.backend.prop;

import static org.assertj.core.api.Assertions.assertThat;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.hipparchus.util.FastMath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.frames.FramesFactory;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;
import space.orbit.backend.io.GpRecord;

/**
 * Validates the OMM&rarr;TLE&rarr;SGP4 path against Orekit (our SGP4 reference
 * implementation per Decision 7). Pure JUnit — no Spring context, no DB. The
 * checks are physics invariants of a LEO orbit (radius band, orbital speed,
 * bounded over a full revolution) rather than brittle magic numbers; a wrong
 * unit conversion or wrong constructor arg order in {@link TleFactory} breaks
 * them loudly. External golden-vector conformance (AIAA 2006-6753) is the
 * Phase 3 §5.2 validation suite.
 */
class Sgp4PropagationTests {

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    /** A representative ISS GP record (mean elements; ISS ~408 km, i=51.64°). */
    private static GpRecord issRecord() {
        return new GpRecord(
                "ISS (ZARYA)",
                "1998-067A",
                "2024-06-01T12:00:00.000",
                15.50125000,   // rev/day
                0.0006703,
                51.6416,       // deg
                247.4627,
                130.5360,
                325.0288,
                25544,
                999,
                45000,
                0.00010270,
                "U",
                0);
    }

    @Test
    void ommToTleRoundTripsThroughGetters() {
        TleFactory factory = new TleFactory();
        factory.init();
        TLE tle = factory.fromGp(issRecord());

        // getI() etc. are RADIANS — convert back and confirm we fed them correctly.
        assertThat(FastMath.toDegrees(tle.getI())).isCloseTo(51.6416, org.assertj.core.api.Assertions.within(1e-3));
        assertThat(tle.getE()).isCloseTo(0.0006703, org.assertj.core.api.Assertions.within(1e-7));
        assertThat(tle.getSatelliteNumber()).isEqualTo(25544);
        // mean motion rad/s back to rev/day
        double revPerDay = tle.getMeanMotion() * 86400.0 / (2.0 * Math.PI);
        assertThat(revPerDay).isCloseTo(15.50125000, org.assertj.core.api.Assertions.within(1e-5));
    }

    @Test
    void sgp4ProducesAStableLeoOrbit() {
        TleFactory factory = new TleFactory();
        factory.init();
        TLE tle = factory.fromGp(issRecord());
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle, FramesFactory.getTEME());

        AbsoluteDate epoch = tle.getDate();
        double periodSec = 2.0 * Math.PI / tle.getMeanMotion();

        // Sample across one full revolution in the inertial frame.
        for (int i = 0; i <= 12; i++) {
            AbsoluteDate t = epoch.shiftedBy(periodSec * i / 12.0);
            PVCoordinates pv = propagator.getPVCoordinates(t, FramesFactory.getEME2000());
            double radiusKm = pv.getPosition().getNorm() / 1000.0;
            double speedKms = pv.getVelocity().getNorm() / 1000.0;
            assertThat(radiusKm)
                    .as("LEO orbital radius at step %d", i)
                    .isBetween(6500.0, 7200.0);   // ~6786 km mean for the ISS
            assertThat(speedKms)
                    .as("LEO orbital speed at step %d", i)
                    .isBetween(7.3, 7.9);          // ~7.66 km/s
        }
    }

    @Test
    void positionDiffersBetweenEciAndEcef() {
        // Sanity: the TEME->ECEF rotation is actually applied (not a no-op),
        // i.e. Orekit data + frame transforms are wired correctly.
        TleFactory factory = new TleFactory();
        factory.init();
        TLE tle = factory.fromGp(issRecord());
        TLEPropagator propagator = TLEPropagator.selectExtrapolator(tle, FramesFactory.getTEME());
        AbsoluteDate t = tle.getDate().shiftedBy(600.0);

        Vector3D eci = propagator.getPVCoordinates(t, FramesFactory.getEME2000()).getPosition();
        Vector3D ecef = propagator.getPVCoordinates(
                t, FramesFactory.getITRF(org.orekit.utils.IERSConventions.IERS_2010, true)).getPosition();

        // Same radius (rotation preserves magnitude) but different vector.
        assertThat(ecef.getNorm()).isCloseTo(eci.getNorm(), org.assertj.core.api.Assertions.within(1.0));
        assertThat(ecef.distance(eci)).isGreaterThan(1000.0);
    }
}
