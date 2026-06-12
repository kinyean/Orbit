package space.orbit.backend.stream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

/**
 * Shared gzip helper for the streaming handlers. Both the catalog broadcast and
 * the per-scenario stream send their JSON as <strong>gzip-compressed binary
 * frames</strong>: CZML is ~10× compressible, and an uncompressed multi-MB frame
 * drains over loopback but resets over a real network within the send-time
 * limit. The client inflates with the native {@code DecompressionStream('gzip')}.
 * See docs/streaming-contract.md.
 */
public final class StreamGzip {

    private StreamGzip() {}

    /** Gzip a UTF-8 string. Throws {@link IllegalStateException} on the (impossible) IO failure. */
    public static byte[] gzip(String message) {
        byte[] raw = message.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, raw.length / 8));
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new IllegalStateException("gzip of stream message failed", e);
        }
        return bos.toByteArray();
    }
}
