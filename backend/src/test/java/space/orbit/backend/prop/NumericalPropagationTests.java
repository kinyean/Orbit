package space.orbit.backend.prop;

import static org.assertj.core.api.Assertions.assertThat;

import org.orekit.orbits.KeplerianOrbit;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.numerical.NumericalPropagator;
import org.orekit.time.AbsoluteDate;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import space.orbit.backend.io.GpRecord;

/**
 * Invariant + determinism tests for the numerical propagator (US-PROP-02,
 * SRS §3.1.2–6, §5.4.1). Pure JUnit — no Spring, no DB. These are physics
 * sanity checks that the DP8(7) integrator and the full force model are wired
 * (bound LEO over a rev, energy roughly held), plus a bit-identical-rerun
 * determinism check — not a golden-vector conformance suite (that's Phase 10).
 */
class NumericalPropagationTests {

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

    private record Fixture(FrameService frames, NumericalPropagation numerical, StateVector seed, double periodSec) {}

    private static Fixture fixture() {
        FrameService frames = new FrameService();
        frames.init();
        SatellitePropagator sat = new SatellitePropagator(frames);
        NumericalPropagation numerical = new NumericalPropagation(frames);
        TleFactory factory = new TleFactory();
        factory.init();
        TLE tle = factory.fromGp(issRecord());
        TLEPropagator sgp4 = sat.build(tle);
        StateVector seed = sat.eciState(sgp4, tle.getDate());
        double periodSec = 2.0 * Math.PI / tle.getMeanMotion();
        return new Fixture(frames, numerical, seed, periodSec);
    }

    private static double smaKm(PVCoordinates pv, org.orekit.frames.Frame eci, AbsoluteDate date) {
        // Osculating semi-major axis from the Cartesian state.
        return new KeplerianOrbit(pv, eci, date, Constants.WGS84_EARTH_MU).getA() / 1000.0;
    }

    @Test
    void numericalPropagatorHoldsABoundLeoOrbitOverOneRev() {
        Fixture f = fixture();
        NumericalPropagator prop = f.numerical().build(f.seed(), PropagationSettings.DEFAULT);
        AbsoluteDate epoch = f.seed().date();
        double sma0 = smaKm(prop.getPVCoordinates(epoch, f.frames().eci()), f.frames().eci(), epoch);

        for (int i = 0; i <= 12; i++) {
            AbsoluteDate t = epoch.shiftedBy(f.periodSec() * i / 12.0);
            PVCoordinates pv = prop.getPVCoordinates(t, f.frames().eci());
            double radiusKm = pv.getPosition().getNorm() / 1000.0;
            double speedKms = pv.getVelocity().getNorm() / 1000.0;
            assertThat(radiusKm).as("LEO radius at step %d", i).isBetween(6500.0, 7200.0);
            assertThat(speedKms).as("LEO speed at step %d", i).isBetween(7.3, 7.9);
            // Osculating SMA oscillates under J2 short-period terms (amplitude
            // ~10 km for LEO); a 30 km band tolerates that yet catches a grossly
            // mis-wired force (wrong sign/scale would diverge far past this).
            assertThat(smaKm(pv, f.frames().eci(), t))
                    .as("osculating SMA at step %d stays near the initial orbit", i)
                    .isCloseTo(sma0, org.assertj.core.api.Assertions.within(30.0));
        }
    }

    @Test
    void numericalPropagationIsBitIdenticalOnRerun() {
        // SRS §5.4.1 / R11: same inputs, same platform → identical state.
        Fixture f = fixture();
        AbsoluteDate target = f.seed().date().shiftedBy(3600.0); // one hour, many integrator steps

        NumericalPropagator a = f.numerical().build(f.seed(), PropagationSettings.DEFAULT);
        NumericalPropagator b = f.numerical().build(f.seed(), PropagationSettings.DEFAULT);
        PVCoordinates pa = a.getPVCoordinates(target, f.frames().eci());
        PVCoordinates pb = b.getPVCoordinates(target, f.frames().eci());

        assertThat(pa.getPosition().getX()).isEqualTo(pb.getPosition().getX());
        assertThat(pa.getPosition().getY()).isEqualTo(pb.getPosition().getY());
        assertThat(pa.getPosition().getZ()).isEqualTo(pb.getPosition().getZ());
        assertThat(pa.getVelocity().getX()).isEqualTo(pb.getVelocity().getX());
        assertThat(pa.getVelocity().getY()).isEqualTo(pb.getVelocity().getY());
        assertThat(pa.getVelocity().getZ()).isEqualTo(pb.getVelocity().getZ());
    }

    @Test
    void rejectsANonEciSeed() {
        Fixture f = fixture();
        StateVector ecefSeed = new StateVector(
                f.seed().position(), f.seed().velocity(), f.seed().date(), f.frames().ecef());
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> f.numerical().build(ecefSeed, PropagationSettings.DEFAULT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
