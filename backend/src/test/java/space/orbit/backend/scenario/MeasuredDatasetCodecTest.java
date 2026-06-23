package space.orbit.backend.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import space.orbit.backend.io.MeasuredEphemeris;

/**
 * The measured-sample blob codec: lossless round-trip and <em>deterministic</em>
 * encoding (R11) — identical input encodes to identical bytes and the same
 * content hash, so a referencing scenario reproduces byte-for-byte. Slice 2 adds
 * a parallel attitude series and a backward-compatible v1 (position-only) decode.
 */
class MeasuredDatasetCodecTest {

    private static List<MeasuredEphemeris.Sample> samples() {
        return List.of(
                new MeasuredEphemeris.Sample(1_000L, 6.953e6, 0, 0, 0, 7500, 0),
                new MeasuredEphemeris.Sample(301_000L, 6.9e6, 5e5, 1e5, -500, 7400, 200),
                new MeasuredEphemeris.Sample(601_000L, 6.8e6, 1e6, 2e5, -1000, 7300, 400));
    }

    private static List<MeasuredEphemeris.AttitudeSample> attitude() {
        return List.of(
                new MeasuredEphemeris.AttitudeSample(1_000L, -0.546, -0.0798, 0.0463, 0.8327),
                new MeasuredEphemeris.AttitudeSample(301_000L, -0.1906, -0.7405, 0.6177, 0.1839));
    }

    @Test
    void roundTripsPositionAndAttitude() {
        MeasuredDatasetCodec.Decoded out =
                MeasuredDatasetCodec.decode(MeasuredDatasetCodec.encode(samples(), attitude()));
        assertThat(out.samples()).isEqualTo(samples());
        assertThat(out.attitude()).isEqualTo(attitude());
    }

    @Test
    void positionOnlyDecodesWithEmptyAttitude() {
        MeasuredDatasetCodec.Decoded out = MeasuredDatasetCodec.decode(MeasuredDatasetCodec.encode(samples()));
        assertThat(out.samples()).isEqualTo(samples());
        assertThat(out.attitude()).isEmpty();
    }

    @Test
    void decodesLegacyV1Blob() {
        // A v1 blob predates the version sentinel: it starts with the (non-negative) count
        // and carries no attitude trailer. decode() must still read it (existing datasets).
        byte[] v1 = legacyV1Encode(samples());
        MeasuredDatasetCodec.Decoded out = MeasuredDatasetCodec.decode(v1);
        assertThat(out.samples()).isEqualTo(samples());
        assertThat(out.attitude()).isEmpty();
    }

    @Test
    void encodingIsDeterministic() {
        byte[] a = MeasuredDatasetCodec.encode(samples(), attitude());
        byte[] b = MeasuredDatasetCodec.encode(samples(), attitude());
        assertThat(a).isEqualTo(b);
        assertThat(MeasuredDatasetCodec.sha256(a)).isEqualTo(MeasuredDatasetCodec.sha256(b));
    }

    /** Reproduce the pre-slice-2 (v1) wire format: {@code int count} then 7 fields/sample, gzipped. */
    private static byte[] legacyV1Encode(List<MeasuredEphemeris.Sample> samples) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out =
                new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(bos))) {
            out.writeInt(samples.size());
            for (MeasuredEphemeris.Sample s : samples) {
                out.writeLong(s.epochMillis());
                out.writeDouble(s.px());
                out.writeDouble(s.py());
                out.writeDouble(s.pz());
                out.writeDouble(s.vx());
                out.writeDouble(s.vy());
                out.writeDouble(s.vz());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return bos.toByteArray();
    }
}
