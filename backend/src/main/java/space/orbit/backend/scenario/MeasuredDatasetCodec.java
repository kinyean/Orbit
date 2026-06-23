package space.orbit.backend.scenario;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import space.orbit.backend.io.MeasuredEphemeris;

/**
 * Compact, deterministic codec for a measured dataset's samples — the blob
 * stored in {@code measured_dataset.samples} (out of the small jsonb scenario
 * body).
 *
 * <p><b>Format v1</b> (position only): {@code int count}, then per sample
 * {@code long epochMillis} + six {@code double}s (pos x/y/z, vel x/y/z, SI).
 *
 * <p><b>Format v2</b> (slice 2 — adds attitude): a leading <em>negative version
 * sentinel</em> {@code int = -2} (a real v1 count is always {@code >= 0}, so the
 * sign disambiguates), then {@code int posCount} + the v1 position rows, then
 * {@code int attCount} + per attitude sample {@code long epochMillis} + four raw
 * quaternion {@code double}s. {@link #decode} reads either — legacy v1 blobs
 * (e.g. a position-only dataset imported before slice 2) still decode, with an
 * empty attitude list.
 *
 * <p>Deterministic (R11): fixed binary layout, and Java's {@code GZIPOutputStream}
 * writes a zero MTIME, so {@link #encode} of identical input yields identical
 * bytes — making the dataset content-addressable via {@link #sha256}.
 */
public final class MeasuredDatasetCodec {

    /** Negative leading int marking the v2 (position + attitude) layout. */
    private static final int FORMAT_V2 = -2;

    private MeasuredDatasetCodec() {}

    /** Decoded contents of a dataset blob: position samples + (optional) attitude. */
    public record Decoded(List<MeasuredEphemeris.Sample> samples,
                          List<MeasuredEphemeris.AttitudeSample> attitude) {}

    /** Position-only convenience (no measured attitude). */
    public static byte[] encode(List<MeasuredEphemeris.Sample> samples) {
        return encode(samples, List.of());
    }

    /** Serialize + gzip the position samples and raw attitude series into the storage blob. */
    public static byte[] encode(List<MeasuredEphemeris.Sample> samples,
                                List<MeasuredEphemeris.AttitudeSample> attitude) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, samples.size() * 56));
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
            out.writeInt(FORMAT_V2);
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
            out.writeInt(attitude.size());
            for (MeasuredEphemeris.AttitudeSample a : attitude) {
                out.writeLong(a.epochMillis());
                out.writeDouble(a.q1());
                out.writeDouble(a.q2());
                out.writeDouble(a.q3());
                out.writeDouble(a.q4());
            }
        } catch (IOException e) {
            throw new IllegalStateException("measured-dataset encode failed", e);
        }
        return bos.toByteArray();
    }

    /** Inflate + deserialize the storage blob (v1 or v2) back into time-ordered samples. */
    public static Decoded decode(byte[] blob) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(blob)))) {
            int first = in.readInt();
            int posCount = first < 0 ? in.readInt() : first; // v2 sentinel vs v1 count
            List<MeasuredEphemeris.Sample> samples = new ArrayList<>(Math.max(0, posCount));
            for (int i = 0; i < posCount; i++) {
                long t = in.readLong();
                samples.add(new MeasuredEphemeris.Sample(t,
                        in.readDouble(), in.readDouble(), in.readDouble(),
                        in.readDouble(), in.readDouble(), in.readDouble()));
            }
            List<MeasuredEphemeris.AttitudeSample> attitude = new ArrayList<>();
            if (first < 0) { // v2 carries an attitude series (possibly empty)
                int attCount = in.readInt();
                for (int i = 0; i < attCount; i++) {
                    long t = in.readLong();
                    attitude.add(new MeasuredEphemeris.AttitudeSample(t,
                            in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble()));
                }
            }
            return new Decoded(samples, attitude);
        } catch (IOException e) {
            throw new IllegalStateException("measured-dataset decode failed", e);
        }
    }

    /** Hex SHA-256 of a blob — the dataset's content hash (reproducibility / dedup). */
    public static String sha256(byte[] blob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
