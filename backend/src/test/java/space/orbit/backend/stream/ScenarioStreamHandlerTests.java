package space.orbit.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.orekit.propagation.analytical.tle.TLE;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import space.orbit.backend.TestcontainersConfiguration;
import space.orbit.backend.io.GpRecord;
import space.orbit.backend.prop.TleFactory;
import space.orbit.backend.scenario.Scenario;
import space.orbit.backend.scenario.ScenarioBody;
import space.orbit.backend.scenario.ScenarioRepository;
import space.orbit.backend.scenario.ScenarioVersion;
import space.orbit.backend.scenario.ScenarioVersionRepository;

/**
 * End-to-end WebSocket test for the per-scenario stream against a real Postgres
 * (Testcontainers) and the full Spring context. A {@link StandardWebSocketClient}
 * connects to a seeded scenario, inflates the gzip frame, and asserts the
 * {@code scenario-czml} message; the not-found / malformed-id paths assert the
 * application close codes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ScenarioStreamHandlerTests {

    /** Seeded dev user (V2 migration) — the stream's owner gate. */
    private static final UUID DEV_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @LocalServerPort
    private int port;

    @Autowired private ScenarioRepository scenarios;
    @Autowired private ScenarioVersionRepository versions;
    @Autowired private TleFactory tleFactory;
    @Autowired private ObjectMapper objectMapper;

    private UUID seedScenario(boolean softDeleted) throws Exception {
        TLE tle = tleFactory.fromGp(new GpRecord(
                "ISS (ZARYA)", "1998-067A", "2024-06-01T12:00:00.000",
                15.50125000, 0.0006703, 51.6416, 247.4627, 130.5360, 325.0288,
                25544, 999, 45000, 0.00010270, "U", 0));
        ScenarioBody body = new ScenarioBody(1, "sgp4",
                new ScenarioBody.TimeRange("2024-06-01T12:00:00Z", "2024-06-01T12:30:00Z"),
                new ScenarioBody.Role("chief", 25544, "ISS (ZARYA)",
                        new ScenarioBody.InitialState("tle",
                                new ScenarioBody.Tle(tle.getLine1(), tle.getLine2(), tle.getDate().toString()))),
                List.of());
        String json = objectMapper.writeValueAsString(body);

        UUID scenarioId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        Scenario scenario = new Scenario(scenarioId, DEV_USER, "WS Test " + scenarioId);
        scenarios.saveAndFlush(scenario);
        versions.saveAndFlush(new ScenarioVersion(versionId, scenarioId, 1, DEV_USER, json));
        scenario.setLatestVersionId(versionId);
        if (softDeleted) {
            scenario.setDeletedAt(OffsetDateTime.now());
        }
        scenarios.saveAndFlush(scenario);
        return scenarioId;
    }

    private CollectingHandler connect(String idSegment) throws Exception {
        CollectingHandler handler = new CollectingHandler();
        String uri = "ws://localhost:" + port + "/stream/scenario/" + idSegment;
        new StandardWebSocketClient().execute(handler, uri).get(10, TimeUnit.SECONDS);
        return handler;
    }

    @Test
    void deliversScenarioCzmlForASeededScenario() throws Exception {
        UUID id = seedScenario(false);
        CollectingHandler handler = connect(id.toString());

        assertThat(handler.messageLatch.await(10, TimeUnit.SECONDS)).as("received a message").isTrue();
        JsonNode root = objectMapper.readTree(handler.lastText.get());
        assertThat(root.get("contractVersion").asText()).isEqualTo(StreamContract.VERSION);
        assertThat(root.get("type").asText()).isEqualTo(StreamContract.MESSAGE_TYPE_SCENARIO_CZML);
        assertThat(root.get("czml").isArray()).isTrue();
    }

    @Test
    void nonExistentScenarioClosesWith4404() throws Exception {
        CollectingHandler handler = connect(UUID.randomUUID().toString());
        assertThat(handler.closeLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(handler.closeCode.get()).isEqualTo(StreamContract.CLOSE_NOT_FOUND);
    }

    @Test
    void softDeletedScenarioClosesWith4404() throws Exception {
        UUID id = seedScenario(true);
        CollectingHandler handler = connect(id.toString());
        assertThat(handler.closeLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(handler.closeCode.get()).isEqualTo(StreamContract.CLOSE_NOT_FOUND);
    }

    @Test
    void malformedIdClosesWith4400() throws Exception {
        CollectingHandler handler = connect("not-a-uuid");
        assertThat(handler.closeLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(handler.closeCode.get()).isEqualTo(StreamContract.CLOSE_BAD_REQUEST);
    }

    /** Collects the (gzipped) binary frame and the close status. */
    private static final class CollectingHandler extends AbstractWebSocketHandler {
        final CountDownLatch messageLatch = new CountDownLatch(1);
        final CountDownLatch closeLatch = new CountDownLatch(1);
        final AtomicReference<String> lastText = new AtomicReference<>();
        final AtomicInteger closeCode = new AtomicInteger(-1);

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
            lastText.set(inflate(message.getPayload()));
            messageLatch.countDown();
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closeCode.set(status.getCode());
            closeLatch.countDown();
        }

        private static String inflate(ByteBuffer payload) throws Exception {
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
