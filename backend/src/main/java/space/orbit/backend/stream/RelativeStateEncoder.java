package space.orbit.backend.stream;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import space.orbit.backend.analysis.SensorEvent;
import space.orbit.backend.scenario.ScenarioBody;

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
     * @param epoch             t=0 reference for the sample times (the scenario start)
     * @param stepSeconds       effective sample step (echoed; matches the CZML grid)
     * @param chiefId           NORAD id of the chief (the LVLH origin)
     * @param deputies          one entry per deputy (chief excluded)
     * @param includeVelocity   whether each sample carries vR/vI/vC (stride 7 vs 4)
     * @param fidelity          the scenario fidelity (echoed; {@code "cw"} drives the
     *                          client's validity warning, Phase 5C / US-REL-03)
     * @param maxSeparationM    largest chief-relative range over the window (CW hint)
     * @param chiefEccentricity chief orbit eccentricity (CW assumes near-circular)
     * @param chiefRadiusM      chief geocentric distance at the epoch — lets the
     *                          proximity view place the Earth backdrop along −R at
     *                          the right altitude (Phase 6 / US-PROX-05). Additive —
     *                          contract stays VERSION "1".
     * @param chiefAttitude     chief body-orientation samples in the chief-LVLH scene
     *                          frame (Phase 7), {@code [t,qx,qy,qz,qw, ...]} (stride 5),
     *                          three.js convention; null/empty when absent. Carried in
     *                          a top-level {@code chief} block (the chief is the LVLH
     *                          origin but it has attitude + sensors that must be drawn).
     * @param chiefSensors      the chief's body-fixed sensors (Phase 7); null/empty if none
     * @param events            sensor acquisition/loss events over the window (Phase 7,
     *                          US-EVT-01); null/empty if no sensors. Top-level array.
     */
    public String encodeRelative(Instant epoch, int stepSeconds, int chiefId,
                                 double[] chiefAttitude, List<ScenarioBody.Sensor> chiefSensors,
                                 List<RelativeSamples> deputies, boolean includeVelocity,
                                 String fidelity, double maxSeparationM, double chiefEccentricity,
                                 double chiefRadiusM, List<SensorEvent> events) {
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
            // Fidelity + separation/eccentricity hints (Phase 5C): the client warns
            // when a CW scenario exceeds the linearization's small-separation /
            // near-circular validity envelope. Additive — contract stays VERSION "1".
            if (fidelity != null) {
                g.writeStringField("fidelity", fidelity);
            }
            g.writeNumberField("maxSeparationM", Math.round(maxSeparationM));
            g.writeNumberField("chiefEccentricity", Math.round(chiefEccentricity * 1e6) / 1e6);
            // Chief geocentric radius (Phase 6 / US-PROX-05): the proximity view
            // centers the Earth backdrop at (−chiefRadiusM, 0, 0) in the LVLH scene.
            g.writeNumberField("chiefRadiusM", Math.round(chiefRadiusM));

            // Chief block (Phase 7): the LVLH origin's attitude + sensors so its FOV
            // volumes render and a sensor-frame camera can anchor to them.
            g.writeObjectFieldStart("chief");
            g.writeNumberField("noradId", chiefId);
            writeAttitude(g, chiefAttitude);
            writeSensors(g, chiefSensors);
            g.writeEndObject();

            g.writeArrayFieldStart("deputies");
            for (RelativeSamples dep : deputies) {
                writeDeputy(g, dep, stride);
            }
            g.writeEndArray();

            writeEvents(g, events);

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

        // Closest approach over the scenario window (US-REL-02, Phase 5A). Computed
        // on the live propagators; the frontend only displays it.
        if (dep.tcaEpoch() != null) {
            g.writeStringField("tcaEpoch", dep.tcaEpoch().toString());
            g.writeNumberField("tcaDistanceM", Math.round(dep.tcaDistanceM()));
        }

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

        // Attitude + sensors (Phase 7) — additive, written only when present.
        writeAttitude(g, dep.attitude());
        writeSensors(g, dep.sensors());

        g.writeEndObject();
    }

    /**
     * Attitude samples as a flat {@code att} array {@code [t,qx,qy,qz,qw, ...]}
     * (stride 5). Times whole seconds; quaternion components to 1e-6. Omitted when
     * null/empty (older bodies / stride mismatch) — the client falls back to the
     * derived estimate.
     */
    private void writeAttitude(JsonGenerator g, double[] att) throws IOException {
        if (att == null || att.length < ATT_STRIDE) {
            return;
        }
        g.writeArrayFieldStart("att");
        for (int base = 0; base + ATT_STRIDE <= att.length; base += ATT_STRIDE) {
            g.writeNumber(Math.round(att[base]));            // t (whole seconds)
            g.writeNumber(roundMicros(att[base + 1]));       // qx
            g.writeNumber(roundMicros(att[base + 2]));       // qy
            g.writeNumber(roundMicros(att[base + 3]));       // qz
            g.writeNumber(roundMicros(att[base + 4]));       // qw
        }
        g.writeEndArray();
    }

    /** Static FOV descriptors the frontend builds geometry from. Omitted when empty. */
    private void writeSensors(JsonGenerator g, List<ScenarioBody.Sensor> sensors) throws IOException {
        if (sensors == null || sensors.isEmpty()) {
            return;
        }
        g.writeArrayFieldStart("sensors");
        for (ScenarioBody.Sensor s : sensors) {
            g.writeStartObject();
            g.writeStringField("id", s.id());
            g.writeStringField("kind", s.kind());
            g.writeStringField("name", s.name());
            g.writeObjectFieldStart("fov");
            ScenarioBody.Fov fov = s.fov();
            g.writeStringField("type", fov != null ? fov.type() : "cone");
            if (fov != null) {
                g.writeNumberField("halfAngleDeg", fov.halfAngleDeg());
                g.writeNumberField("hDeg", fov.hDeg());
                g.writeNumberField("vDeg", fov.vDeg());
            }
            g.writeEndObject();
            g.writeNumberField("minRangeM", Math.round(s.minRangeM()));
            g.writeNumberField("maxRangeM", Math.round(s.maxRangeM()));
            g.writeObjectFieldStart("mount");
            double[] b = s.mount() != null ? s.mount().boresightBody() : null;
            g.writeArrayFieldStart("boresightBody");
            if (b != null && b.length == 3) {
                g.writeNumber(roundMicros(b[0]));
                g.writeNumber(roundMicros(b[1]));
                g.writeNumber(roundMicros(b[2]));
            } else {
                g.writeNumber(1);
                g.writeNumber(0);
                g.writeNumber(0);
            }
            g.writeEndArray();
            g.writeNumberField("clockDeg", s.mount() != null ? s.mount().clockDeg() : 0.0);
            g.writeEndObject(); // end mount
            g.writeEndObject(); // end sensor
        }
        g.writeEndArray();
    }

    /** Acquisition/loss events (Phase 7, US-EVT-01). Top-level; omitted when empty. */
    private void writeEvents(JsonGenerator g, List<SensorEvent> events) throws IOException {
        if (events == null || events.isEmpty()) {
            return;
        }
        g.writeArrayFieldStart("events");
        for (SensorEvent e : events) {
            g.writeStartObject();
            g.writeStringField("type", e.type());
            g.writeNumberField("hostId", e.hostId());
            g.writeStringField("sensorId", e.sensorId());
            g.writeNumberField("targetId", e.targetId());
            g.writeStringField("epoch", e.epoch().toString());
            g.writeNumberField("rangeM", Math.round(e.rangeM()));
            g.writeEndObject();
        }
        g.writeEndArray();
    }

    /** Attitude sample stride: {@code [t,qx,qy,qz,qw]}. */
    static final int ATT_STRIDE = 5;

    private static double roundMillis(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double roundMicros(double v) {
        return Math.round(v * 1e6) / 1e6;
    }
}
