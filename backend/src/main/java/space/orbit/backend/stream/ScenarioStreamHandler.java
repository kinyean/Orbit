package space.orbit.backend.stream;

import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import space.orbit.backend.scenario.ScenarioNotFoundException;

/**
 * Per-connection WebSocket handler for the scenario stream (Phase 4,
 * US-STREAM-02). Unlike the catalog handler this is <strong>not</strong> a
 * broadcast: each connection precomputes one scenario's ephemeris and pushes it
 * (gzipped binary, like the catalog), then stays open idle for the reserved
 * client→server control channel (re-propagation on maneuver edits, Phase 5).
 *
 * <p>Identity + scenario id are captured at handshake by
 * {@link ScenarioHandshakeInterceptor} (the WS thread is outside the security
 * filter window). Typed failures map to application close codes (see
 * {@link StreamContract}); 4403/not-owned is collapsed into 4404 so ids can't
 * be enumerated.
 */
@Component
public class ScenarioStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ScenarioStreamHandler.class);

    private final ScenarioStreamService streamService;

    public ScenarioStreamHandler(ScenarioStreamService streamService) {
        this.streamService = streamService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) {
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(
                rawSession, StreamContract.SEND_TIME_LIMIT_MS, StreamContract.SEND_BUFFER_LIMIT_BYTES);

        String idSegment = (String) rawSession.getAttributes().get(StreamContract.ATTR_SCENARIO_ID);
        String principalName = (String) rawSession.getAttributes().get(StreamContract.ATTR_PRINCIPAL_NAME);

        UUID scenarioId;
        try {
            scenarioId = UUID.fromString(idSegment);
        } catch (IllegalArgumentException | NullPointerException badId) {
            close(session, StreamContract.CLOSE_BAD_REQUEST, "malformed scenario id");
            return;
        }
        if (principalName == null || principalName.isBlank()) {
            // No resolvable caller → can't own anything; collapse to not-found.
            close(session, StreamContract.CLOSE_NOT_FOUND, "no scenario");
            return;
        }

        try {
            EncodedScenario encoded = streamService.loadAndEncode(scenarioId, principalName);
            session.sendMessage(new BinaryMessage(StreamGzip.gzip(encoded.czml())));
            if (encoded.relative() != null) {
                session.sendMessage(new BinaryMessage(StreamGzip.gzip(encoded.relative())));
            }
            log.debug("Scenario stream sent: {} ({} s step) to {}",
                    scenarioId, encoded.effectiveStepSeconds(), session.getId());
            // Keep the socket open (idle) for the reserved Phase-5 control channel.
        } catch (ScenarioNotFoundException notFound) {
            close(session, StreamContract.CLOSE_NOT_FOUND, "no scenario");
        } catch (ScenarioStreamUnprocessableException unusable) {
            close(session, StreamContract.CLOSE_UNPROCESSABLE, "unprocessable scenario");
        } catch (IOException io) {
            log.debug("Scenario stream send failed for {}: {}", scenarioId, io.toString());
            close(session, CloseStatus.SERVER_ERROR.getCode(), "send failed");
        } catch (RuntimeException unexpected) {
            log.warn("Scenario stream failed for {}: {}", scenarioId, unexpected.toString());
            close(session, CloseStatus.SERVER_ERROR.getCode(), "server error");
        }
    }

    /** Reserved client→server control channel — v1 logs and ignores. */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.debug("Ignoring scenario control message (reserved for Phase 5): {} on {}",
                message.getPayload(), session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Scenario stream transport error on {}: {}", session.getId(), exception.toString());
        close(session, CloseStatus.SERVER_ERROR.getCode(), "transport error");
    }

    private static void close(WebSocketSession session, int code, String reason) {
        try {
            session.close(new CloseStatus(code, reason));
        } catch (IOException ignored) {
            // already gone
        }
    }
}
