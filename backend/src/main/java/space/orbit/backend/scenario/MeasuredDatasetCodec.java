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
 * Compact, deterministic codec for a measured ephemeris's samples — the blob
 * stored in {@code measured_dataset.samples} (out of the small jsonb scenario
 * body). Layout (before gzip): {@code int count}, then per sample
 * {@code long epochMillis} + six {@code double}s (pos x/y/z, vel x/y/z, SI).
 *
 * <p>Deterministic (R11): fixed binary layout, and Java's {@code GZIPOutputStream}
 * writes a zero MTIME, so {@link #encode} of identical samples yields identical
 * bytes — making the dataset content-addressable via {@link #sha256}.
 */
public final class MeasuredDatasetCodec {

    private MeasuredDatasetCodec() {}

    /** Serialize + gzip the samples into the storage blob. */
    public static byte[] encode(List<MeasuredEphemeris.Sample> samples) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, samples.size() * 56));
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bos))) {
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
        } catch (IOException e) {
            throw new IllegalStateException("measured-dataset encode failed", e);
        }
        return bos.toByteArray();
    }

    /** Inflate + deserialize the storage blob back into time-ordered samples. */
    public static List<MeasuredEphemeris.Sample> decode(byte[] blob) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(blob)))) {
            int count = in.readInt();
            List<MeasuredEphemeris.Sample> out = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                long t = in.readLong();
                out.add(new MeasuredEphemeris.Sample(t,
                        in.readDouble(), in.readDouble(), in.readDouble(),
                        in.readDouble(), in.readDouble(), in.readDouble()));
            }
            return out;
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
