package space.orbit.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.data.DataContext;
import org.orekit.data.DirectoryCrawler;
import space.orbit.backend.io.GpCatalogParser;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;
import space.orbit.backend.stream.CatalogStreamHandler;
import space.orbit.backend.stream.CzmlEncoder;
import space.orbit.backend.stream.StreamContract;

/**
 * End-to-end-ish test of the catalog pipeline (OMM JSON → TLE → SGP4 → CZML)
 * wired by hand (no Spring, no network, no DB). Uses a 2-satellite embedded GP
 * snapshot. Confirms the full chain produces a valid streaming-contract message.
 */
class CatalogServiceTests {

    private static final String TWO_SATS = """
            [
              {"OBJECT_NAME":"ISS (ZARYA)","OBJECT_ID":"1998-067A","EPOCH":"2024-06-01T12:00:00.000",
               "MEAN_MOTION":15.50125,"ECCENTRICITY":0.0006703,"INCLINATION":51.6416,
               "RA_OF_ASC_NODE":247.4627,"ARG_OF_PERICENTER":130.536,"MEAN_ANOMALY":325.0288,
               "EPHEMERIS_TYPE":0,"CLASSIFICATION_TYPE":"U","NORAD_CAT_ID":25544,
               "ELEMENT_SET_NO":999,"REV_AT_EPOCH":45000,"BSTAR":0.0001027,
               "MEAN_MOTION_DOT":1.0e-5,"MEAN_MOTION_DDOT":0},
              {"OBJECT_NAME":"NOAA 19","OBJECT_ID":"2009-005A","EPOCH":"2024-06-01T10:00:00.000",
               "MEAN_MOTION":14.13,"ECCENTRICITY":0.0013,"INCLINATION":99.18,
               "RA_OF_ASC_NODE":120.0,"ARG_OF_PERICENTER":40.0,"MEAN_ANOMALY":320.0,
               "EPHEMERIS_TYPE":0,"CLASSIFICATION_TYPE":"U","NORAD_CAT_ID":33591,
               "ELEMENT_SET_NO":999,"REV_AT_EPOCH":80000,"BSTAR":0.00012,
               "MEAN_MOTION_DOT":1.0e-6,"MEAN_MOTION_DDOT":0}
            ]
            """;

    private static CatalogService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        String path = System.getProperty("orekit.data.path");
        DataContext.getDefault().getDataProvidersManager().addProvider(new DirectoryCrawler(new File(path)));

        FrameService frames = new FrameService();
        frames.init();
        SatellitePropagator propagator = new SatellitePropagator(frames);
        TleFactory tleFactory = new TleFactory();
        tleFactory.init();

        CatalogProperties props = new CatalogProperties(
                null, List.of(), "0 0 0 * * *", 30_000L, 180, 60, 20_000, 0);

        service = new CatalogService(
                props,
                new GpCatalogParser(new ObjectMapper()),
                tleFactory,
                propagator,
                new CzmlEncoder(),
                new CatalogStreamHandler());
        service.init();
    }

    @Test
    void loadsBothSatellites() {
        service.loadFromGpJson(TWO_SATS.getBytes(StandardCharsets.UTF_8), "test");
        assertThat(service.size()).isEqualTo(2);
    }

    @Test
    void buildsValidCatalogCzml() throws Exception {
        service.loadFromGpJson(TWO_SATS.getBytes(StandardCharsets.UTF_8), "test");
        String message = service.buildCatalogMessage(Instant.parse("2024-06-01T12:05:00Z"));

        JsonNode root = mapper.readTree(message);
        assertThat(root.get("contractVersion").asText()).isEqualTo(StreamContract.VERSION);
        assertThat(root.get("satelliteCount").asInt()).isEqualTo(2);

        JsonNode czml = root.get("czml");
        assertThat(czml.get(0).get("id").asText()).isEqualTo("document");
        // document + 2 satellites
        assertThat(czml.size()).isEqualTo(3);

        JsonNode iss = czml.get(1);
        assertThat(iss.get("id").asText()).isEqualTo("sat-25544");
        assertThat(iss.get("name").asText()).isEqualTo("ISS (ZARYA)");
        // window 180s / step 60s -> 4 samples -> 16 cartesian numbers
        assertThat(iss.get("position").get("cartesian").size()).isEqualTo(16);
        // inclination property carried through (~51.6)
        assertThat(iss.get("properties").get("inclinationDeg").get("number").asDouble())
                .isCloseTo(51.6, org.assertj.core.api.Assertions.within(0.1));

        // First ECEF sample should be a plausible LEO radius (~6.6-7.2 Mm).
        JsonNode cart = iss.get("position").get("cartesian");
        double x = cart.get(1).asDouble(), y = cart.get(2).asDouble(), z = cart.get(3).asDouble();
        double radiusKm = Math.sqrt(x * x + y * y + z * z) / 1000.0;
        assertThat(radiusKm).isBetween(6500.0, 7200.0);
    }
}
