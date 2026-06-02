package space.orbit.backend.stream;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Encodes a catalog pass into the streaming-contract envelope (a JSON object
 * carrying {@code contractVersion} + a CZML packet array). See
 * docs/streaming-contract.md.
 *
 * <p>Uses Jackson's streaming {@link JsonGenerator} rather than building a tree:
 * a ~15 k-satellite message is multi-MB, and streaming keeps it to a single
 * pass with correct escaping. Position/time values are rounded to whole units
 * (1 m / 1 s) — invisible at a 3-pixel dot, ~halves the payload.
 */
@Component
public class CzmlEncoder {

    private static final JsonFactory JSON = new JsonFactory();
    private static final int MAX_INTERP_DEGREE = 5;

    /** Build the full envelope JSON for one catalog pass. */
    public String encodeCatalog(Instant epoch, List<CatalogSatelliteSamples> satellites) {
        // Pre-size generously; positions dominate (~16 chars/value * 4 * samples * sats).
        StringWriter writer = new StringWriter(1 << 20);
        try (JsonGenerator g = JSON.createGenerator(writer)) {
            g.writeStartObject();
            g.writeStringField("contractVersion", StreamContract.VERSION);
            g.writeStringField("type", StreamContract.MESSAGE_TYPE_CATALOG);
            g.writeStringField("epoch", epoch.toString());
            g.writeNumberField("satelliteCount", satellites.size());

            g.writeArrayFieldStart("czml");
            writeDocumentPacket(g);
            String epochIso = epoch.toString();
            for (CatalogSatelliteSamples sat : satellites) {
                writeSatellitePacket(g, sat, epochIso);
            }
            g.writeEndArray();

            g.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode catalog CZML", e);
        }
        return writer.toString();
    }

    private void writeDocumentPacket(JsonGenerator g) throws IOException {
        g.writeStartObject();
        g.writeStringField("id", "document");
        g.writeStringField("name", "orbit-catalog");
        g.writeStringField("version", "1.0");
        g.writeEndObject();
    }

    private void writeSatellitePacket(JsonGenerator g, CatalogSatelliteSamples sat, String epochIso)
            throws IOException {
        g.writeStartObject();
        g.writeStringField("id", "sat-" + sat.noradId());
        g.writeStringField("name", sat.name() != null ? sat.name() : ("NORAD " + sat.noradId()));

        g.writeObjectFieldStart("properties");
        writeIntProperty(g, "noradId", sat.noradId());
        writeNumberProperty(g, "inclinationDeg", round1(sat.inclinationDeg()));
        writeNumberProperty(g, "periodMinutes", round1(sat.periodMinutes()));
        g.writeEndObject();

        g.writeObjectFieldStart("point");
        g.writeNumberField("pixelSize", 3);
        g.writeObjectFieldStart("color");
        g.writeArrayFieldStart("rgba");
        g.writeNumber(180);
        g.writeNumber(200);
        g.writeNumber(255);
        g.writeNumber(200);
        g.writeEndArray();
        g.writeEndObject();
        g.writeEndObject();

        double[] c = sat.cartesian();
        int sampleCount = c.length / 4;
        int degree = Math.max(1, Math.min(MAX_INTERP_DEGREE, sampleCount - 1));
        g.writeObjectFieldStart("position");
        g.writeStringField("epoch", epochIso);
        g.writeStringField("interpolationAlgorithm", "LAGRANGE");
        g.writeNumberField("interpolationDegree", degree);
        // Hold the end samples instead of returning "no value" just past the
        // window edge — otherwise dots (and the selection ring) blink out for a
        // frame at the leading edge between chunks.
        g.writeStringField("forwardExtrapolationType", "HOLD");
        g.writeStringField("backwardExtrapolationType", "HOLD");
        g.writeStringField("referenceFrame", "FIXED");
        g.writeArrayFieldStart("cartesian");
        for (double v : c) {
            g.writeNumber(Math.round(v)); // whole metres / whole seconds
        }
        g.writeEndArray();
        g.writeEndObject();

        g.writeEndObject();
    }

    private void writeIntProperty(JsonGenerator g, String name, int value) throws IOException {
        g.writeObjectFieldStart(name);
        g.writeNumberField("number", value);
        g.writeEndObject();
    }

    private void writeNumberProperty(JsonGenerator g, String name, double value) throws IOException {
        g.writeObjectFieldStart(name);
        g.writeNumberField("number", value);
        g.writeEndObject();
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
