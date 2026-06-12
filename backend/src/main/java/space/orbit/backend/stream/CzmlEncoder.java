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

    /** Build the full envelope JSON for one catalog pass (the live broadcast). */
    public String encodeCatalog(Instant epoch, List<CatalogSatelliteSamples> satellites) {
        return encodeCatalog(epoch, satellites, StreamContract.MESSAGE_TYPE_CATALOG);
    }

    /**
     * Build the catalog envelope with an explicit message {@code type} — the live
     * broadcast ({@link StreamContract#MESSAGE_TYPE_CATALOG}) and an on-demand
     * time-travel snapshot ({@link StreamContract#MESSAGE_TYPE_CATALOG_SNAPSHOT})
     * share this identical CZML body, differing only by the tag.
     */
    public String encodeCatalog(Instant epoch, List<CatalogSatelliteSamples> satellites, String messageType) {
        // Pre-size generously; positions dominate (~16 chars/value * 4 * samples * sats).
        StringWriter writer = new StringWriter(1 << 20);
        try (JsonGenerator g = JSON.createGenerator(writer)) {
            g.writeStartObject();
            g.writeStringField("contractVersion", StreamContract.VERSION);
            g.writeStringField("type", messageType);
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

        writeFixedPosition(g, sat.cartesian(), epochIso);

        g.writeEndObject();
    }

    // --- scenario stream (Phase 4, US-STREAM-02) ------------------------------

    /**
     * Build the envelope JSON for one scenario's full ephemeris (the global-view
     * scenario layer). Same FIXED/ECEF position block as the catalog, plus an
     * orbit-path trail and a role-colored marker so chief and deputies read
     * distinctly from the small catalog dots. {@code stepSeconds} is the
     * <em>effective</em> step (echoed, never silently truncated — R8).
     */
    public String encodeScenario(Instant epoch, int stepSeconds, List<ScenarioSatelliteSamples> satellites) {
        StringWriter writer = new StringWriter(1 << 14);
        try (JsonGenerator g = JSON.createGenerator(writer)) {
            g.writeStartObject();
            g.writeStringField("contractVersion", StreamContract.VERSION);
            g.writeStringField("type", StreamContract.MESSAGE_TYPE_SCENARIO_CZML);
            g.writeStringField("epoch", epoch.toString());
            g.writeNumberField("satelliteCount", satellites.size());
            g.writeNumberField("stepSeconds", stepSeconds);

            g.writeArrayFieldStart("czml");
            writeScenarioDocumentPacket(g);
            String epochIso = epoch.toString();
            int deputyIdx = 0;
            for (ScenarioSatelliteSamples sat : satellites) {
                boolean isChief = "chief".equals(sat.role());
                // Chief = amber; each deputy a distinct palette color so they're
                // told apart (marker + trail share the color).
                int[] rgb = isChief
                        ? CHIEF_RGB
                        : SCENARIO_DEPUTY_PALETTE[deputyIdx++ % SCENARIO_DEPUTY_PALETTE.length];
                writeScenarioPacket(g, sat, epochIso, stepSeconds, isChief, rgb);
            }
            g.writeEndArray();

            g.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode scenario CZML", e);
        }
        return writer.toString();
    }

    /**
     * Encode a single satellite's orbit path (one period of ECEF positions) as a
     * compact JSON message: {@code {contractVersion, type:"catalog-orbit",
     * noradId, cartesian:[X,Y,Z, ...]}}. Positions only (a static dashed polyline
     * on the client) — no time. Whole-metre rounded.
     */
    public String encodeOrbit(int noradId, double[] cartesianXYZ) {
        StringWriter writer = new StringWriter(1 << 12);
        try (JsonGenerator g = JSON.createGenerator(writer)) {
            g.writeStartObject();
            g.writeStringField("contractVersion", StreamContract.VERSION);
            g.writeStringField("type", StreamContract.MESSAGE_TYPE_CATALOG_ORBIT);
            g.writeNumberField("noradId", noradId);
            g.writeArrayFieldStart("cartesian");
            for (double v : cartesianXYZ) {
                g.writeNumber(Math.round(v));
            }
            g.writeEndArray();
            g.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode orbit path", e);
        }
        return writer.toString();
    }

    private void writeScenarioDocumentPacket(JsonGenerator g) throws IOException {
        g.writeStartObject();
        g.writeStringField("id", "document");
        g.writeStringField("name", "orbit-scenario");
        g.writeStringField("version", "1.0");
        g.writeEndObject();
    }

    private void writeScenarioPacket(JsonGenerator g, ScenarioSatelliteSamples sat,
                                     String epochIso, int stepSeconds, boolean isChief, int[] rgb)
            throws IOException {
        g.writeStartObject();
        g.writeStringField("id", "scn-" + sat.noradId());
        g.writeStringField("name", sat.name() != null ? sat.name() : ("NORAD " + sat.noradId()));

        g.writeObjectFieldStart("properties");
        writeIntProperty(g, "noradId", sat.noradId());
        writeStringProperty(g, "role", sat.role());
        g.writeEndObject();

        // Role-colored point, larger than the 3 px catalog dot + a white outline.
        g.writeObjectFieldStart("point");
        g.writeNumberField("pixelSize", isChief ? 10 : 8);
        writeRgba(g, "color", rgb, 255);
        writeRgba(g, "outlineColor", WHITE_RGB, 220);
        g.writeNumberField("outlineWidth", 1);
        g.writeEndObject();

        // Orbit-path trail: 1.5 orbital periods long (lead+trail), so it sweeps
        // with the clock instead of showing the whole run statically. Dotted via
        // polylineDash with a small dash length (fine gaps).
        double halfWindow = Math.max(1.0, sat.periodSeconds() * 1.5);
        g.writeObjectFieldStart("path");
        g.writeNumberField("width", 1.5);
        // Sample the (LAGRANGE-interpolated) position much finer than the sample
        // step so the trail is a smooth curve, not straight segments between samples.
        g.writeNumberField("resolution", Math.max(2.0, stepSeconds / 8.0));
        g.writeNumberField("leadTime", halfWindow);
        g.writeNumberField("trailTime", halfWindow);
        g.writeObjectFieldStart("material");
        g.writeObjectFieldStart("polylineDash");
        writeRgba(g, "color", rgb, 230);
        g.writeNumberField("dashLength", 6.0); // small gaps (default is 16)
        g.writeEndObject();
        g.writeEndObject();
        g.writeEndObject();

        writeFixedPosition(g, sat.cartesian(), epochIso);

        g.writeEndObject();
    }

    /**
     * The shared FIXED/ECEF {@code position} block: LAGRANGE-interpolated,
     * degree-clamped, HOLD-extrapolated, whole-unit-rounded. Used by both the
     * catalog and scenario packets so they interpolate identically.
     */
    private void writeFixedPosition(JsonGenerator g, double[] c, String epochIso) throws IOException {
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
    }

    private void writeRgba(JsonGenerator g, String name, int[] rgb, int alpha) throws IOException {
        g.writeObjectFieldStart(name);
        g.writeArrayFieldStart("rgba");
        g.writeNumber(rgb[0]);
        g.writeNumber(rgb[1]);
        g.writeNumber(rgb[2]);
        g.writeNumber(alpha);
        g.writeEndArray();
        g.writeEndObject();
    }

    private void writeStringProperty(JsonGenerator g, String name, String value) throws IOException {
        g.writeStringField(name, value);
    }

    private static final int[] CHIEF_RGB = {255, 209, 102};   // amber/gold
    private static final int[] WHITE_RGB = {255, 255, 255};
    // Distinct per-deputy colors (round-robin by deputy index) so multiple
    // deputies are told apart; the first stays the original cyan.
    private static final int[][] SCENARIO_DEPUTY_PALETTE = {
        {56, 189, 248},   // cyan
        {255, 146, 43},   // orange
        {163, 230, 53},   // lime
        {232, 121, 249},  // orchid
        {45, 212, 191},   // teal
        {244, 114, 182},  // pink
        {129, 140, 248},  // indigo
        {250, 204, 21},   // yellow
    };

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
