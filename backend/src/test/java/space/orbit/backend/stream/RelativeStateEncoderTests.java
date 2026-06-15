package space.orbit.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structural tests for {@link RelativeStateEncoder}. Pure — no Orekit, no Spring.
 * Asserts the {@code scenario-relative} envelope shape: stride/velocity flag,
 * chief id, degree clamp, and whole-metre position / mm-per-s velocity rounding.
 */
class RelativeStateEncoderTests {

    private final RelativeStateEncoder encoder = new RelativeStateEncoder();
    private final ObjectMapper mapper = new ObjectMapper();

    private static RelativeSamples deputyWithVelocity() {
        // 3 samples × stride 7: [t, R,I,C, vR,vI,vC]; sub-unit values to check rounding.
        double[] s = {
                0,   1000.4, -2000.6, 50.5,   0.1234, -0.5678, 0.0009,
                30,  1100.0, -1900.0, 55.0,   0.2000, -0.5000, 0.0010,
                60,  1200.0, -1800.0, 60.0,   0.3000, -0.4000, 0.0011,
        };
        return new RelativeSamples(25545, "DEPUTY-1", 5, s,
                Instant.parse("2026-06-02T01:00:30Z"), 1234.5);
    }

    @Test
    void envelopeCarriesContractMetadata() throws Exception {
        String json = encoder.encodeRelative(
                Instant.parse("2026-06-02T01:00:00Z"), 30, 25544, List.of(deputyWithVelocity()), true,
                "cw", 5000.0, 0.0006703);
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("contractVersion").asText()).isEqualTo(StreamContract.VERSION);
        assertThat(root.get("type").asText()).isEqualTo(StreamContract.MESSAGE_TYPE_SCENARIO_RELATIVE);
        assertThat(root.get("epoch").asText()).isEqualTo("2026-06-02T01:00:00Z");
        assertThat(root.get("stepSeconds").asInt()).isEqualTo(30);
        assertThat(root.get("frame").asText()).isEqualTo("LVLH");
        assertThat(root.get("chiefId").asInt()).isEqualTo(25544);
        assertThat(root.get("includeVelocity").asBoolean()).isTrue();
        assertThat(root.get("stride").asInt()).isEqualTo(7);
        assertThat(root.get("deputies").isArray()).isTrue();
        // CW validity hints (Phase 5C).
        assertThat(root.get("fidelity").asText()).isEqualTo("cw");
        assertThat(root.get("maxSeparationM").asLong()).isEqualTo(5000L);
        assertThat(root.get("chiefEccentricity").asDouble()).isEqualTo(0.00067);
    }

    @Test
    void deputyHasClampedDegreeAndRoundedSamples() throws Exception {
        String json = encoder.encodeRelative(
                Instant.parse("2026-06-02T01:00:00Z"), 30, 25544, List.of(deputyWithVelocity()), true,
                "sgp4", 0.0, 0.0);
        JsonNode dep = mapper.readTree(json).get("deputies").get(0);

        assertThat(dep.get("noradId").asInt()).isEqualTo(25545);
        assertThat(dep.get("interpolationDegree").asInt()).isEqualTo(5); // min(5, 5)

        // Closest approach (US-REL-02): absolute epoch + whole-metre distance.
        assertThat(dep.get("tcaEpoch").asText()).isEqualTo("2026-06-02T01:00:30Z");
        assertThat(dep.get("tcaDistanceM").asLong()).isEqualTo(1235L); // 1234.5 -> 1235 (round half up)

        JsonNode s = dep.get("samples");
        assertThat(s.size()).isEqualTo(3 * 7);
        assertThat(s.get(0).asInt()).isEqualTo(0);         // t
        assertThat(s.get(1).asLong()).isEqualTo(1000L);    // R rounded to whole metres (1000.4)
        assertThat(s.get(2).asLong()).isEqualTo(-2001L);   // I: -2000.6 -> -2001
        assertThat(s.get(4).asDouble()).isEqualTo(0.123);  // vR rounded to mm/s (0.1234)
        assertThat(s.get(5).asDouble()).isEqualTo(-0.568); // vI: -0.5678 -> -0.568
    }

    @Test
    void tcaOmittedWhenAbsent() throws Exception {
        double[] s = {0, 1000, -2000, 50,  30, 1100, -1900, 55};
        var dep = new RelativeSamples(25545, "DEPUTY-1", 1, s, null, 0.0);
        String json = encoder.encodeRelative(
                Instant.parse("2026-06-02T01:00:00Z"), 30, 25544, List.of(dep), false, "sgp4", 0.0, 0.0);
        JsonNode deputy = mapper.readTree(json).get("deputies").get(0);
        assertThat(deputy.has("tcaEpoch")).isFalse();
        assertThat(deputy.has("tcaDistanceM")).isFalse();
    }

    @Test
    void velocityOmittedGivesStrideFour() throws Exception {
        // When velocity is off the service builds stride-4 input ([t,R,I,C]).
        double[] s = {0, 1000, -2000, 50,  30, 1100, -1900, 55,  60, 1200, -1800, 60};
        var deputy = new RelativeSamples(25545, "DEPUTY-1", 3, s, null, 0.0);
        String json = encoder.encodeRelative(
                Instant.parse("2026-06-02T01:00:00Z"), 30, 25544, List.of(deputy), false, "sgp4", 0.0, 0.0);
        JsonNode root = mapper.readTree(json);
        assertThat(root.get("includeVelocity").asBoolean()).isFalse();
        assertThat(root.get("stride").asInt()).isEqualTo(4);
        assertThat(root.get("deputies").get(0).get("samples").size()).isEqualTo(3 * 4);
    }

    @Test
    void degreeClampsForShortWindows() throws Exception {
        double[] s = {0, 1, 2, 3, 30, 4, 5, 6}; // 2 samples, stride 4
        var dep = new RelativeSamples(1, "X", 5, s, null, 0.0);
        String json = encoder.encodeRelative(
                Instant.parse("2026-06-02T01:00:00Z"), 30, 99, List.of(dep), false, "sgp4", 0.0, 0.0);
        // interpolationDegree is min(5, requested); the requested degree here is 5 → 5.
        assertThat(mapper.readTree(json).get("deputies").get(0).get("interpolationDegree").asInt()).isEqualTo(5);
    }
}
