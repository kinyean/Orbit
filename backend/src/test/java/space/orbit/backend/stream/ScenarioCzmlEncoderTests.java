package space.orbit.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structural tests for {@link CzmlEncoder#encodeScenario}. Pure — no Orekit, no
 * Spring. Asserts the scenario-CZML envelope/packet shape: the {@code stepSeconds}
 * echo, the role property + role-colored marker, the orbit-path trail, the
 * shared FIXED position block (degree-clamp + rounding), and that the catalog
 * encoder is left untouched.
 */
class ScenarioCzmlEncoderTests {

    private final CzmlEncoder encoder = new CzmlEncoder();
    private final ObjectMapper mapper = new ObjectMapper();

    private static ScenarioSatelliteSamples chief() {
        double[] cartesian = {
                0,   6778137.4, 1000000.6, 2000000.5,
                30,  6700000.0, 1100000.0, 2100000.0,
                60,  6600000.0, 1200000.0, 2200000.0,
                90,  6500000.0, 1300000.0, 2300000.0,
        };
        return new ScenarioSatelliteSamples("chief", 25544, "ISS (ZARYA)", 5560.0, 51.64, false, cartesian);
    }

    private static ScenarioSatelliteSamples deputy() {
        double[] cartesian = {0, 6778000, 0, 0, 30, 6700000, 0, 0};
        return new ScenarioSatelliteSamples("deputy", 33591, "NOAA 19", 6120.0, 99.05, false, cartesian);
    }

    /** A maneuvered deputy — its seed-orbit elements are flagged pre-burn. */
    private static ScenarioSatelliteSamples maneuveredDeputy() {
        double[] cartesian = {0, 6778000, 0, 0, 30, 6700000, 0, 0};
        return new ScenarioSatelliteSamples("deputy", 40000, "CHASER", 5700.0, 51.60, true, cartesian);
    }

    @Test
    void envelopeCarriesContractMetadataAndStep() throws Exception {
        String json = encoder.encodeScenario(Instant.parse("2026-06-02T01:00:00Z"), 30, List.of(chief(), deputy()));
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("contractVersion").asText()).isEqualTo(StreamContract.VERSION);
        assertThat(root.get("type").asText()).isEqualTo(StreamContract.MESSAGE_TYPE_SCENARIO_CZML);
        assertThat(root.get("epoch").asText()).isEqualTo("2026-06-02T01:00:00Z");
        assertThat(root.get("satelliteCount").asInt()).isEqualTo(2);
        assertThat(root.get("stepSeconds").asInt()).isEqualTo(30);
        assertThat(root.get("czml").isArray()).isTrue();
    }

    @Test
    void firstPacketIsTheScenarioDocument() throws Exception {
        String json = encoder.encodeScenario(Instant.parse("2026-06-02T01:00:00Z"), 30, List.of(chief()));
        JsonNode czml = mapper.readTree(json).get("czml");
        assertThat(czml.get(0).get("id").asText()).isEqualTo("document");
        assertThat(czml.get(0).get("name").asText()).isEqualTo("orbit-scenario");
    }

    @Test
    void chiefPacketHasRolePropertyPathAndFixedPosition() throws Exception {
        String json = encoder.encodeScenario(Instant.parse("2026-06-02T01:00:00Z"), 30, List.of(chief()));
        JsonNode sat = mapper.readTree(json).get("czml").get(1);

        assertThat(sat.get("id").asText()).isEqualTo("scn-25544");
        assertThat(sat.get("name").asText()).isEqualTo("ISS (ZARYA)");
        assertThat(sat.get("properties").get("role").asText()).isEqualTo("chief");
        assertThat(sat.get("properties").get("noradId").get("number").asInt()).isEqualTo(25544);
        // Seed-orbit elements mirror the catalog packet (so the info panel shows them);
        // a non-maneuvered role omits the `maneuvered` flag.
        assertThat(sat.get("properties").get("inclinationDeg").get("number").asDouble()).isEqualTo(51.6);
        assertThat(sat.get("properties").get("periodMinutes").get("number").asDouble()).isEqualTo(92.7);
        assertThat(sat.get("properties").has("maneuvered")).isFalse();

        // Orbit-path trail: dotted + a finite (one-period) sweeping window, sampled
        // finer than the step so it curves smoothly.
        JsonNode path = sat.get("path");
        assertThat(path.get("resolution").asDouble()).isLessThan(30.0); // finer than the 30 s step
        assertThat(path.get("trailTime").asDouble()).isGreaterThan(0.0); // moves with the clock
        assertThat(path.get("leadTime").asDouble()).isGreaterThan(0.0);
        assertThat(path.get("material").has("polylineDash")).isTrue();   // dotted, not solid

        JsonNode pos = sat.get("position");
        assertThat(pos.get("referenceFrame").asText()).isEqualTo("FIXED");
        assertThat(pos.get("interpolationAlgorithm").asText()).isEqualTo("LAGRANGE");
        // 4 samples -> degree clamped to min(5, 4-1) = 3
        assertThat(pos.get("interpolationDegree").asInt()).isEqualTo(3);

        JsonNode cart = pos.get("cartesian");
        assertThat(cart.size()).isEqualTo(16);
        assertThat(cart.get(0).asInt()).isEqualTo(0);
        assertThat(cart.get(1).asLong()).isEqualTo(6778137L);  // whole metres
        assertThat(cart.get(2).asLong()).isEqualTo(1000001L);  // 1000000.6 -> rounded
        assertThat(cart.get(4).asInt()).isEqualTo(30);          // second t
    }

    @Test
    void deputyPacketCarriesDeputyRole() throws Exception {
        String json = encoder.encodeScenario(Instant.parse("2026-06-02T01:00:00Z"), 30, List.of(chief(), deputy()));
        JsonNode dep = mapper.readTree(json).get("czml").get(2);
        assertThat(dep.get("id").asText()).isEqualTo("scn-33591");
        assertThat(dep.get("properties").get("role").asText()).isEqualTo("deputy");
        // 2 samples -> degree clamps to 1.
        assertThat(dep.get("position").get("interpolationDegree").asInt()).isEqualTo(1);
    }

    @Test
    void maneuveredRoleFlagsSeedOrbitElements() throws Exception {
        String json = encoder.encodeScenario(
                Instant.parse("2026-06-02T01:00:00Z"), 30, List.of(chief(), maneuveredDeputy()));
        JsonNode dep = mapper.readTree(json).get("czml").get(2);

        assertThat(dep.get("properties").get("role").asText()).isEqualTo("deputy");
        // The seed-orbit elements are still emitted (the plane is informative),
        // but flagged so the client marks them pre-burn.
        assertThat(dep.get("properties").get("inclinationDeg").get("number").asDouble()).isEqualTo(51.6);
        assertThat(dep.get("properties").get("maneuvered").get("number").asInt()).isEqualTo(1);
    }
}
