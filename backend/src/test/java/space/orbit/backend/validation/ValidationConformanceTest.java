package space.orbit.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.orbits.KeplerianOrbit;
import org.orekit.propagation.analytical.KeplerianPropagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.numerical.NumericalPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.NumericalPropagation;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.PropagationSettings;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.StateVector;
import space.orbit.backend.prop.TleFactory;

/**
 * §5.2 validation / conformance suite (Phase 10, US-INFRA-05). See
 * {@code docs/validation-conformance.md} for the full posture and the test →
 * SRS §5.2 → AIAA 2006-6753 mapping.
 *
 * <p>Posture (R2 / Decision 7): Orekit is the upstream-validated engine, so
 * absolute AIAA 2006-6753 accuracy is <em>inherited</em>; what we validate here
 * is that we <strong>integrated Orekit correctly</strong> — the right frames,
 * force model actually engaged, orbits bounded (not diverging from a wiring
 * bug), and byte-identical reruns (§5.4.1). Pinned to Orekit 13.1.5; these are
 * robust physical invariants, not brittle golden magic-numbers.
 *
 * <p>§5.2.3 (CW sub-metre / 1h &lt;10 km) is covered by {@code CwPropagationTests};
 * §5.2.4 (frame transforms) by {@code FrameServiceTests}. This class covers
 * §5.2.1 (SGP4) and §5.2.2 (high-fidelity numerical).
 */
class ValidationConformanceTest {

    private static final double DAY = 86400.0;

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    /** A representative ISS GP record (mean elements; ISS ~408 km, i=51.64°). */
    private static GpRecord issRecord() {
        return new GpRecord(
                "ISS (ZARYA)", "1998-067A", "2024-06-01T12:00:00.000",
                15.50125000, 0.0006703, 51.6416, 247.4627, 130.5360, 325.0288,
                25544, 999, 45000, 0.00010270, "U", 0);
    }

    private record Fixture(FrameService frames, SatellitePropagator sat,
                           NumericalPropagation numerical, TLE tle, StateVector seed) {}

    private static Fixture fixture() {
        FrameService frames = new FrameService();
        frames.init();
        SatellitePropagator sat = new SatellitePropagator(frames);
        NumericalPropagation numerical = new NumericalPropagation(frames);
        TleFactory factory = new TleFactory();
        factory.init();
        TLE tle = factory.fromGp(issRecord());
        StateVector seed = sat.eciState(sat.build(tle), tle.getDate());
        return new Fixture(frames, sat, numerical, tle, seed);
    }

    // --- §5.2.1 SGP4 -----------------------------------------------------------

    @Test
    void sgp4HoldsAStableLeoTrackOverAWeek() {
        // SGP4/SDP4 (§5.2.1). AIAA 2006-6753 conformance is inherited from Orekit
        // (documented); here we confirm our wrapper drives it correctly — a bound,
        // physically sane LEO track across a week (no frame/unit mis-wiring, which
        // would fling the radius out of the band immediately).
        Fixture f = fixture();
        TLEPropagator prop = f.sat().build(f.tle());
        for (int day = 0; day <= 7; day++) {
            AbsoluteDate t = f.tle().getDate().shiftedBy(day * DAY);
            PVCoordinates pv = prop.getPVCoordinates(t, f.frames().eci());
            double radiusKm = pv.getPosition().getNorm() / 1000.0;
            double speedKms = pv.getVelocity().getNorm() / 1000.0;
            assertThat(radiusKm).as("SGP4 LEO radius on day %d", day).isBetween(6500.0, 7200.0);
            assertThat(speedKms).as("SGP4 LEO speed on day %d", day).isBetween(7.3, 7.9);
        }
    }

    // --- §5.2.2 high-fidelity numerical ---------------------------------------

    @Test
    void numericalPerturbationsAreEngagedYetTheOrbitStaysBounded() {
        // High-fidelity numerical (§5.2.2). Two checks that the force model is
        // actually configured (not a no-op) and correctly signed:
        //   (a) over 24 h the perturbed track diverges from a two-body Kepler
        //       propagation of the same seed by many km (J2/drag/SRP/3rd-body ON);
        //   (b) the orbit nonetheless stays in the LEO band the whole time
        //       (a mis-wired force would diverge to nonsense, not stay bounded).
        Fixture f = fixture();
        AbsoluteDate epoch = f.seed().date();

        NumericalPropagator numerical = f.numerical().build(f.seed(), PropagationSettings.DEFAULT);
        KeplerianPropagator twoBody = new KeplerianPropagator(new KeplerianOrbit(
                new PVCoordinates(f.seed().position(), f.seed().velocity()),
                f.frames().eci(), epoch, Constants.WGS84_EARTH_MU));

        // (b) bounded across the day (sample ~hourly)
        for (int h = 0; h <= 24; h++) {
            PVCoordinates pv = numerical.getPVCoordinates(epoch.shiftedBy(h * 3600.0), f.frames().eci());
            assertThat(pv.getPosition().getNorm() / 1000.0)
                    .as("numerical LEO radius at +%d h stays bounded", h)
                    .isBetween(6400.0, 7300.0);
        }

        // (a) perturbations moved the track well away from two-body over a day
        AbsoluteDate end = epoch.shiftedBy(DAY);
        double sepKm = numerical.getPVCoordinates(end, f.frames().eci()).getPosition()
                .distance(twoBody.getPVCoordinates(end, f.frames().eci()).getPosition()) / 1000.0;
        assertThat(sepKm).as("perturbed vs two-body divergence over 24 h (km)").isGreaterThan(10.0);
    }

    @Test
    void numerical24hPropagationIsBitIdentical() {
        // §5.4.1 / R11 at the §5.2.2 24 h horizon: byte-identical reruns.
        Fixture f = fixture();
        AbsoluteDate end = f.seed().date().shiftedBy(DAY);
        PVCoordinates a = f.numerical().build(f.seed(), PropagationSettings.DEFAULT)
                .getPVCoordinates(end, f.frames().eci());
        PVCoordinates b = f.numerical().build(f.seed(), PropagationSettings.DEFAULT)
                .getPVCoordinates(end, f.frames().eci());
        assertThat(a.getPosition().getX()).isEqualTo(b.getPosition().getX());
        assertThat(a.getPosition().getY()).isEqualTo(b.getPosition().getY());
        assertThat(a.getPosition().getZ()).isEqualTo(b.getPosition().getZ());
    }
}
