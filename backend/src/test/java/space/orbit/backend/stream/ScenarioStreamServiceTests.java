package space.orbit.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLE;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.FrameService;
import space.orbit.backend.prop.NumericalPropagation;
import space.orbit.backend.prop.OrekitTestData;
import space.orbit.backend.prop.PropagationService;
import space.orbit.backend.prop.SatellitePropagator;
import space.orbit.backend.prop.TleFactory;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioService;

/**
 * {@link ScenarioStreamService} behaviour. Pure JUnit + the Orekit data bundle;
 * {@link ScenarioService#bodyForStream} is mocked so the test stands up the real
 * propagation/frame/encoder stack without a DB. Builds the body from
 * <em>line-string</em> TLEs (proving the {@code new TLE(l1,l2,utc)} rebuild),
 * and pins the R8 sample-cap clamp + the R11 determinism guarantee.
 */
class ScenarioStreamServiceTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID ID = UUID.randomUUID();
    private static final String EMAIL = "dev@orbit.local";

    @BeforeAll
    static void loadData() {
        OrekitTestData.ensureLoaded();
    }

    // --- fixtures -------------------------------------------------------------

    private static ScenarioStreamService service(ScenarioStreamProperties props, ScenarioService scenarioService) {
        FrameService frames = new FrameService();
        frames.init();
        PropagationService prop = new PropagationService(
                new SatellitePropagator(frames), new NumericalPropagation(frames), frames);
        ScenarioStreamService svc = new ScenarioStreamService(scenarioService, prop, frames, new CzmlEncoder(), props);
        svc.init();
        return svc;
    }

    private static TLE leoTle(int norad, String name, double meanAnomalyDeg) {
        TleFactory factory = new TleFactory();
        factory.init();
        GpRecord r = new GpRecord(
                name, "1998-067A", "2024-06-01T12:00:00.000",
                15.50125000, 0.0006703, 51.6416, 247.4627, 130.5360, meanAnomalyDeg,
                norad, 999, 45000, 0.00010270, "U", 0);
        return factory.fromGp(r);
    }

    private static ScenarioBody.Role role(String roleName, TLE tle, String name) {
        // Freeze the canonical line strings — exactly what the composer persists.
        return new ScenarioBody.Role(roleName, tle.getSatelliteNumber(), name,
                new ScenarioBody.InitialState("tle",
                        new ScenarioBody.Tle(tle.getLine1(), tle.getLine2(), tle.getDate().toString())));
    }

    private static ScenarioBody body(String fidelity, String start, String end) {
        ScenarioBody.Role chief = role("chief", leoTle(25544, "ISS (ZARYA)", 325.0), "ISS (ZARYA)");
        ScenarioBody.Role deputy = role("deputy", leoTle(25545, "DEPUTY-1", 5.0), "DEPUTY-1");
        return new ScenarioBody(1, fidelity, new ScenarioBody.TimeRange(start, end), chief, List.of(deputy));
    }

    private static ScenarioService mockBody(ScenarioBody body) {
        ScenarioService scenarioService = mock(ScenarioService.class);
        when(scenarioService.bodyForStream(any(), any())).thenReturn(body);
        return scenarioService;
    }

    // --- tests ----------------------------------------------------------------

    @Test
    void rebuildsLineStringTlesAndEncodesPlausibleLeo() throws Exception {
        ScenarioBody body = body("sgp4", "2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));

        EncodedScenario encoded = svc.loadAndEncode(ID, EMAIL);
        JsonNode root = MAPPER.readTree(encoded.czml());

        assertThat(root.get("type").asText()).isEqualTo(StreamContract.MESSAGE_TYPE_SCENARIO_CZML);
        assertThat(root.get("satelliteCount").asInt()).isEqualTo(2);

        JsonNode chiefPos = root.get("czml").get(1).get("position").get("cartesian");
        double x = chiefPos.get(1).asDouble(), y = chiefPos.get(2).asDouble(), z = chiefPos.get(3).asDouble();
        double rKm = Math.sqrt(x * x + y * y + z * z) / 1000.0;
        assertThat(rKm).as("chief ECEF radius (km)").isBetween(6500.0, 7200.0);

        assertThat(root.get("czml").get(1).get("properties").get("role").asText()).isEqualTo("chief");
        assertThat(root.get("czml").get(2).get("properties").get("role").asText()).isEqualTo("deputy");
    }

    @Test
    void numericalFidelityAlsoEncodes() throws Exception {
        ScenarioBody body = body("numerical", "2024-06-01T12:00:00Z", "2024-06-01T12:20:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));
        JsonNode root = MAPPER.readTree(svc.loadAndEncode(ID, EMAIL).czml());
        assertThat(root.get("satelliteCount").asInt()).isEqualTo(2);
    }

    @Test
    void cwFidelityIsUnprocessable() {
        ScenarioBody body = body("cw", "2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));
        assertThatThrownBy(() -> svc.loadAndEncode(ID, EMAIL))
                .isInstanceOf(ScenarioStreamUnprocessableException.class);
    }

    @Test
    void sampleCapRaisesEffectiveStepAndEchoesIt() throws Exception {
        // 24 h at the requested 30 s would be 2880 samples; cap at 100 forces a
        // larger step, which must be reported (R8 — never silently truncate).
        ScenarioBody body = body("sgp4", "2024-06-01T00:00:00Z", "2024-06-02T00:00:00Z");
        ScenarioStreamService svc = service(new ScenarioStreamProperties(30, 100, true, true), mockBody(body));

        EncodedScenario encoded = svc.loadAndEncode(ID, EMAIL);
        assertThat(encoded.effectiveStepSeconds()).as("effective step (s)").isGreaterThan(30);

        JsonNode root = MAPPER.readTree(encoded.czml());
        assertThat(root.get("stepSeconds").asInt()).isEqualTo(encoded.effectiveStepSeconds());
        // samples per sat = steps + 1 must stay within the cap.
        int samples = root.get("czml").get(1).get("position").get("cartesian").size() / 4;
        assertThat(samples).isLessThanOrEqualTo(100);
    }

    @Test
    void encodingIsBitIdenticalOnRerun() {
        // R11: a pure function of the body — sequential ordered sampling, frozen
        // TLEs, pinned settings, no wall-clock / RNG.
        ScenarioBody body = body("numerical", "2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z");
        ScenarioStreamService a = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));
        ScenarioStreamService b = service(new ScenarioStreamProperties(30, 5000, true, true), mockBody(body));
        assertThat(a.loadAndEncode(ID, EMAIL).czml()).isEqualTo(b.loadAndEncode(ID, EMAIL).czml());
    }
}
