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
 * Encodes per-deputy LVLH relative-state samples into the {@code scenario-relative}
 * envelope (Phase 4B / US-STREAM-02) — <strong>plain JSON, not CZML</strong>, since
 * the three.js proximity view consumes it directly. Mirrors {@link CzmlEncoder}'s
 * streaming {@link JsonGenerator} style.
 *
 * <p>Positions are rounded to whole metres; velocities to mm/s (relative velocities
 * are small — whole-metre rounding would zero them out). {@code stride} and
 * {@code includeVelocity} are explicit so the client never has to infer the layout.
 */
@Component
public class RelativeStateEncoder {

    private static final JsonFactory JSON = new JsonFactory();
    private static final int MAX_INTERP_DEGREE = 5;

    /**
     * @param epoch           t=0 reference for the sample times (the scenario start)
     * @param stepSeconds     effective sample step (echoed; matches the CZML grid)
     * @param chiefId         NORAD id of the chief (the LVLH origin)
     * @param deputies        one entry per deputy (chief excluded)
     * @param includeVelocity whether each sample carries vR/vI/vC (stride 7 vs 4)
     */
    public String encodeRelative(Instant epoch, int stepSeconds, int chiefId,
                                 List<RelativeSamples> deputies, boolean includeVelocity) {
        int stride = includeVelocity ? 7 : 4;
        StringWriter writer = new StringWriter(1 << 14);
        try (JsonGenerator g = JSON.createGenerator(writer)) {
            g.writeStartObject();
            g.writeStringField("contractVersion", StreamContract.VERSION);
            g.writeStringField("type", StreamContract.MESSAGE_TYPE_SCENARIO_RELATIVE);
            g.writeStringField("epoch", epoch.toString());
            g.writeNumberField("stepSeconds", stepSeconds);
            g.writeStringField("frame", "LVLH");
            g.writeNumberField("chiefId", chiefId);
            g.writeBooleanField("includeVelocity", includeVelocity);
            g.writeNumberField("stride", stride);

            g.writeArrayFieldStart("deputies");
            for (RelativeSamples dep : deputies) {
                writeDeputy(g, dep, stride);
            }
            g.writeEndArray();

            g.writeEndObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode relative state", e);
        }
        return writer.toString();
    }

    private void writeDeputy(JsonGenerator g, RelativeSamples dep, int stride) throws IOException {
        g.writeStartObject();
        g.writeNumberField("noradId", dep.noradId());
        g.writeStringField("name", dep.name() != null ? dep.name() : ("NORAD " + dep.noradId()));
        g.writeNumberField("interpolationDegree", Math.min(MAX_INTERP_DEGREE, dep.interpolationDegree()));

        double[] s = dep.samples();
        g.writeArrayFieldStart("samples");
        for (int base = 0; base + stride <= s.length; base += stride) {
            g.writeNumber(Math.round(s[base]));          // t (whole seconds)
            g.writeNumber(Math.round(s[base + 1]));      // R (whole metres)
            g.writeNumber(Math.round(s[base + 2]));      // I
            g.writeNumber(Math.round(s[base + 3]));      // C
            if (stride == 7) {
                g.writeNumber(roundMillis(s[base + 4])); // vR (mm/s precision)
                g.writeNumber(roundMillis(s[base + 5])); // vI
                g.writeNumber(roundMillis(s[base + 6])); // vC
            }
        }
        g.writeEndArray();
        g.writeEndObject();
    }

    private static double roundMillis(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
