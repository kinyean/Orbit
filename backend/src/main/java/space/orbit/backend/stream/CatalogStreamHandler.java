package space.orbit.backend.stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Raw WebSocket handler for the shared catalog feed (Decision 13). Holds the
 * connected sessions and the latest catalog message; {@link #broadcast} both
 * caches that message (for warm-starting new connections) and fans it out.
 *
 * <p>Each session is wrapped in a {@link ConcurrentWebSocketSessionDecorator}
 * because a raw {@code WebSocketSession} is not safe for concurrent sends — a
 * scheduled broadcast and a connect-time warm-start can otherwise collide on
 * the same session.
 *
 * <p>Messages are <strong>gzip-compressed and sent as binary frames</strong>.
 * CZML is highly repetitive (~10x compressible), and an uncompressed multi-MB
 * frame cannot drain to a remote browser within the send-time limit (it works
 * over loopback but resets over a real network). The client inflates with the
 * native DecompressionStream. See docs/streaming-contract.md.
 */
@Component
public class CatalogStreamHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CatalogStreamHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private volatile byte[] latestMessage;

    /** Callback for a client time-travel request (Decision 21). */
    @FunctionalInterface
    public interface SeekHandler {
        /** @param windowSeconds requested window (0 = server default). */
        void onSeek(WebSocketSession session, Instant epoch, int windowSeconds);
    }

    /** Callback for a single-satellite orbit-path request (click-to-toggle). */
    @FunctionalInterface
    public interface OrbitHandler {
        /** @param epoch the orbit's start instant, or {@code null} for server "now". */
        void onOrbit(WebSocketSession session, int noradId, Instant epoch);
    }

    // Set by CatalogService at init: answer a client's "seek"/"orbit" by
    // propagating and replying to that session. Kept as callbacks to avoid a
    // CatalogService↔handler cycle.
    private volatile SeekHandler seekHandler;
    private volatile OrbitHandler orbitHandler;

    public void setSeekHandler(SeekHandler seekHandler) {
        this.seekHandler = seekHandler;
    }

    public void setOrbitHandler(OrbitHandler orbitHandler) {
        this.orbitHandler = orbitHandler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession guarded =
                new ConcurrentWebSocketSessionDecorator(
                        session, StreamContract.SEND_TIME_LIMIT_MS, StreamContract.SEND_BUFFER_LIMIT_BYTES);
        sessions.add(guarded);
        log.debug("Catalog stream connected: {} ({} total)", session.getId(), sessions.size());

        byte[] warmStart = latestMessage;
        if (warmStart != null) {
            sendTo(guarded, warmStart);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // The stored session is the decorator; remove by identity match on the underlying id.
        sessions.removeIf(s -> s.getId().equals(session.getId()));
        log.debug("Catalog stream closed: {} ({} remain)", session.getId(), sessions.size());
    }

    /**
     * Inbound client requests on the catalog socket:
     * <ul>
     *   <li>{@code {"kind":"seek","epoch":"<iso>","windowSeconds":N}} — live
     *       time-travel (Decision 21): reply with a {@code catalog-snapshot};</li>
     *   <li>{@code {"kind":"orbit","noradId":N}} — one satellite's orbit path:
     *       reply with a {@code catalog-orbit}.</li>
     * </ul>
     * Unknown / malformed messages are ignored.
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode root = MAPPER.readTree(message.getPayload());
            String kind = root.path("kind").asText();
            if ("seek".equals(kind)) {
                SeekHandler handler = seekHandler;
                if (handler == null) {
                    return;
                }
                String epochText = root.path("epoch").asText(null);
                if (epochText == null || epochText.isBlank()) {
                    return;
                }
                Instant epoch = Instant.parse(epochText);
                int windowSeconds = root.path("windowSeconds").asInt(0); // 0 → server default
                handler.onSeek(session, epoch, windowSeconds);
            } else if ("orbit".equals(kind)) {
                OrbitHandler handler = orbitHandler;
                if (handler == null || !root.hasNonNull("noradId")) {
                    return;
                }
                String epochText = root.path("epoch").asText(null);
                Instant epoch = (epochText == null || epochText.isBlank()) ? null : Instant.parse(epochText);
                handler.onOrbit(session, root.path("noradId").asInt(), epoch);
            }
        } catch (RuntimeException | IOException e) {
            log.debug("Ignoring malformed catalog request from {}: {}", session.getId(), e.toString());
        }
    }

    /**
     * Send a one-off message to a single connected session (the time-travel
     * snapshot reply). Routed through the stored {@link ConcurrentWebSocketSessionDecorator}
     * for that session so it can't collide with a concurrent broadcast.
     */
    public void sendMessageTo(WebSocketSession session, String message) {
        byte[] compressed = StreamGzip.gzip(message);
        WebSocketSession target = sessions.stream()
                .filter(s -> s.getId().equals(session.getId()))
                .findFirst()
                .orElse(session);
        sendTo(target, compressed);
    }

    /** Compress once, cache as the warm-start frame, and fan out to all sessions. */
    public void broadcast(String message) {
        byte[] compressed = StreamGzip.gzip(message);
        latestMessage = compressed;
        for (WebSocketSession session : sessions) {
            sendTo(session, compressed);
        }
    }

    public int connectionCount() {
        return sessions.size();
    }

    private void sendTo(WebSocketSession session, byte[] compressed) {
        if (!session.isOpen()) {
            sessions.remove(session);
            return;
        }
        try {
            session.sendMessage(new BinaryMessage(compressed));
        } catch (IOException | RuntimeException e) {
            // One dead/slow client must not abort the broadcast to everyone else.
            log.debug("Dropping catalog session {} after send failure: {}", session.getId(), e.toString());
            sessions.remove(session);
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
                // already gone
            }
        }
    }
}
