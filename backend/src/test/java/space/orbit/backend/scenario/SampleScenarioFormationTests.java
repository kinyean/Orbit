package space.orbit.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.frames.Frame;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.PVCoordinates;
import space.orbit.backend.analysis.EclipseEvent;
import space.orbit.backend.analysis.EclipseEventComputer;
import space.orbit.backend.analysis.SampledCraft;
import space.orbit.backend.analysis.SampledGeocentricCraft;
import space.orbit.backend.analysis.SensorEvent;
import space.orbit.backend.analysis.SensorEventComputer;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;

/**
 * Validates that each seeded demo (see {@link SampleScenarioSeeder}) actually shows
 * what its name promises — the same definition the seeder ships, checked with the
 * same analysis computers the stream runs. Pure JUnit + Orekit.
 */
class SampleScenarioFormationTests {

    private static FrameService frames;
    private static SatellitePropagator sat;
    private static TleFactory factory;

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
        frames = new FrameService();
        frames.init();
        sat = new SatellitePropagator(frames);
        factory = new TleFactory();
        factory.init();
    }

    @Test
    void deputyTracesABoundedFewKmEllipseInChiefLvlh() {
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

    /** The sensor demo's imager (boresight = body +Y = ram) acquires and loses the
     *  circumnavigating deputy — the AOS/LOS timeline windows the demo exists to show. */
    @Test
    void sensorDemoImagerAcquiresAndLosesTheDeputy() {
        TLE chiefTle = factory.fromGp(SampleScenarioSeeder.chiefRecord());
        TLE deputyTle = factory.fromGp(SampleScenarioSeeder.deputyRecord());
        TLEPropagator chief = sat.build(chiefTle);
        TLEPropagator deputy = sat.build(deputyTle);

        Frame eci = frames.eci();
        Frame lvlh = frames.lvlh(chief);
        AbsoluteDate t0 = chiefTle.getDate();
        int step = 60;
        int steps = 3 * 3600 / step; // the demo's 3 h window

        // Sample the same arrays the stream would: deputy LVLH position + both
        // craft's modeled ("lvlh") body attitude on the grid.
        double[] depPos = new double[(steps + 1) * 4];
        double[] chiefAtt = new double[(steps + 1) * 5];
        double[] depAtt = new double[(steps + 1) * 5];
        for (int k = 0; k <= steps; k++) {
            double t = (double) k * step;
            AbsoluteDate date = t0.shiftedBy(t);
            Vector3D rel = relPosition(eci, lvlh, deputy, date);
            depPos[k * 4] = t;
            depPos[k * 4 + 1] = rel.getX();
            depPos[k * 4 + 2] = rel.getY();
            depPos[k * 4 + 3] = rel.getZ();
            fillAtt(chiefAtt, k, t, frames.bodyQuaternionInLvlh(chief, lvlh, date, "lvlh", null));
            fillAtt(depAtt, k, t, frames.bodyQuaternionInLvlh(deputy, lvlh, date, "lvlh", null));
        }
        double chiefRadiusM = chief.getPVCoordinates(t0, eci).getPosition().getNorm();

        SampledCraft chiefCraft = new SampledCraft(99001, null, 4, chiefAtt,
                List.of(SampleScenarioSeeder.demoImager()));
        SampledCraft deputyCraft = new SampledCraft(99002, depPos, 4, depAtt, List.of());

        List<SensorEvent> events = new SensorEventComputer().compute(
                List.of(chiefCraft, deputyCraft), 0.0, step, steps,
                Instant.parse("2026-06-01T00:00:00Z"), 3 * 3600, chiefRadiusM);

        assertThat(events).as("imager sees the deputy at least once")
                .anyMatch(e -> "acquisition".equals(e.type()) && e.targetId() == 99002);
        assertThat(events).as("…and loses it again")
                .anyMatch(e -> "los".equals(e.type()) && e.targetId() == 99002);
        assertThat(events).allSatisfy(e -> {
            assertThat(e.sensorId()).isEqualTo("demo-imager");
            assertThat(e.rangeM()).as("crossings happen inside the range band")
                    .isBetween(50.0, 5_000.0);
        });
    }

    /** The eclipse demo's 6 h window really contains umbra passes (UC-5's whole point). */
    @Test
    void eclipseDemoWindowContainsUmbraPasses() {
        TLE chiefTle = factory.fromGp(SampleScenarioSeeder.chiefRecord());
        TLEPropagator chief = sat.build(chiefTle);
        Frame eci = frames.eci();
        AbsoluteDate t0 = chiefTle.getDate();
        int step = 60;
        int steps = 6 * 3600 / step; // the demo's 6 h window

        double[] posEci = new double[(steps + 1) * 4];
        double[] sunEci = new double[(steps + 1) * 4];
        for (int k = 0; k <= steps; k++) {
            double t = (double) k * step;
            AbsoluteDate date = t0.shiftedBy(t);
            Vector3D p = chief.getPVCoordinates(date, eci).getPosition();
            Vector3D s = frames.sunPosition(date);
            posEci[k * 4] = t;
            posEci[k * 4 + 1] = p.getX();
            posEci[k * 4 + 2] = p.getY();
            posEci[k * 4 + 3] = p.getZ();
            sunEci[k * 4] = t;
            sunEci[k * 4 + 1] = s.getX();
            sunEci[k * 4 + 2] = s.getY();
            sunEci[k * 4 + 3] = s.getZ();
        }

        List<EclipseEvent> events = new EclipseEventComputer().compute(
                List.of(new SampledGeocentricCraft(99001, posEci)), sunEci,
                0.0, step, steps, Instant.parse("2026-06-01T00:00:00Z"), 6 * 3600);

        assertThat(events).anyMatch(e -> "umbra-ingress".equals(e.type()));
        assertThat(events).anyMatch(e -> "umbra-egress".equals(e.type()));
    }

    /** The V-bar station demo parks the deputy ~2 km behind the chief on the in-track
     *  axis and holds it there — the hold/glideslope templates' starting geometry. */
    @Test
    void vbarStationHoldsTwoKmBehindOnTheVbar() {
        TLE chiefTle = factory.fromGp(SampleScenarioSeeder.chiefRecord());
        TLE stationTle = factory.fromGp(SampleScenarioSeeder.vbarStationRecord());
        TLEPropagator chief = sat.build(chiefTle);
        TLEPropagator station = sat.build(stationTle);

        Frame eci = frames.eci();
        Frame lvlh = frames.lvlh(chief);
        AbsoluteDate t0 = chiefTle.getDate();
        double periodSec = 2.0 * Math.PI / chiefTle.getMeanMotion();

        for (int k = 0; k <= 40; k++) {
            Vector3D rel = relPosition(eci, lvlh, station, t0.shiftedBy(2.0 * periodSec * k / 40.0));
            assertThat(rel.getY() / 1000.0).as("in-track offset (km), behind = negative")
                    .isBetween(-2.5, -1.5);
            assertThat(Math.abs(rel.getX())).as("radial excursion (m)").isLessThan(300.0);
            assertThat(Math.abs(rel.getZ())).as("cross-track excursion (m)").isLessThan(300.0);
        }
    }

    private static void fillAtt(double[] att, int k, double t, double[] quatXyzw) {
        att[k * 5] = t;
        att[k * 5 + 1] = quatXyzw[0];
        att[k * 5 + 2] = quatXyzw[1];
        att[k * 5 + 3] = quatXyzw[2];
        att[k * 5 + 4] = quatXyzw[3];
    }

    private static Vector3D relPosition(Frame eci, Frame lvlh, TLEPropagator deputy, AbsoluteDate date) {
        PVCoordinates depEci = deputy.getPVCoordinates(date, eci);
        return eci.getTransformTo(lvlh, date).transformPVCoordinates(depEci).getPosition();
    }
}
