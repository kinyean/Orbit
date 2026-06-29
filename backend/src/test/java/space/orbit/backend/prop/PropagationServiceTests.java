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
    void finiteBurnApproximatesEquivalentImpulse() {
        // A short, high-thrust finite burn (Phase 9, US-MAN-11) centred on the epoch should
        // land within metres of the same ΔV applied impulsively — yet both must differ from
        // the un-maneuvered track by far more, proving the burn actually fired.
        Fixture f = fixture();
        AbsoluteDate burn = f.tle().getDate().shiftedBy(600.0);   // mid-stream (not the seed epoch)
        AbsoluteDate at = f.tle().getDate().shiftedBy(3600.0);
        double dvIntrack = 2.0; // m/s

        StateVector reference = f.service().sample(
                f.service().propagatorFor(f.tle(), Fidelity.NUMERICAL), at);
        StateVector impulsive = f.service().sample(f.service().propagatorFor(
                f.tle(), Fidelity.NUMERICAL,
                java.util.List.of(new Impulse(burn, 0.0, dvIntrack, 0.0))), at);
        StateVector finite = f.service().sample(f.service().propagatorFor(
                f.tle(), Fidelity.NUMERICAL,
                java.util.List.of(new Impulse(burn, 0.0, dvIntrack, 0.0, 1000.0, 300.0))), at);

        assertThat(finite.position().distance(impulsive.position()))
                .as("finite burn vs equivalent impulse, 50 min later (m)")
                .isLessThan(100.0);
        assertThat(impulsive.position().distance(reference.position()))
                .as("a 2 m/s in-track ΔV must move the track by far more than the finite/impulse gap (m)")
                .isGreaterThan(1000.0);
    }

    @Test
    void finiteDurationAchievesTheTargetDeltaV() {
        // The Tsiolkovsky duration must be the one whose integrated thrust yields the ΔV.
        double dv = 2.0, thrust = 1000.0, isp = 300.0, mass = 500.0;
        double g0 = 9.80665, ve = isp * g0;
        double duration = PropagationService.finiteDuration(dv, thrust, isp, mass);
        double mdot = thrust / ve;
        double achievedDv = ve * Math.log(mass / (mass - mdot * duration));
        assertThat(achievedDv).as("ΔV achieved over the computed burn duration (m/s)")
                .isCloseTo(dv, org.assertj.core.data.Offset.offset(1.0e-6));
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
