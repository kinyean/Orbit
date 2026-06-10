package space.orbit.backend.prop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.propagation.Propagator;
import org.orekit.propagation.analytical.tle.TLE;
import org.orekit.propagation.analytical.tle.TLEPropagator;
import org.orekit.propagation.numerical.NumericalPropagator;
import org.orekit.time.AbsoluteDate;
import space.orbit.backend.io.GpRecord;

/**
 * Tests the fidelity-dispatch seam (SRS §3.1.8). Pure JUnit. Confirms each
 * {@link Fidelity} maps to the right engine, that both engines sample to a
 * uniform ECI {@link StateVector} of plausible LEO magnitude, that CW is a
 * clean not-yet-implemented signal, and that {@link Fidelity#fromString}
 * normalises and defaults safely.
 */
class PropagationServiceTests {

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    private static GpRecord issRecord() {
        return new GpRecord(
                "ISS (ZARYA)", "1998-067A", "2024-06-01T12:00:00.000",
                15.50125000, 0.0006703, 51.6416, 247.4627, 130.5360, 325.0288,
                25544, 999, 45000, 0.00010270, "U", 0);
    }

    private record Fixture(PropagationService service, FrameService frames, TLE tle) {}

    private static Fixture fixture() {
        FrameService frames = new FrameService();
        frames.init();
        SatellitePropagator sat = new SatellitePropagator(frames);
        NumericalPropagation numerical = new NumericalPropagation(frames);
        PropagationService service = new PropagationService(sat, numerical, frames);
        TleFactory factory = new TleFactory();
        factory.init();
        return new Fixture(service, frames, factory.fromGp(issRecord()));
    }

    @Test
    void sgp4FidelityYieldsAnSgp4Propagator() {
        Fixture f = fixture();
        Propagator p = f.service().propagatorFor(f.tle(), Fidelity.SGP4);
        assertThat(p).isInstanceOf(TLEPropagator.class);

        StateVector s = f.service().sample(p, f.tle().getDate().shiftedBy(60.0));
        assertThat(s.frame()).isEqualTo(f.frames().eci());
        assertThat(s.position().getNorm() / 1000.0).isBetween(6500.0, 7200.0);
        assertThat(s.velocity().getNorm() / 1000.0).isBetween(7.3, 7.9);
    }

    @Test
    void numericalFidelityYieldsANumericalPropagator() {
        Fixture f = fixture();
        Propagator p = f.service().propagatorFor(f.tle(), Fidelity.NUMERICAL);
        assertThat(p).isInstanceOf(NumericalPropagator.class);

        StateVector s = f.service().sample(p, f.tle().getDate().shiftedBy(60.0));
        assertThat(s.frame()).isEqualTo(f.frames().eci());
        assertThat(s.position().getNorm() / 1000.0).isBetween(6500.0, 7200.0);
        assertThat(s.velocity().getNorm() / 1000.0).isBetween(7.3, 7.9);
    }

    @Test
    void cwFidelityIsNotYetImplemented() {
        Fixture f = fixture();
        assertThatThrownBy(() -> f.service().propagatorFor(f.tle(), Fidelity.CW))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void fromStringNormalisesAndDefaults() {
        assertThat(Fidelity.fromString("numerical")).isEqualTo(Fidelity.NUMERICAL);
        assertThat(Fidelity.fromString("SGP4")).isEqualTo(Fidelity.SGP4);
        assertThat(Fidelity.fromString("  Cw ")).isEqualTo(Fidelity.CW);
        assertThat(Fidelity.fromString(null)).isEqualTo(Fidelity.SGP4);
        assertThat(Fidelity.fromString("")).isEqualTo(Fidelity.SGP4);
        assertThat(Fidelity.fromString("   ")).isEqualTo(Fidelity.SGP4);
        assertThat(Fidelity.fromString("nonsense")).isEqualTo(Fidelity.SGP4);
    }

    @Test
    void bothEnginesAgreeOnGrossPositionShortlyAfterEpoch() {
        // Over 60 s the perturbations barely diverge from SGP4; the two engines
        // should place the satellite within a few km. Catches a seed/frame
        // blunder in the numerical path (e.g. wrong frame, wrong epoch).
        Fixture f = fixture();
        AbsoluteDate t = f.tle().getDate().shiftedBy(60.0);
        StateVector sgp4 = f.service().sample(f.service().propagatorFor(f.tle(), Fidelity.SGP4), t);
        StateVector num = f.service().sample(f.service().propagatorFor(f.tle(), Fidelity.NUMERICAL), t);
        assertThat(num.position().distance(sgp4.position()) / 1000.0)
                .as("numerical vs SGP4 position 60 s after epoch (km)")
                .isLessThan(5.0);
    }
}
