package space.orbit.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structural tests for {@link CzmlEncoder}. Pure — no Orekit, no Spring. Parses
 * the produced envelope and asserts the streaming-contract shape.
 */
class CzmlEncoderTests {

    private final CzmlEncoder encoder = new CzmlEncoder();
    private final ObjectMapper mapper = new ObjectMapper();

    private static CatalogSatelliteSamples iss() {
        // 4 samples over 180s; ECEF metres with sub-metre noise to check rounding.
        double[] cartesian = {
                0,   6778137.4, 1000000.6, 2000000.5,
                60,  6700000.0, 1100000.0, 2100000.0,
                120, 6600000.0, 1200000.0, 2200000.0,
                180, 6500000.0, 1300000.0, 2300000.0,
        };
        return new CatalogSatelliteSamples(25544, "ISS (ZARYA)", 51.6416, 92.83, cartesian);
    }

    @Test
    void envelopeCarriesContractMetadata() throws Exception {
        String json = encoder.encodeCatalog(Instant.parse("2026-06-02T01:00:00Z"), List.of(iss()));
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("contractVersion").asText()).isEqualTo(StreamContract.VERSION);
        assertThat(root.get("type").asText()).isEqualTo(StreamContract.MESSAGE_TYPE_CATALOG);
        assertThat(root.get("epoch").asText()).isEqualTo("2026-06-02T01:00:00Z");
        assertThat(root.get("satelliteCount").asInt()).isEqualTo(1);
        assertThat(root.get("czml").isArray()).isTrue();
    }

    @Test
    void firstPacketIsTheDocument() throws Exception {
        String json = encoder.encodeCatalog(Instant.parse("2026-06-02T01:00:00Z"), List.of(iss()));
        JsonNode czml = mapper.readTree(json).get("czml");

        assertThat(czml.get(0).get("id").asText()).isEqualTo("document");
        assertThat(czml.get(0).get("version").asText()).isEqualTo("1.0");
    }

    @Test
    void satellitePacketHasIdNamePropertiesAndFixedPosition() throws Exception {
        String json = encoder.encodeCatalog(Instant.parse("2026-06-02T01:00:00Z"), List.of(iss()));
        JsonNode sat = mapper.readTree(json).get("czml").get(1);

        assertThat(sat.get("id").asText()).isEqualTo("sat-25544");
        assertThat(sat.get("name").asText()).isEqualTo("ISS (ZARYA)");
        assertThat(sat.get("properties").get("noradId").get("number").asInt()).isEqualTo(25544);
        assertThat(sat.get("properties").get("inclinationDeg").get("number").asDouble()).isEqualTo(51.6);

        JsonNode pos = sat.get("position");
        assertThat(pos.get("referenceFrame").asText()).isEqualTo("FIXED");
        assertThat(pos.get("interpolationAlgorithm").asText()).isEqualTo("LAGRANGE");
        // 4 samples -> degree clamped to min(5, 4-1) = 3
        assertThat(pos.get("interpolationDegree").asInt()).isEqualTo(3);

        JsonNode cart = pos.get("cartesian");
        assertThat(cart.size()).isEqualTo(16);          // 4 samples * (t,x,y,z)
        assertThat(cart.get(0).asInt()).isEqualTo(0);    // first t
        assertThat(cart.get(1).asLong()).isEqualTo(6778137L); // X rounded to whole metres
        assertThat(cart.get(2).asLong()).isEqualTo(1000001L); // Y: 1000000.6 -> 1000001
        assertThat(cart.get(4).asInt()).isEqualTo(60);   // second t
    }

    @Test
    void degreeClampsForShortSampleWindows() throws Exception {
        // Only 2 samples -> degree clamps to 1.
        double[] cartesian = {0, 1, 2, 3, 60, 4, 5, 6};
        var sat = new CatalogSatelliteSamples(1, "X", 0, 0, cartesian);
        String json = encoder.encodeCatalog(Instant.parse("2026-06-02T01:00:00Z"), List.of(sat));
        JsonNode pos = mapper.readTree(json).get("czml").get(1).get("position");
        assertThat(pos.get("interpolationDegree").asInt()).isEqualTo(1);
    }
}
