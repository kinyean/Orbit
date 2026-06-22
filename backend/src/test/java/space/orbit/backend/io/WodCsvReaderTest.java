package space.orbit.backend.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parses the stacked-block WOD fixture and checks the normalization rules:
 * km→m / km/s→m/s, six-channel alignment by timestamp, invalid-(0,0,0) drop, and
 * time ordering. Pure — no Orekit, no Spring.
 */
class WodCsvReaderTest {

    @Test
    void parsesAlignsAndNormalizes() throws Exception {
        MeasuredEphemeris eph;
        try (InputStream in = getClass().getResourceAsStream("/wod-sample.csv")) {
            eph = new WodCsvReader().parse(in);
        }

        assertThat(eph.satelliteName()).isEqualTo("TELEOS-2-TEST");
        assertThat(eph.frame()).isEqualTo("EME2000");

        List<MeasuredEphemeris.Sample> s = eph.samples();
        // 5 timestamps in the file: one is (0,0,0) invalid, one (00:20:00) lacks VEL_Z
        // and so isn't present in all six channels → 3 valid aligned states remain.
        assertThat(s).hasSize(3);
        assertThat(s).isSortedAccordingTo((a, b) -> Long.compare(a.epochMillis(), b.epochMillis()));

        // First sample: km→m and km/s→m/s.
        MeasuredEphemeris.Sample first = s.get(0);
        assertThat(first.px()).isCloseTo(6_953_000.0, within(1e-6));
        assertThat(first.py()).isCloseTo(0.0, within(1e-6));
        assertThat(first.vy()).isCloseTo(7_500.0, within(1e-6));

        // Last kept sample is 00:10:00 (the 00:15:00 invalid and 00:20:00 misaligned are gone).
        assertThat(s.get(2).px()).isCloseTo(6_800_000.0, within(1e-6));
        // No surviving sample is a zero-fill.
        assertThat(s).allSatisfy(x ->
                assertThat(Math.sqrt(x.px() * x.px() + x.py() * x.py() + x.pz() * x.pz()))
                        .isGreaterThan(6_000_000.0));
    }
}
