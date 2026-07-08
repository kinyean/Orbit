package space.orbit.backend.prop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hipparchus.geometry.euclidean.threed.Vector3D;
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
    void stabilizedManeuveredProviderIsOrderIndependentAcrossTheBurn() {
        // A raw maneuvered NumericalPropagator is stateful across its ImpulseManeuver:
        // sweep it forward past the burn, then jump BACK to an earlier date, and the
        // re-integration realizes the impulse differently — so a second sampling pass
        // (as ScenarioStreamService does: CZML pass, then relative pass) disagrees with
        // the first by tens+ of metres downstream of the burn. stabilizeForRepeatedSampling
        // freezes it into a bounded ephemeris that samples identically in any order.
        Fixture f = fixture();
        AbsoluteDate epoch = f.tle().getDate();
        AbsoluteDate burn = epoch.shiftedBy(1800.0);
        AbsoluteDate from = epoch.shiftedBy(60.0);
        AbsoluteDate to = epoch.shiftedBy(5400.0);
        var imps = java.util.List.of(new Impulse(burn, 8.0, -2.0, 4.0));
        AbsoluteDate probe = to; // downstream of the burn, where divergence accumulates

        // The correct answer: a fresh propagator swept forward once (what pass 1 sees).
        Vector3D reference = f.service().sample(
                f.service().propagatorFor(f.tle(), Fidelity.NUMERICAL, imps), probe).position();

        var stable = f.service().stabilizeForRepeatedSampling(
                f.service().propagatorFor(f.tle(), Fidelity.NUMERICAL, imps), from, to);

        // Sample the stabilized provider in a SCRAMBLED order (end, start, mid, end),
        // mimicking two passes that each jump back to the grid start. It must return the
        // same state each time and match the forward reference.
        Vector3D first = stable.getPVCoordinates(probe, f.frames().eci()).getPosition();
        stable.getPVCoordinates(from, f.frames().eci());
        stable.getPVCoordinates(epoch.shiftedBy(2700.0), f.frames().eci());
        Vector3D again = stable.getPVCoordinates(probe, f.frames().eci()).getPosition();

        assertThat(first.distance(again)).as("re-sampled in scrambled order, same result (m)")
                .isLessThan(1.0e-6);
        // A bounded ephemeris interpolates, so it differs from the raw integrator by
        // cm — far below the tens-to-hundreds of metres the reuse bug produced.
        assertThat(first.distance(reference)).as("stabilized provider matches the forward sweep (m)")
                .isLessThan(1.0);
    }

    @Test
    void stabilizeLeavesAnalyticalProvidersUnchanged() {
        // SGP4 (and CW / tabulated) providers are already order-independent — the helper
        // must return them untouched (no needless bounded-ephemeris wrap).
        Fixture f = fixture();
        Propagator sgp4 = f.service().propagatorFor(f.tle(), Fidelity.SGP4);
        assertThat(f.service().stabilizeForRepeatedSampling(
                sgp4, f.tle().getDate(), f.tle().getDate().shiftedBy(3600.0))).isSameAs(sgp4);
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
