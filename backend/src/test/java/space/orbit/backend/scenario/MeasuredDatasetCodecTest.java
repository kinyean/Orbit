package space.orbit.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import space.orbit.backend.io.MeasuredEphemeris;

/**
 * The measured-sample blob codec: lossless round-trip and <em>deterministic</em>
 * encoding (R11) — identical samples encode to identical bytes and the same
 * content hash, so a referencing scenario reproduces byte-for-byte.
 */
class MeasuredDatasetCodecTest {

    private static List<MeasuredEphemeris.Sample> samples() {
        return List.of(
                new MeasuredEphemeris.Sample(1_000L, 6.953e6, 0, 0, 0, 7500, 0),
                new MeasuredEphemeris.Sample(301_000L, 6.9e6, 5e5, 1e5, -500, 7400, 200),
                new MeasuredEphemeris.Sample(601_000L, 6.8e6, 1e6, 2e5, -1000, 7300, 400));
    }

    @Test
    void roundTripsLosslessly() {
        List<MeasuredEphemeris.Sample> in = samples();
        List<MeasuredEphemeris.Sample> out = MeasuredDatasetCodec.decode(MeasuredDatasetCodec.encode(in));
        assertThat(out).isEqualTo(in);
    }

    @Test
    void encodingIsDeterministic() {
        byte[] a = MeasuredDatasetCodec.encode(samples());
        byte[] b = MeasuredDatasetCodec.encode(samples());
        assertThat(a).isEqualTo(b);
        assertThat(MeasuredDatasetCodec.sha256(a)).isEqualTo(MeasuredDatasetCodec.sha256(b));
    }
}
