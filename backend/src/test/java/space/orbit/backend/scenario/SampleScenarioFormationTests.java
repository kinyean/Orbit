package space.orbit.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.frames.Frame;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;

/**
 * Validates that the seeded demo (see {@link SampleScenarioSeeder}) is a genuine
 * <em>close formation</em>: the deputy's motion in the chief's LVLH frame is a
 * bounded few-km ellipse that nearly closes after one orbit — i.e. exactly the
 * relative-motion shape the proximity view is built to show, not the
 * thousands-of-km scatter of arbitrary catalog satellites. Pure JUnit + Orekit.
 */
class SampleScenarioFormationTests {

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    @Test
    void deputyTracesABoundedFewKmEllipseInChiefLvlh() {
        FrameService frames = new FrameService();
        frames.init();
        SatellitePropagator sat = new SatellitePropagator(frames);
        TleFactory factory = new TleFactory();
        factory.init();

        TLE chiefTle = factory.fromGp(SampleScenarioSeeder.chiefRecord());
        TLE deputyTle = factory.fromGp(SampleScenarioSeeder.deputyRecord());

        // The seeder freezes getLine1()/getLine2() into the body and rebuilds from
        // them — exercise that here (it rejects 6-digit NORAD ids), then re-parse.
        TLE chiefRoundTrip = new TLE(chiefTle.getLine1(), chiefTle.getLine2());
        assertThat(chiefRoundTrip.getSatelliteNumber()).isEqualTo(99001);
        assertThat(new TLE(deputyTle.getLine1(), deputyTle.getLine2()).getSatelliteNumber()).isEqualTo(99002);

        TLEPropagator chief = sat.build(chiefTle);
        TLEPropagator deputy = sat.build(deputyTle);

        Frame eci = frames.eci();
        Frame lvlh = frames.lvlh(chief); // rotating LVLH over the live chief orbit
        AbsoluteDate t0 = chiefTle.getDate();
        double periodSec = 2.0 * Math.PI / chiefTle.getMeanMotion();

        Vector3D start = relPosition(eci, lvlh, deputy, t0);
        double maxSep = 0.0;
        double minSep = Double.MAX_VALUE;
        for (int k = 0; k <= 60; k++) {
            Vector3D rel = relPosition(eci, lvlh, deputy, t0.shiftedBy(periodSec * k / 60.0));
            double sepKm = rel.getNorm() / 1000.0;
            maxSep = Math.max(maxSep, sepKm);
            minSep = Math.min(minSep, sepKm);
        }
        Vector3D end = relPosition(eci, lvlh, deputy, t0.shiftedBy(periodSec));

        // A tight, bounded sub-km circumnavigation: close (≤ a couple km), not
        // coincident, and the loop nearly closes after one orbit (small SGP4/J2 drift).
        assertThat(maxSep).as("max LVLH separation over one orbit (km)").isBetween(0.1, 2.0);
        assertThat(minSep).as("deputies don't perfectly overlap (km)").isGreaterThan(0.02);
        assertThat(end.distance(start) / 1000.0).as("relative orbit nearly closes (km)").isLessThan(0.5);
    }

    private static Vector3D relPosition(Frame eci, Frame lvlh, TLEPropagator deputy, AbsoluteDate date) {
        PVCoordinates depEci = deputy.getPVCoordinates(date, eci);
        return eci.getTransformTo(lvlh, date).transformPVCoordinates(depEci).getPosition();
    }
}
