package space.orbit.backend.prop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hipparchus.geometry.euclidean.threed.Vector3D;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.frames.Frame;
import org.orekit.frames.FramesFactory;
import org.orekit.orbits.CartesianOrbit;
import org.orekit.propagation.SpacecraftState;
import org.orekit.propagation.analytical.Ephemeris;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.TimeScalesFactory;
import org.orekit.utils.Constants;
import org.orekit.utils.PVCoordinates;
import space.orbit.backend.io.MeasuredEphemeris;
import space.orbit.backend.io.WodCsvReader;

/**
 * Confirms the measured-ephemeris serving path: WOD samples → Orekit tabulated
 * {@link Ephemeris} returns the MEASURED state at a sample epoch (a node), i.e.
 * the chief is served as truth, not a propagation. Mirrors the core of
 * {@code ScenarioStreamService.prepareEphemerisRole} without Spring/DB.
 */
class MeasuredEphemerisServeTest {

    @BeforeAll
    static void load() {
        OrekitTestData.ensureLoaded();
    }

    @Test
    void ephemerisServesTheMeasuredStateAtASampleEpoch() throws Exception {
        MeasuredEphemeris eph;
        try (InputStream in = getClass().getResourceAsStream("/wod-sample.csv")) {
            eph = new WodCsvReader().parse(in);
        }
        List<MeasuredEphemeris.Sample> samples = eph.samples();
        assertThat(samples).hasSizeGreaterThanOrEqualTo(3);

        Frame eci = FramesFactory.getEME2000();
        double mu = Constants.WGS84_EARTH_MU;
        var utc = TimeScalesFactory.getUTC();
        List<SpacecraftState> states = new ArrayList<>();
        for (MeasuredEphemeris.Sample s : samples) {
            AbsoluteDate d = new AbsoluteDate(Instant.ofEpochMilli(s.epochMillis()), utc);
            PVCoordinates pv = new PVCoordinates(
                    new Vector3D(s.px(), s.py(), s.pz()), new Vector3D(s.vx(), s.vy(), s.vz()));
            states.add(new SpacecraftState(new CartesianOrbit(pv, eci, d, mu)));
        }
        Ephemeris ephemeris = new Ephemeris(states, 2);

        // At a node epoch (the middle sample) interpolation returns the exact measured state.
        MeasuredEphemeris.Sample mid = samples.get(1);
        AbsoluteDate at = new AbsoluteDate(Instant.ofEpochMilli(mid.epochMillis()), utc);
        Vector3D served = ephemeris.getPVCoordinates(at, eci).getPosition();
        assertThat(served.getX()).isCloseTo(mid.px(), within(1e-3));
        assertThat(served.getY()).isCloseTo(mid.py(), within(1e-3));
        assertThat(served.getZ()).isCloseTo(mid.pz(), within(1e-3));
    }

    /**
     * The measured-attitude serving math (slice 2): the parsed raw quaternions are
     * converted to body→ECI three.js order and SLERP-served. At a node, the served
     * quaternion equals the converted raw one; between nodes it stays unit-length.
     * Mirrors {@code ScenarioStreamService.buildMeasuredAttitude} + {@code bodyAttitude}.
     */
    @Test
    void measuredAttitudeSlerpsTheConvertedQuaternion() throws Exception {
        MeasuredEphemeris eph;
        try (InputStream in = getClass().getResourceAsStream("/wod-sample.csv")) {
            eph = new WodCsvReader().parse(in);
        }
        List<MeasuredEphemeris.AttitudeSample> att = eph.attitude();
        assertThat(att).hasSize(2);

        // Build the stride-5 [absEpochSec, qx,qy,qz,qw, ...] series (as the stream does).
        double[] series = new double[att.size() * QuaternionSamples.STRIDE];
        for (int i = 0; i < att.size(); i++) {
            MeasuredEphemeris.AttitudeSample a = att.get(i);
            double[] q = MeasuredAttitude.wodEstAttdToBodyEciXyzw(a.q1(), a.q2(), a.q3(), a.q4());
            int b = i * QuaternionSamples.STRIDE;
            series[b] = a.epochMillis() / 1000.0;
            series[b + 1] = q[0];
            series[b + 2] = q[1];
            series[b + 3] = q[2];
            series[b + 4] = q[3];
        }

        // At the second node, the served quaternion is exactly the converted raw one.
        double[] out = new double[4];
        QuaternionSamples.sampleAt(series, att.get(1).epochMillis() / 1000.0, out);
        double[] expected = MeasuredAttitude.wodEstAttdToBodyEciXyzw(0.5, 0.5, 0.5, 0.5);
        for (int i = 0; i < 4; i++) {
            assertThat(out[i]).isCloseTo(expected[i], within(1e-9));
        }

        // Halfway between the two nodes the SLERP result stays unit-length.
        double mid = (att.get(0).epochMillis() + att.get(1).epochMillis()) / 2000.0;
        QuaternionSamples.sampleAt(series, mid, out);
        assertThat(Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]))
                .isCloseTo(1.0, within(1e-9));
    }

    /**
     * Regression guard for the Runge-overshoot bug: a tabulated ephemeris of a real
     * circular orbit must stay on the circle BETWEEN sample nodes, not just at them.
     * With too many interpolation points (e.g. 4 over ~5-min/~22° spacing) the
     * polynomial overshoots wildly between nodes (radius blew up to ~1e11 km even
     * though the nodes were exact). Two points (cubic Hermite w/ velocity) is stable.
     */
    @Test
    void interpolatesStablyBetweenNodes() {
        Frame eci = FramesFactory.getEME2000();
        double mu = Constants.WGS84_EARTH_MU;
        var utc = TimeScalesFactory.getUTC();
        double r = 6_953_000.0;            // ~575 km LEO
        double v = Math.sqrt(mu / r);      // circular speed
        double n = v / r;                  // mean motion (rad/s)
        double dt = 300.0;                 // 5-min sampling, like the WOD data (~22° per step)
        AbsoluteDate t0 = new AbsoluteDate(Instant.ofEpochMilli(0), utc);

        List<SpacecraftState> states = new ArrayList<>();
        for (int k = 0; k <= 12; k++) {
            double th = n * (k * dt);
            PVCoordinates pv = new PVCoordinates(
                    new Vector3D(r * Math.cos(th), r * Math.sin(th), 0),
                    new Vector3D(-v * Math.sin(th), v * Math.cos(th), 0));
            states.add(new SpacecraftState(new CartesianOrbit(pv, eci, t0.shiftedBy(k * dt), mu)));
        }
        Ephemeris ephemeris = new Ephemeris(states, 2);

        // Sample BETWEEN nodes across the span — radius must stay on the circle.
        for (double t = dt / 2; t < 12 * dt; t += dt) {
            double served = ephemeris.getPVCoordinates(t0.shiftedBy(t), eci).getPosition().getNorm();
            assertThat(served)
                    .as("interpolated radius at t=%.0fs", t)
                    .isCloseTo(r, within(20_000.0)); // cubic Hermite of a 22° arc: well under 20 km
        }
    }
}
